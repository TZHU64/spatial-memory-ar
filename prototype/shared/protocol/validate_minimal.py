"""Check minimal design artifacts; does not run an app or a VLM."""

import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


def main():
    root = Path(__file__).resolve().parent
    schema = json.loads((root / "minimal-demo.schema.json").read_text())
    examples = json.loads((root / "minimal-examples.json").read_text())
    Draft202012Validator.check_schema(schema)
    checker = FormatChecker()
    if "date-time" not in checker.checkers:
        raise RuntimeError("Install requirements.txt, including rfc3339-validator")

    def validator(name):
        return Draft202012Validator(
            {"$schema": schema["$schema"], "$defs": schema["$defs"], "$ref": "#/$defs/" + name},
            format_checker=checker,
        )

    for example in examples:
        validator(example["record_type"]).validate(example["value"])

    invalid = []
    def mutate(name, change):
        value = copy.deepcopy(next(x["value"] for x in examples if x["record_type"] == name))
        change(value)
        invalid.append((name, value))

    mutate("observe_request", lambda v: v.pop("captured_at"))
    mutate("observe_request", lambda v: v.update(captured_at="invalid"))
    mutate("observe_request", lambda v: v.update(capture_sequence=0))
    mutate("observe_request", lambda v: v.update(schema_version="0.5"))
    mutate("model_proposal", lambda v: v.update(location_id="invented_room"))
    mutate("model_proposal", lambda v: v.update(target_presence="not_detected"))
    mutate("recall_response", lambda v: v["memory"].update(evidence_status="current"))
    mutate("recall_response", lambda v: v["memory"].update(evidence_status="unknown"))
    for name, value in invalid:
        if validator(name).is_valid(value):
            raise ValueError(f"Invalid {name} unexpectedly passed")

    saved = next(x["value"] for x in examples if x["record_type"] == "observe_response" and x["value"]["outcome"] == "saved")
    missing = next(x["value"] for x in examples if x["record_type"] == "observe_response" and x["value"]["outcome"] == "not_detected")
    if missing["memory"] != saved["memory"]:
        raise ValueError("Nondetection example changed the earlier sighting")
    print(f"PASS: minimal schema, {len(examples)} examples, {len(invalid)} rejection checks; nondetection preserves example memory")
    print("Artifact checks only. App, VLM and device tests remain unrun.")


if __name__ == "__main__":
    main()
