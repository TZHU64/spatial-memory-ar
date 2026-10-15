#!/usr/bin/env python3
"""Check exported timing logs; optionally verify MP4 samples with ffprobe."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess


def inspect(folder):
    manifest = json.loads((folder / "manifest.json").read_text())
    errors = []
    streams = []
    if manifest.get("state") != "complete":
        errors.append(f"Run is {manifest.get('state')}: {manifest.get('error') or manifest.get('stop_reason')}")
    expected = {"dual": {"vga", "rgb"}, "vga": {"vga"}, "rgb": {"rgb"}}.get(manifest.get("mode"), set())
    if {s.get("role") for s in manifest.get("streams", [])} != expected or not expected:
        errors.append("Requested streams are missing.")
    windows = []
    for stream in manifest.get("streams", []):
        role = stream["role"]
        result = stream.get("result", {})
        video = folder / stream["video"]
        rows = [json.loads(line) for line in (folder / stream["frames"]).read_text().splitlines() if line.strip()]
        pts = [int(row.get("mp4_pts_us", row["encoder_pts_us"])) for row in rows]
        times = [int(row["session_elapsed_ns"]) for row in rows]
        sensors = [int(row["sensor_timestamp_ns"]) for row in rows]
        if not result.get("complete") or not rows or len(rows) != result.get("encoded_frames"):
            errors.append(f"{role}: incomplete frame log.")
        for label, values in [("video", pts), ("session", times), ("sensor", sensors)]:
            if any(b <= a for a, b in zip(values, values[1:])):
                errors.append(f"{role}: {label} timestamps are not increasing.")
        if [r["video_frame_index"] for r in rows] != list(range(len(rows))):
            errors.append(f"{role}: frame indices are not contiguous.")
        if not video.is_file() or video.stat().st_size != result.get("video_bytes"):
            errors.append(f"{role}: missing or truncated video.")
        if rows:
            windows.append((times[0], times[-1]))
        duration = (pts[-1] - pts[0]) / 1e6 if len(pts) > 1 else 0
        info = {"role": role, "frames": len(rows), "duration_seconds": round(duration, 3),
                "actual_fps": round((len(rows) - 1) / duration, 3) if duration else 0,
                "max_gap_ms": round(max((b - a for a, b in zip(pts, pts[1:])), default=0) / 1000, 3)}
        if shutil.which("ffprobe") and video.is_file():
            probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                                    "-show_entries", "packet=pts_time", "-of", "json", str(video)],
                                   capture_output=True, text=True, check=True)
            packets = json.loads(probe.stdout).get("packets", [])
            packet_pts = [round(float(p["pts_time"]) * 1e6) for p in packets]
            if len(packet_pts) != len(pts) or any(abs(a - b) > 2 for a, b in zip(packet_pts, pts)):
                errors.append(f"{role}: MP4 packets do not match the timing log.")
            info["mp4_checked_with_ffprobe"] = True
        else:
            info["mp4_checked_with_ffprobe"] = False
        streams.append(info)
    overlap = max(0, min(b for _, b in windows) - max(a for a, _ in windows)) / 1e9 if windows else 0
    if manifest.get("mode") == "dual" and (not manifest.get("both_streams_observed") or overlap <= 0):
        errors.append("No overlapping dual-camera interval was recorded.")
    return {"run_id": manifest.get("run_id"), "valid": not errors, "streams": streams,
            "overlap_seconds": round(overlap, 3), "hardware_frame_sync": False,
            "cross_camera_sensor_clock_comparable": manifest.get("cross_camera_sensor_clock_comparable", False),
            "errors": errors}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    try:
        report = inspect(args.run)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Invalid recording: {error}\n")
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["valid"] else 1)
