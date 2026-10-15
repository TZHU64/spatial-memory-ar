"""Evaluator-only event scoring. These labels are never inputs to the gate."""
import json


def score(events, captures, start_seconds, end_seconds, review_complete=False):
    required = [event for event in events if event.get("importance") == "required"]
    invalid = [event for event in events if not (
        isinstance(event.get("start"), (int,float)) and isinstance(event.get("end"), (int,float)) and
        start_seconds <= event["start"] <= event["end"] <= end_seconds)]
    if invalid:
        raise ValueError("Annotations must have visible start/end times inside the shared interval.")
    hits = [any(event["start"] <= capture["session_seconds"] <= event["end"] for capture in captures)
            for event in required]
    relevant = [event for event in events if event.get("importance") in ("required", "optional")]
    useful = [any(event["start"] <= capture["session_seconds"] <= event["end"] for event in relevant)
              for capture in captures]
    complete = review_complete
    return {"required_events":len(required), "captured_required_events":sum(hits),
            "event_recall": sum(hits)/len(required) if required else None,
            "captures_matching_labeled_events":sum(useful),
            "unmatched_captures":len(captures)-sum(useful),
            "capture_precision":sum(useful)/len(captures) if complete and captures else None,
            "annotation_complete":complete,
            "missed_events":[event for event,hit in zip(required,hits) if not hit]}


def evaluate(output_folder, annotations):
    report = json.loads((output_folder / "report.json").read_text())
    document = json.loads(annotations.read_text())
    if document.get("run_id") != report["run_id"]:
        raise ValueError("Annotations belong to a different recording.")
    events = list(document.get("events", []))
    start,end = report["interval"]
    return {name:score(events,captures,start,end,document.get("review_complete",False))
            for name,captures in report["policies"].items()}
