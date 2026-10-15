#!/usr/bin/env python3
"""Replay S0 -> MobileNet S1 -> RGB -> optional MobileCLIP S3. No online VLM client."""
import argparse
from dataclasses import asdict
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
from time import perf_counter

import cv2
import numpy as np
from PIL import Image

from gate import NoveltyGate, MotionCheck, luminance_grid, grid_difference, novelty
from models import MobileNetEmbedder, MobileCLIPEmbedder
from recording import Recording, fingerprint

HERE = Path(__file__).resolve().parent


def legacy_policy(samples, output):
    source = HERE.parent / "android/app/src/main/java/com/spatialmemory/demo/SceneChangeGate.java"
    homes = [os.environ.get("JAVA_HOME", ""), "/private/tmp/spatial-recorder-jdk17/zulu-17.jdk/Contents/Home"]
    java = javac = None
    for home in homes:
        if home and (Path(home)/"bin/javac").is_file():
            java, javac = str(Path(home)/"bin/java"), str(Path(home)/"bin/javac")
            break
    if java is None:
        java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        return None, {"available":False,"reason":"JDK 17 is required to run the exact installed gate."}
    classes = output / "legacy_classes"
    classes.mkdir(exist_ok=True)
    try:
        subprocess.run([javac,"-d",str(classes),str(source),str(HERE/"LegacyGateReplay.java")],
                       capture_output=True,text=True,check=True)
        lines = "".join(str(s["session_ns"]//1_000_000) + "," +
                        ",".join(f"{v:.8f}" for v in s["grid"].ravel()) + "\n" for s in samples)
        run = subprocess.run([java,"-cp",str(classes),"LegacyGateReplay"],input=lines,
                             capture_output=True,text=True,check=True)
        rows = run.stdout.splitlines()
        if len(rows) != len(samples):
            raise ValueError("Legacy replay did not return one decision per sample.")
        indices = [i for i,line in enumerate(rows) if line.startswith("1\t")]
        return indices, {"available":True,"implementation_sha256":fingerprint(source),
                         "assumption":"Instant accepted RGB capture, immediate stream restart, no queue/network or camera blind interval."}
    except (OSError,subprocess.SubprocessError,ValueError) as error:
        return None, {"available":False,"reason":str(error)}


def capture(recording, request_ns, source_index, config, reason):
    ready = request_ns + int(config["rgb_capture_latency_ms"]*1e6)
    rgb = recording.first_rgb_after(ready, config["max_pair_delay_ms"])
    if rgb is None:
        return None
    return {"request_seconds":request_ns/1e9,"session_seconds":rgb.session_ns/1e9,
            "vga_frame_index":source_index,"rgb_frame_index":rgb.index,"rgb_mp4_pts_us":rgb.pts_us,
            "pair_delay_ms":(rgb.session_ns-request_ns)/1e6,"reason":reason}


def periodic(recording, count, config):
    results = []
    for i in range(count):
        time_ns = recording.start_ns + int(i * (recording.end_ns-recording.start_ns) / max(1,count))
        vga = recording.preceding("vga", time_ns)
        row = capture(recording,time_ns,vga.index if vga else None,config,"periodic_matched_count")
        if row:
            results.append(row)
    return results


def thumbnail(path, image, size=(360,240)):
    img = Image.fromarray(image)
    img.thumbnail(size)
    img.save(path,quality=82)


def replay(args):
    started = perf_counter()
    recording = Recording(args.run)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output/"report.json").exists() and not args.overwrite:
        raise ValueError("Output already contains a report. Use a new folder or --overwrite.")
    assets = output/"assets"
    assets.mkdir(exist_ok=True)
    config = json.loads(args.config.read_text())
    if args.max_captures is not None:
        config["max_rgb_captures"] = args.max_captures
    if config["analysis_hz"] <= 0 or config["max_rgb_captures"] < 1 or config["max_pair_delay_ms"] < 0:
        raise ValueError("Invalid sampling frequency, capture budget, or pairing tolerance.")
    encoder = MobileNetEmbedder(args.vga_model)
    gate = NoveltyGate(config)
    motion = MotionCheck()
    samples, decisions, accepted, model_times = [], [], [], []
    last_grid = None
    next_ns = recording.start_ns
    busy_until_ns = recording.start_ns
    s1_count = 0
    rgb_missing = 0
    budget_blocked = 0
    processed = 0
    try:
        for stamp,image in recording.decode("vga"):
            if stamp.session_ns < next_ns or stamp.session_ns > recording.end_ns:
                continue
            next_ns = stamp.session_ns + int(1e9/config["analysis_hz"])
            begin = perf_counter()
            gray = cv2.cvtColor(image,cv2.COLOR_RGB2GRAY)
            grid = luminance_grid(gray)
            mean = float(grid.mean())
            small = cv2.resize(gray,(320,240),interpolation=cv2.INTER_AREA)
            sharpness = float(cv2.Laplacian(small,cv2.CV_32F).var())
            whole,patch = grid_difference(last_grid,grid) if last_grid is not None else (1.0,1.0)
            quality = config["min_brightness"] <= mean <= config["max_brightness"] and sharpness >= config["min_sharpness"]
            wake = (not gate.bank or last_grid is None or whole >= config["s0_whole_change"] or patch >= config["s0_patch_change"] or gate.pending is not None)
            row = {"sample_index":len(samples),"vga_frame_index":stamp.index,"session_seconds":stamp.session_ns/1e9,
                   "vga_mp4_pts_us":stamp.pts_us,"brightness":mean,"sharpness":sharpness,
                   "s0_whole":whole,"s0_patch":patch,"s1_ran":False,"proposal":False,"promoted":False,
                   "global_distance":None,"region_distance":None,"model_ms":0.0,"reason":"s0_unchanged"}
            row.update(motion.observe(gray))
            features = None
            if stamp.session_ns < busy_until_ns:
                row["reason"] = "local_model_busy"
            elif not quality:
                gate.clear_pending()
                row["reason"] = "poor_image_quality"
            elif wake:
                features,elapsed = encoder.encode(image)
                model_times.append(elapsed)
                s1_count += 1
                decision = gate.observe(features,stamp.session_ns)
                row.update(asdict(decision))
                row.pop("propose",None)
                row["proposal"] = decision.propose
                row["s1_ran"] = True
                row["model_ms"] = elapsed
                last_grid = grid
                done_ns = stamp.session_ns + int((perf_counter()-begin)*1e9)
                busy_until_ns = done_ns
                if decision.propose:
                    if len(accepted) >= config["max_rgb_captures"]:
                        row["reason"] = "capture_budget_reached"
                        budget_blocked += 1
                        gate.clear_pending()
                    else:
                        selected = capture(recording,done_ns,stamp.index,config,decision.reason)
                        if selected is None:
                            row["reason"] = "no_future_rgb_within_tolerance"
                            rgb_missing += 1
                        else:
                            selected["global_distance"] = decision.global_distance
                            selected["region_distance"] = decision.region_distance
                            accepted.append(selected)
                            row["promoted"] = True
                            gate.commit(features)
            if last_grid is None:
                last_grid = grid
            samples.append({"session_ns":stamp.session_ns,"grid":grid})
            row["thumbnail"] = f"vga_{stamp.index}.jpg"
            thumbnail(assets/row["thumbnail"],image)
            decisions.append(row)
            processed += 1
            if processed % 100 == 0:
                print(f"VGA {processed} samples: S1 {s1_count}, proposed RGB {len(accepted)}",flush=True)
    finally:
        encoder.close()
    policies = {"layered":accepted,"periodic_matched_layered":periodic(recording,len(accepted),config)}
    legacy_indices,legacy_info = legacy_policy(samples,output)
    if legacy_indices is not None:
        legacy = []
        for i in legacy_indices:
            row = decisions[i]
            selected = capture(recording,int(row["session_seconds"]*1e9),row["vga_frame_index"],config,"installed_vga_gate")
            if selected: legacy.append(selected)
        policies["installed_gate"] = legacy
        policies["periodic_matched_installed"] = periodic(recording,len(legacy),config)
    # Decode RGB only after S1 has finished its decisions. No reference RGB enters S0/S1.
    selected_indices = {c["rgb_frame_index"] for captures in policies.values() for c in captures}
    rgb_encoder = None
    rgb_features = {}
    rgb_times = []
    if args.rgb_model == "mobileclip2":
        os.environ["HF_HUB_OFFLINE"] = "1"
        rgb_encoder = MobileCLIPEmbedder(HERE/"models/mobileclip")
    try:
        for stamp,image in recording.decode("rgb",selected_indices):
            thumbnail(assets/f"rgb_{stamp.index}.jpg",image,(420,300))
            if rgb_encoder:
                features,elapsed = rgb_encoder.encode(image)
                rgb_features[stamp.index] = features
                rgb_times.append(elapsed)
    finally:
        if rgb_encoder: rgb_encoder.close()
    for policy,captures in policies.items():
        bank = []
        for row in captures:
            row["rgb_thumbnail"] = f"rgb_{row['rgb_frame_index']}.jpg"
            if rgb_encoder:
                features = rgb_features[row["rgb_frame_index"]]
                distance,region,_ = novelty(features,bank)
                duplicate = distance <= config["rgb_duplicate_global"] and region <= config["rgb_duplicate_region"]
                row.update(rgb_global_distance=distance,rgb_region_distance=region,
                           rgb_route="duplicate_visual_evidence" if duplicate else "review_for_vlm")
                if not duplicate:
                    bank.append(features)
                    bank = bank[-config["prototype_limit"]:]
            else:
                row["rgb_route"] = "local_rgb_model_not_run"
    def timing(values):
        return {"count":len(values),"p50_ms":float(np.median(values)) if values else None,
                "p95_ms":float(np.percentile(values,95)) if values else None}
    report = {"schema_version":1,"run_id":recording.run_id,"source_folder":str(recording.folder),
              "task":"New places and objects encountered","interval":[recording.start_ns/1e9,recording.end_ns/1e9],
              "duration_seconds":recording.duration,"parameters":config,"analysis_samples":len(samples),
              "s1_inferences":s1_count,"s1_promoted_rgb":len(accepted),"budget_blocked_proposals":budget_blocked,
              "rgb_pair_misses":rgb_missing,"policies":policies,"legacy_baseline":legacy_info,
              "models":{"vga":"MobileNetV3-Small 0.75 224 embedder","vga_sha256":fingerprint(args.vga_model),
                        "rgb":"MobileCLIP2-S0 / dfndr2b" if rgb_encoder else None,
                        "regions":"Full frame and four overlapping 60% corner crops"},
              "host":{"platform":platform.platform(),"processor":platform.processor(),"python":platform.python_version(),
                      "packages":{name:importlib.metadata.version(name) for name in ["mediapipe","av","numpy","opencv-contrib-python"]}},
              "host_model_timing":{"vga":timing(model_times),"rgb":timing(rgb_times)},
              "elapsed_wall_seconds":perf_counter()-started,"online_vlm_calls":0,
              "accuracy_metrics":None,"annotation_status":"unlabeled",
              "limitations":["Appearance novelty is not confirmed object identity or useful-event recall.",
                "This is a development walk, not a held-out test.",
                "RGB is the first recorded acquisition after a causal request; no camera-switch cost is measured.",
                "Model timing is measured on this computer, not on the glasses.",
                "No learned memory facts, object absence, or calibrated uncertainty are inferred.",
                "Candidate budgets are guardrails and may hide later useful events; inspect budget-blocked proposals."],
              "counterfactual_rgb_note":"At-or-after acquisition, never an earlier or out-of-interval frame."}
    report["model_provenance"] = json.loads((HERE/"models/provenance.json").read_text()) if (HERE/"models/provenance.json").exists() else None
    report["source_sha256"] = {name:fingerprint(recording.folder/name) for name in
                               ["manifest.json","vga_frames.jsonl","rgb_frames.jsonl","vga.mp4","rgb.mp4"]}
    report["stage_counts"] = {"vga_analysis_samples":len(decisions),"s1_checks":s1_count,
        "poor_quality_frames":sum(d["reason"]=="poor_image_quality" for d in decisions),
        "persistent_new_view_proposals":sum(d["proposal"] for d in decisions),"rgb_captures":len(accepted),
        "rgb_duplicate_proposals":sum(c["rgb_route"]=="duplicate_visual_evidence" for c in accepted),
        "vlm_review_candidates":sum(c["rgb_route"]=="review_for_vlm" for c in accepted),"online_vlm_calls":0}
    (output/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    (output/"decisions.jsonl").write_text("".join(json.dumps(row)+"\n" for row in decisions))
    (output/"timeline.json").write_text(json.dumps({role:[{"index":f.index,"session_seconds":f.session_ns/1e9,"pts_seconds":f.pts_us/1e6}
        for f in frames if recording.start_ns-1e9 <= f.session_ns <= recording.end_ns+1e9] for role,frames in recording.frames.items()}))
    (output/"observations.jsonl").write_text("".join(json.dumps({"observation_id":f"{recording.run_id}:rgb:{row['rgb_frame_index']}",
        "source_video":"rgb.mp4","source_run_id":recording.run_id,"video_frame_index":row["rgb_frame_index"],
        "acquisition_session_seconds":row["session_seconds"],"mp4_pts_us":row["rgb_mp4_pts_us"],
        "proposal_type":"visual_novelty","interpretation_status":"unresolved","local_route":row["rgb_route"]})+"\n" for row in accepted))
    print(json.dumps({"report":str(output/"report.json"),"captures":{name:len(rows) for name,rows in policies.items()},
                      "s1_inferences":s1_count,"online_vlm_calls":0},indent=2),flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--config",type=Path,default=HERE/"gate_config.json")
    parser.add_argument("--vga-model",type=Path,default=HERE/"models/mobilenet_v3_small_075_224_embedder.tflite")
    parser.add_argument("--rgb-model",choices=["none","mobileclip2"],default="none")
    parser.add_argument("--max-captures",type=int)
    parser.add_argument("--overwrite",action="store_true")
    args = parser.parse_args()
    try:
        replay(args)
    except (ValueError,RuntimeError,OSError,KeyError) as error:
        parser.exit(1,f"Replay failed: {error}\n")
