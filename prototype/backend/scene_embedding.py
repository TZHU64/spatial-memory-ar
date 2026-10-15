"""Live S3 gate: cached local MobileCLIP2 features; an unavailable model never invokes MiMo."""
import atexit
import hashlib
import math
import os
from pathlib import Path
import select
import struct
import subprocess
import threading
import time
from collections import OrderedDict
from PIL import Image, ImageOps

VISION = Path(__file__).resolve().parents[1] / 'vision'
WEIGHTS_SHA256 = 'ab91a1a0c4330d6b1913e24d5035dfdea15423316aaec649610c6b1c6ddd0e95'


def file_sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''): digest.update(block)
    return digest.hexdigest()


def distance(a, b):
    return max(0., min(2., 1. - sum(x*y for x,y in zip(a,b))))


def compare(features, references):
    """Same full-view / nearest-three regional comparison used by the offline gate."""
    ordered = sorted(references, key=lambda item: distance(features[0], item[1][0]))
    if not ordered:
        return None, 1., 1.
    global_distance = distance(features[0], ordered[0][1][0])
    regions = [crop for _,past in ordered[:3] for crop in past[1:]]
    region_distance = max(min(distance(crop,past) for past in regions) for crop in features[1:])
    return ordered[0][0], global_distance, region_distance


class MobileCLIPScreen:
    def __init__(self, encoder=None):
        self.lock = threading.RLock()
        self.cache = OrderedDict()
        self.process = None
        self.encoder = encoder
        self.last_diagnostics = {}
        self.deadline = float('inf')
        atexit.register(self.close)

    def _read(self, count, deadline):
        data=bytearray()
        while len(data)<count:
            remaining=deadline-time.monotonic()
            if remaining<=0 or not select.select([self.process.stdout],[],[],remaining)[0]:
                raise TimeoutError('Local RGB model timed out')
            part=os.read(self.process.stdout.fileno(),count-len(data))
            if not part: raise RuntimeError('Local RGB worker stopped')
            data.extend(part)
        return bytes(data)

    def _start(self):
        if self.process is not None and self.process.poll() is None: return
        # Validate the exact experiment weights before loading. No network downloads at runtime.
        matches=list((VISION/'models/mobileclip').glob('**/open_clip_model.safetensors'))
        if not any(file_sha256(p)==WEIGHTS_SHA256 for p in matches):
            raise RuntimeError('Verified MobileCLIP2 weights unavailable; run vision/fetch_models.py --rgb')
        python=VISION/'.venv/bin/python'
        if not python.is_file(): raise RuntimeError('Local RGB runtime unavailable')
        self.process=subprocess.Popen([str(python),str(VISION/'rgb_worker.py'),str(VISION/'models/mobileclip')],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,bufsize=0,env=dict(os.environ,HF_HUB_OFFLINE='1'))
        if self._read(4,min(self.deadline,time.monotonic()+60))!=b'RDY!': raise RuntimeError('Local RGB model initialization failed')

    def _encode(self, path):
        if self.encoder is not None: return self.encoder(path)
        self._start()
        with Image.open(path) as source:
            image=ImageOps.exif_transpose(source).convert('RGB')
            # Bound pipe input for large stills. This is live preprocessing, not the replay's 720p source.
            image.thumbnail((1280,1280),Image.Resampling.LANCZOS)
            w,h=image.size; data=image.tobytes()
        deadline=min(self.deadline,time.monotonic()+30)
        os.set_blocking(self.process.stdin.fileno(),False)
        view=memoryview(struct.pack('>II',w,h)+data)
        while view:
            remaining=deadline-time.monotonic()
            if remaining<=0 or not select.select([],[self.process.stdin],[],remaining)[1]:
                raise TimeoutError('Local RGB input timed out')
            try: written=os.write(self.process.stdin.fileno(),view[:65536])
            except BlockingIOError: continue
            if not written: raise RuntimeError('RGB worker input failed')
            view=view[written:]
        rows,columns,elapsed=struct.unpack('>IId',self._read(16,deadline))
        if rows!=5 or not 1<=columns<=4096: raise ValueError('Invalid RGB feature dimensions')
        values=struct.unpack('<'+'f'*(rows*columns),self._read(rows*columns*4,deadline))
        features=[list(values[i*columns:(i+1)*columns]) for i in range(rows)]
        if any(not math.isfinite(v) for v in values): raise ValueError('Nonfinite RGB feature')
        if any(abs(sum(v*v for v in row)-1)>.01 for row in features): raise ValueError('Nonunit RGB feature')
        self.last_diagnostics['model_ms']=elapsed
        return features

    def _features(self, path):
        path=Path(path)
        digest=file_sha256(path)
        if digest not in self.cache:
            self.cache[digest]=self._encode(path)
            while len(self.cache)>65: self.cache.popitem(last=False)
        self.cache.move_to_end(digest)
        return self.cache[digest]

    def __call__(self, path, recent_sources):
        with self.lock:
            self.deadline=time.monotonic()+25
            try:
                features=self._features(path)
                refs=[]
                # Only database-retained evidence enters the comparison bank; unsuccessful calls do not.
                for identifier,source in list(recent_sources)[:64]:
                    if Path(source).is_file(): refs.append((identifier,self._features(source)))
                identifier,global_distance,region_distance=compare(features,refs)
                duplicate=identifier is not None and global_distance<=.035 and region_distance<=.070
                self.last_diagnostics.update(global_distance=global_distance,region_distance=region_distance,
                    reference_count=len(refs),route='duplicate' if duplicate else 'vlm')
                return identifier if duplicate else None
            except Exception:
                self.close()
                raise

    def prepare(self):
        with self.lock:
            self.deadline=time.monotonic()+60
            try: self._start()
            except Exception: self.close();raise

    def close(self):
        with self.lock:
            process=self.process;self.process=None
            if process is not None:
                if process.poll() is None: process.terminate()
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: process.kill();process.wait()
                process.stdin.close();process.stdout.close()
