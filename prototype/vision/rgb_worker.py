"""Private local RGB embedding worker. It cannot access VGA history or annotations."""
import struct
import sys
import numpy as np
from models import MobileCLIPModel


def exact(count):
    data=bytearray()
    while len(data)<count:
        part=sys.stdin.buffer.read(count-len(data))
        if not part: break
        data.extend(part)
    return bytes(data)


if __name__=="__main__":
    model=MobileCLIPModel(sys.argv[1])
    sys.stdout.buffer.write(b"RDY!");sys.stdout.buffer.flush()
    try:
        while True:
            header=exact(8)
            if not header: break
            if len(header)!=8: raise ValueError("Truncated image header.")
            w,h=struct.unpack(">II",header)
            if w==h==0: break
            if not 0<w*h<=16_000_000: raise ValueError("Invalid image dimensions.")
            data=exact(w*h*3)
            if len(data)!=w*h*3: raise ValueError("Truncated image.")
            image=np.frombuffer(data,dtype=np.uint8).reshape(h,w,3)
            features,elapsed=model.encode(image)
            sys.stdout.buffer.write(struct.pack(">IId",*features.shape,elapsed))
            sys.stdout.buffer.write(features.astype(np.float32).tobytes());sys.stdout.buffer.flush()
    finally: model.close()
