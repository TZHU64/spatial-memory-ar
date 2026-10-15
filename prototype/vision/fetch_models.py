#!/usr/bin/env python3
"""Download public weights explicitly; replay itself uses cached weights offline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

HERE = Path(__file__).resolve().parent
VGA_URL = "https://storage.googleapis.com/mediapipe-tasks/image_embedder/mobilenet_v3_small_075_224_embedder.tflite"
VGA_SHA256 = "0f786f8eb8bcecd1f9c5bc50f00a5e6b9f62600edb503ad3bfff5f51035b0ea5"


def fetch(rgb=False):
    directory = HERE/"models"
    directory.mkdir(exist_ok=True)
    target = directory/"mobilenet_v3_small_075_224_embedder.tflite"
    if not target.exists():
        temporary = target.with_suffix(".download")
        with urllib.request.urlopen(VGA_URL, timeout=60) as source, temporary.open("wb") as output:
            while block := source.read(1024*1024): output.write(block)
        temporary.replace(target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != VGA_SHA256:
        raise ValueError("MobileNet weights differ from the pinned, inspected model. Do not run until reviewed.")
    provenance = {"vga":{"url":VGA_URL,"sha256":VGA_SHA256}}
    if rgb:
        os.environ["HF_HUB_DISABLE_XET"] = "1"
        from models import MobileCLIPModel
        model = MobileCLIPModel(directory/"mobileclip")
        model.close()
        provenance["rgb"] = {"model":"MobileCLIP2-S0","pretrained":"dfndr2b",
                             "implementation":"open_clip_torch 3.3.0; official MobileCLIP2 model configuration",
                             "weights":[{"path":str(p.relative_to(directory)),
                                         "sha256":hashlib.sha256(p.read_bytes()).hexdigest()}
                                        for p in (directory/"mobileclip").rglob("*")
                                        if p.is_file() and p.suffix in (".bin",".pt",".safetensors")]}
    (directory/"provenance.json").write_text(json.dumps(provenance,indent=2)+"\n")
    print("Models ready for local inference.",flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rgb",action="store_true")
    fetch(parser.parse_args().rgb)
