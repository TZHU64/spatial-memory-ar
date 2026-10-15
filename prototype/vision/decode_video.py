"""Private decoder worker. Binary stdout is frame-index / dimensions / PTS / RGB pixels."""
import json
import struct
import sys
from pathlib import Path
import av


def decode(video,log,indices):
    rows=[json.loads(line) for line in Path(log).read_text().splitlines()]
    selected=None if indices=="all" else set(json.loads(indices))
    count=0
    with av.open(video) as container:
        for index,frame in enumerate(container.decode(video=0)):
            if index>=len(rows): raise ValueError("Video contains unlogged frames.")
            pts=round(float(frame.pts*frame.time_base)*1e6)
            if abs(pts-int(rows[index]["mp4_pts_us"]))>3: raise ValueError("Video/log timestamp mismatch.")
            count+=1
            if selected is None or index in selected:
                image=frame.to_ndarray(format="rgb24")
                sys.stdout.buffer.write(struct.pack(">IIIq",index,frame.width,frame.height,pts))
                sys.stdout.buffer.write(image.tobytes())
    if count!=len(rows): raise ValueError("Video ends before its frame log.")


if __name__=="__main__":
    try: decode(*sys.argv[1:])
    except BrokenPipeError: pass
