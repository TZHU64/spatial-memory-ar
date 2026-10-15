"""Decode frame indices and acquisition clocks; never synthesize missing observations."""
from dataclasses import dataclass
from bisect import bisect_left, bisect_right
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

import cv2
import numpy as np


@dataclass(frozen=True)
class Stamp:
    index: int
    session_ns: int
    sensor_ns: int
    callback_ns: int
    pts_us: int


class Recording:
    def __init__(self, folder):
        self.folder = Path(folder).resolve()
        self.manifest = json.loads((self.folder / "manifest.json").read_text())
        if self.manifest.get("state") != "complete" or self.manifest.get("mode") != "dual":
            raise ValueError("Choose a completed dual-camera recording.")
        if not self.manifest.get("cross_camera_sensor_clock_comparable"):
            raise ValueError("This replay requires comparable sensor clocks. Calibrate UNKNOWN clocks first.")
        self.specs = {s["role"]: s for s in self.manifest["streams"]}
        if set(self.specs) != {"vga", "rgb"}:
            raise ValueError("Both camera streams are required.")
        self.frames = {}
        for role, spec in self.specs.items():
            rows = [json.loads(x) for x in self.safe_file(spec["frames"]).read_text().splitlines() if x.strip()]
            frames = [Stamp(int(r["video_frame_index"]), int(r["session_elapsed_ns"]),
                            int(r["sensor_timestamp_ns"]), int(r["callback_elapsed_ns"]), int(r["mp4_pts_us"])) for r in rows]
            if not frames or [f.index for f in frames] != list(range(len(frames))):
                raise ValueError(f"{role}: missing or disordered frame indices.")
            if any(b.session_ns <= a.session_ns or b.pts_us <= a.pts_us for a, b in zip(frames, frames[1:])):
                raise ValueError(f"{role}: timestamps are not increasing.")
            if len(frames) != spec["result"]["encoded_frames"]:
                raise ValueError(f"{role}: manifest frame count mismatch.")
            self.frames[role] = frames
        self.start_ns = max(f[0].session_ns for f in self.frames.values())
        # User Stop is the end of the experiment, even if a worker finalized late.
        stops = [int(e["elapsed_ns"]) - int(self.manifest["start_elapsed_ns"])
                 for e in self.manifest.get("events", []) if e["type"] == "stop_requested"]
        self.end_ns = min([f[-1].session_ns for f in self.frames.values()] + stops)
        if self.end_ns <= self.start_ns:
            raise ValueError("The streams have no shared acquisition interval.")
        self.rgb_times = [s.session_ns for s in self.frames["rgb"]]

    def safe_file(self, name):
        path = (self.folder / name).resolve()
        if path.parent != self.folder:
            raise ValueError("Recording file must be directly inside the session folder.")
        return path

    @property
    def run_id(self):
        return self.manifest["run_id"]

    @property
    def duration(self):
        return (self.end_ns - self.start_ns) / 1e9

    def first_rgb_after(self, request_ns, max_delay_ms):
        index = bisect_left(self.rgb_times, request_ns)
        if index == len(self.rgb_times):
            return None
        frame = self.frames["rgb"][index]
        if frame.session_ns > self.end_ns or frame.session_ns - request_ns > max_delay_ms * 1e6:
            return None
        return frame

    def preceding(self, role, session_ns):
        times = [f.session_ns for f in self.frames[role]]
        i = bisect_right(times, session_ns) - 1
        return self.frames[role][i] if i >= 0 else None

    def decode(self, role, indices=None):
        requested = None if indices is None else set(indices)
        path = self.safe_file(self.specs[role]["video"])
        expected = self.frames[role]
        orientation = self.specs[role].get("orientation_degrees", 0)
        # PyAV and OpenCV bundle different FFmpeg libraries on macOS. Isolate decoding
        # to avoid duplicate Objective-C runtime classes in the model process.
        command = [sys.executable,str(Path(__file__).with_name("decode_video.py")),str(path),
                   str(self.safe_file(self.specs[role]["frames"])),json.dumps(sorted(requested)) if requested is not None else "all"]
        process = subprocess.Popen(command,stdout=subprocess.PIPE)
        def read_exact(size):
            blocks = bytearray()
            while len(blocks)<size:
                chunk = process.stdout.read(size-len(blocks))
                if not chunk: break
                blocks.extend(chunk)
            return bytes(blocks)
        try:
            while True:
                header = read_exact(20)
                if not header: break
                if len(header)!=20: raise ValueError("Truncated decoder header.")
                index,w,h,actual_us = struct.unpack(">IIIq",header)
                if index>=len(expected) or w*h>16_000_000 or abs(actual_us-expected[index].pts_us)>3:
                    raise ValueError(f"{role}: unexpected decoded frame or timestamp.")
                data = read_exact(w*h*3)
                if len(data)!=w*h*3: raise ValueError("Truncated decoded frame.")
                image = np.frombuffer(data,dtype=np.uint8).reshape(h,w,3)
                if orientation == 90:
                    image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
                elif orientation == 180:
                    image = cv2.rotate(image, cv2.ROTATE_180)
                elif orientation == 270:
                    image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
                yield expected[index], image
            if process.wait()!=0:
                raise ValueError(f"{role}: decoder or timestamp verification failed.")
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.terminate()
                process.wait()


def fingerprint(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()
