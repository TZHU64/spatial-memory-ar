#!/usr/bin/env python3
"""Copy finalized Walk Recorder sessions from glasses connected by USB."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

from inspect_recording import inspect

REMOTE = "/sdcard/Android/data/com.spatialmemory.recorder/files/Movies/WalkRecorder"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="Local folder for exported runs")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--serial", help="Select one device when several are connected")
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    listing = subprocess.run(adb + ["shell", "ls", "-1", REMOTE], capture_output=True, text=True, check=True)
    runs = [line.strip() for line in listing.stdout.splitlines() if re.fullmatch(r"\d{8}_\d{6}_[0-9a-f]{8}", line.strip())]
    args.destination.mkdir(parents=True, exist_ok=True)
    count = 0
    for run in sorted(runs):
        content = subprocess.run(adb + ["shell", "cat", f"{REMOTE}/{run}/manifest.json"],
                                 capture_output=True, text=True, check=True)
        manifest = json.loads(content.stdout)
        if manifest.get("state") not in ("complete", "incomplete"):
            print(f"Skipping unfinished run {run}; stop recording first.")
            continue
        destination = args.destination / run
        # Avoid nesting or overwriting a previous export.
        if destination.exists():
            print(f"Already exported: {run}")
            continue
        subprocess.run(adb + ["pull", f"{REMOTE}/{run}", str(destination)], check=True)
        try:
            report = inspect(destination)
        except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
            report = {"valid": False, "errors": [str(error)]}
        (destination / "inspection.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"{run}: {'verified' if report['valid'] else 'incomplete; inspect diagnostics'}")
        count += 1
    print(f"Copied {count} run(s). Recordings remain on the glasses.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Export failed: {error}")
