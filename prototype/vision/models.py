"""Actual local encoders. Missing weights are an error, never a synthetic fallback."""
from pathlib import Path
from time import perf_counter
import struct
import subprocess
import sys
import numpy as np

REGIONS = ((0, 0, 1, 1), (0, 0, .6, .6), (.4, 0, 1, .6), (0, .4, .6, 1), (.4, .4, 1, 1))


def crops(image):
    h, w = image.shape[:2]
    return [np.ascontiguousarray(image[int(y0*h):int(y1*h), int(x0*w):int(x1*w)]) for x0,y0,x1,y1 in REGIONS]


def normalized(values):
    values = np.asarray(values, dtype=np.float32)
    return values / np.maximum(np.linalg.norm(values, axis=-1, keepdims=True), 1e-9)


class MobileNetEmbedder:
    def __init__(self, path):
        import mediapipe as mp
        self.mp = mp
        if not Path(path).is_file():
            raise FileNotFoundError(f"MobileNet model is missing: {path}. Run fetch_models.py first.")
        self.embedder = mp.tasks.vision.ImageEmbedder.create_from_options(mp.tasks.vision.ImageEmbedderOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(path),delegate=mp.tasks.BaseOptions.Delegate.CPU), l2_normalize=True))

    def encode(self, image):
        start = perf_counter()
        features = []
        # VGA is explicitly monochrome, replicated into the pretrained model's three channels.
        gray = np.mean(image.astype(np.float32), axis=2).astype(np.uint8)
        image = np.repeat(gray[:, :, None], 3, axis=2)
        for crop in crops(image):
            result = self.embedder.embed(self.mp.Image(image_format=self.mp.ImageFormat.SRGB, data=crop))
            features.append(result.embeddings[0].embedding)
        return normalized(features), (perf_counter() - start) * 1000

    def close(self):
        self.embedder.close()


class MobileCLIPModel:
    def __init__(self, cache_dir):
        import open_clip
        import torch
        self.torch = torch
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(
            "MobileCLIP2-S0", pretrained="dfndr2b", device="cpu", cache_dir=str(cache_dir))
        self.model.eval()
        torch.set_num_threads(2)

    def encode(self, image):
        from PIL import Image
        start = perf_counter()
        inputs = self.torch.stack([self.preprocess(Image.fromarray(c)) for c in crops(image)])
        with self.torch.inference_mode():
            features = self.model.encode_image(inputs).float().cpu().numpy()
        return normalized(features), (perf_counter() - start) * 1000

    def close(self):
        pass


class MobileCLIPEmbedder:
    """Isolate Torchvision's video imports from MediaPipe/OpenCV's FFmpeg on macOS."""
    def __init__(self, cache_dir):
        self.process = subprocess.Popen([sys.executable,str(Path(__file__).with_name("rgb_worker.py")),str(cache_dir)],
                                        stdin=subprocess.PIPE,stdout=subprocess.PIPE)
        if self._read(4) != b"RDY!":
            self.close()
            raise RuntimeError("The local RGB model worker could not initialize.")

    def _read(self, count):
        value = bytearray()
        while len(value)<count:
            part = self.process.stdout.read(count-len(value))
            if not part: break
            value.extend(part)
        return bytes(value)

    def encode(self,image):
        h,w = image.shape[:2]
        self.process.stdin.write(struct.pack(">II",w,h))
        self.process.stdin.write(np.ascontiguousarray(image).tobytes())
        self.process.stdin.flush()
        header = self._read(16)
        if len(header)!=16: raise RuntimeError("The local RGB model stopped.")
        rows,columns,elapsed = struct.unpack(">IId",header)
        if rows!=5 or not 1<=columns<=4096: raise RuntimeError("Invalid local RGB feature shape.")
        buffer = self._read(rows*columns*4)
        if len(buffer)!=rows*columns*4: raise RuntimeError("Truncated RGB model response.")
        return np.frombuffer(buffer,dtype=np.float32).reshape(rows,columns).copy(),elapsed

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write(struct.pack(">II",0,0));self.process.stdin.flush()
            self.process.stdin.close()
            self.process.wait(timeout=10)
        except (OSError,subprocess.TimeoutExpired):
            self.process.kill();self.process.wait()
        finally:
            self.process.stdout.close()
