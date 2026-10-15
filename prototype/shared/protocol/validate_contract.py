"""Validate preparation artifacts, not application behavior or hardware capability."""

import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parent


def read(name):
    return json.loads((ROOT / name).read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    protocol = read("protocol.schema.json")
    fixture_schema = read("fixture.schema.json")
    registry = Registry()
    for schema in (protocol, fixture_schema):
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    wire_validator = Draft202012Validator(protocol, registry=registry, format_checker=FormatChecker())
    require("date-time" in wire_validator.format_checker.checkers, "Install all requirements, including rfc3339-validator")
    fixture_validator = Draft202012Validator(fixture_schema, registry=registry, format_checker=FormatChecker())
    messages = read("examples/messages.json")
    for message in messages:
        wire_validator.validate(message)
    expected_types = {k.removesuffix("_message") for k in protocol["$defs"] if k.endswith("_message") and k != "wire_message"}
    require({m["message_type"] for m in messages} == expected_types, "Missing message-type example")

    files = sorted((ROOT / "fixtures").glob("*.json"))
    steps = 0
    for path in files:
        fixture = json.loads(path.read_text())
        fixture_validator.validate(fixture)
        frames, queries, results, assessments, observations = {}, {}, {}, {}, {}
        invalidated = set()
        deleted = False
        for step in fixture["steps"]:
            steps += 1
            for record in step["inputs"]:
                kind, value = record["record_type"], record["value"]
                if kind == "wire_message":
                    payload = value["payload"]
                    if value["message_type"] == "query":
                        queries[payload["request_id"]] = payload
                    elif value["message_type"] == "frame":
                        frames[payload["frame_id"]] = payload
                        if payload["request_id"] is not None:
                            q = queries[payload["request_id"]]
                            require(q["intent"] == "check_region", "Check frame linked to recall")
                            require(payload["capture_epoch_id"] == q["capture_epoch_id"], "Check epoch mismatch")
                            started = payload["acquisition_started_monotonic_ms"]
                            require(started >= q["accepted_monotonic_ms"], "Acquisition starts before check request")
                            delta = payload["captured_at"]["capture_monotonic_ms"] - started
                            require(0 <= delta <= 2000, "Fixture check frame is not freshly acquired")
                    elif value["message_type"] == "session_command" and payload["command"] == "reset":
                        deleted = True
                        observations.clear()
                elif kind == "scene_result":
                    frame = frames[value["frame_id"]]
                    require(value["request_id"] == frame["request_id"], "Model/frame request mismatch")
                    results[value["job_id"]] = value
                elif kind == "region_assessment":
                    frame = frames[value["frame_id"]]
                    require(frame["request_id"] == value["request_id"], "Assessment/frame request mismatch")
                    require(value["region"] == queries[value["request_id"]]["region"], "Assessment region mismatch")
                    assessments[value["assessment_id"]] = value
                elif kind == "local_event" and value["event"] in ("continuity_lost", "lease_expired"):
                    invalidated.add(value["frame_id"])
            require(not deleted or not step["expected_observations"], "Deleted fixture expects a new observation")
            for observation in step["expected_observations"]:
                frame = frames[observation["frame_id"]]
                result = results[observation["inference_job_id"]]
                require(observation["captured_at"] == frame["captured_at"], "Observation changed capture time")
                require(observation["capture_epoch_id"] == frame["capture_epoch_id"], "Observation changed epoch")
                require(result["frame_id"] == observation["frame_id"], "Observation cites unrelated inference")
                require(result["status"] == "complete", "Failed model result cannot qualify")
                if observation["kind"] == "negative":
                    assessment = assessments[observation["region_assessment_id"]]
                    require(all(assessment[k] == "pass" for k in ("registration", "coverage", "quality", "occlusion")), "Negative has failed region gates")
                    require(assessment["covers_prior_uncertainty_region"] and assessment["operating_envelope_validated"], "Negative lacks validated full-region support")
                    require(result["target_check"] is not None and result["target_check"]["target_presence"] == "not_detected", "Negative inferred from omission")
                    require(result["target_check"]["region"] == assessment["region"] == observation["region"], "Negative region mismatch")
                observations[observation["observation_id"]] = observation
            answer = step["expected_answer"]
            if answer:
                require(not deleted, "Deleted fixture expects a memory answer")
                require(answer["intent"] == queries[answer["request_id"]]["intent"], "Answer intent mismatch")
                require(all(e in observations for e in answer["evidence_ids"]), "Answer cites missing observation")
                if answer["evidence_status"] == "current":
                    lease = answer["current_lease"]
                    require(fixture["initial_config"]["current_claims_enabled"], "Current disabled in fixture")
                    require(lease["source_frame_id"] not in invalidated, "Current from invalidated view")
                    capture = frames[lease["source_frame_id"]]["captured_at"]
                    require(capture["capture_monotonic_ms"] == lease["capture_monotonic_ms"], "Lease uses receipt time")

    # Rejection checks guard the most consequential structural requirements.
    cases = []
    def changed(kind, mutate):
        item = copy.deepcopy(next(m for m in messages if m["message_type"] == kind))
        mutate(item)
        cases.append(item)
    changed("hello", lambda x: x.update(session_id="unexpected_session"))
    changed("frame", lambda x: x["payload"].pop("captured_at"))
    changed("frame", lambda x: x["payload"].update(payload_bytes=8388609))
    changed("frame", lambda x: x["payload"].update(sensor_timestamp_ns=123456))
    changed("query", lambda x: x["payload"].update(intent="check_region"))
    changed("answer", lambda x: x["payload"].update(evidence_status="current"))
    changed("answer", lambda x: x["payload"].update(evidence_status="historical_with_later_negative_evidence"))
    changed("frame", lambda x: x.update(schema_version="0.4"))
    changed("frame", lambda x: x["payload"]["captured_at"].update(wall_time="not-a-time"))
    for case in cases:
        require(not wire_validator.is_valid(case), "Invalid mutation unexpectedly passed")
    print(f"PASS: 2 schemas, {len(messages)} wire examples, {len(files)} fixtures / {steps} steps, {len(cases)} structural rejection checks")
    print("Static contract/provenance checks only; no application, model or device acceptance tests were run.")


if __name__ == "__main__":
    main()
