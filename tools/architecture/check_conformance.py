#!/usr/bin/env python3
"""Validate LinxCore shared scenarios and compare normalized event traces."""

from __future__ import annotations

import argparse
import copy
import json
import sys
import tempfile
from pathlib import Path
from typing import Any


SCENARIOS_PATH = "docs/architecture/conformance/scenarios.json"
REQUIRED_SCENARIOS = {
    "scalar-rename-recovery",
    "local-tu-block-cleanup",
    "rob-brob-engine-completion",
    "no-same-cycle-wake-pick",
    "speculative-load-cancel-replay",
    "lsid-queue-ordering",
    "youngest-byte-forwarding",
    "mdb-conflict-fanout",
    "precise-trap-bi-restore",
    "legal-block-start-redirect",
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"{path} root must be an object")
    return value


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number} must be an object")
            events.append(value)
    return events


def validate_catalog(root: Path, catalog: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    if catalog.get("schema_version") != 1:
        errors.append("scenario catalog schema_version must be 1")
    schema_rel = catalog.get("event_schema")
    try:
        schema = load_json(root / schema_rel)
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"cannot load event schema: {exc}")
        schema = {}
    event_kinds = set(schema.get("event_kinds", []))
    if schema.get("schema_version") != 1 or not event_kinds:
        errors.append("event schema must be version 1 with event kinds")

    manifest = load_json(root / "docs/architecture/microarchitecture-contract.json")
    contract_ids = {item["id"] for item in manifest.get("contracts", [])}
    intake = load_json(root / "docs/architecture/mechanism-intake.json")
    mechanism_ids = {item["id"] for item in intake.get("mechanisms", [])}
    scenario_ids: set[str] = set()
    covered_contracts: set[str] = set()
    covered_mechanisms: set[str] = set()
    scenarios = catalog.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        errors.append("scenario catalog must contain scenarios")
        scenarios = []
    required_fields = {
        "id", "contracts", "mechanisms", "parameters", "initial_state",
        "inputs", "expected_events", "architectural_outcome",
    }
    for index, scenario in enumerate(scenarios):
        if not isinstance(scenario, dict):
            errors.append(f"scenarios[{index}] must be an object")
            continue
        missing = sorted(required_fields - set(scenario))
        if missing:
            errors.append(f"scenario {index} missing fields: {', '.join(missing)}")
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not scenario_id:
            errors.append(f"scenarios[{index}].id must be non-empty")
            continue
        if scenario_id in scenario_ids:
            errors.append(f"duplicate scenario id: {scenario_id}")
        scenario_ids.add(scenario_id)
        for contract_id in scenario.get("contracts", []):
            if contract_id not in contract_ids:
                errors.append(f"scenario {scenario_id} has unknown contract: {contract_id}")
            covered_contracts.add(contract_id)
        for mechanism_id in scenario.get("mechanisms", []):
            if mechanism_id not in mechanism_ids:
                errors.append(f"scenario {scenario_id} has unknown mechanism: {mechanism_id}")
            covered_mechanisms.add(mechanism_id)
        expected = scenario.get("expected_events", [])
        if not expected:
            errors.append(f"scenario {scenario_id} has no expected events")
        for event in expected:
            if event not in event_kinds:
                errors.append(f"scenario {scenario_id} has unknown event: {event}")
        if not isinstance(scenario.get("parameters"), dict) or not scenario["parameters"]:
            errors.append(f"scenario {scenario_id} must declare parameter variation")
        if not isinstance(scenario.get("architectural_outcome"), str) or not scenario["architectural_outcome"]:
            errors.append(f"scenario {scenario_id} needs an architectural outcome")

    for missing_scenario in sorted(REQUIRED_SCENARIOS - scenario_ids):
        errors.append(f"missing required conformance scenario: {missing_scenario}")

    report = {
        "valid": not errors,
        "counts": {
            "scenarios": len(scenario_ids),
            "covered_contracts": len(covered_contracts),
            "covered_mechanisms": len(covered_mechanisms),
            "event_kinds": len(event_kinds),
        },
        "errors": sorted(errors),
    }
    return errors, report


def validate_events(events: list[dict[str, Any]], schema: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    required = set(schema.get("required_fields", []))
    lane_fields = set(schema.get("lane_fields", []))
    identity_fields = set(schema.get("identity_fields", []))
    event_kinds = set(schema.get("event_kinds", []))
    evidence_kinds = set(schema.get("evidence_kinds", []))
    arch = schema.get("architectural_events", {})
    previous: tuple[str, int] | None = None
    for index, event in enumerate(events):
        missing = sorted(required - set(event))
        if missing:
            errors.append(f"event {index} missing fields: {', '.join(missing)}")
        missing_lane = sorted(lane_fields - set(event))
        if missing_lane:
            errors.append(f"event {index} missing lane fields: {', '.join(missing_lane)}")
        if event.get("event") not in event_kinds:
            errors.append(f"event {index} has unknown kind: {event.get('event')!r}")
        if event.get("evidence_kind") not in evidence_kinds:
            errors.append(f"event {index} has invalid evidence_kind")
        if not isinstance(event.get("cycle"), int) or event.get("cycle", -1) < 0:
            errors.append(f"event {index}.cycle must be a non-negative integer")
        if not isinstance(event.get("identity"), dict) or not isinstance(event.get("payload"), dict):
            errors.append(f"event {index} identity and payload must be objects")
        elif identity_fields - set(event["identity"]):
            errors.append(
                f"event {index} identity lacks: "
                f"{', '.join(sorted(identity_fields - set(event['identity'])))}"
            )
        if not isinstance(event.get("owner"), str) or not event.get("owner"):
            errors.append(f"event {index}.owner must be non-empty")
        for field in arch.get(event.get("event"), []):
            if field not in event.get("payload", {}):
                errors.append(f"event {index} {event.get('event')} payload lacks {field}")
        key = (str(event.get("scenario")), int(event.get("cycle", -1)))
        if previous is not None and key < previous:
            errors.append(f"event {index} is not sorted by scenario and cycle")
        previous = key
    return errors


def normalize(events: list[dict[str, Any]], schema: dict[str, Any]) -> list[dict[str, Any]]:
    lane_fields = set(schema.get("lane_fields", []))
    return [{key: value for key, value in event.items() if key not in lane_fields} for event in events]


def compare_traces(
    expected: list[dict[str, Any]],
    pycircuit: list[dict[str, Any]],
    chisel: list[dict[str, Any]],
    schema: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    for name, events in (("expected", expected), ("pycircuit", pycircuit), ("chisel", chisel)):
        errors.extend(f"{name}: {error}" for error in validate_events(events, schema))
    normalized_expected = normalize(expected, schema)
    normalized_pycircuit = normalize(pycircuit, schema)
    normalized_chisel = normalize(chisel, schema)
    if normalized_pycircuit != normalized_expected:
        errors.append("pyCircuit normalized events differ from expected")
    if normalized_chisel != normalized_expected:
        errors.append("Chisel normalized events differ from expected")
    if normalized_pycircuit != normalized_chisel:
        errors.append("pyCircuit normalized events differ from Chisel")
    return errors


def self_test(root: Path, catalog: dict[str, Any]) -> None:
    errors, _ = validate_catalog(root, catalog)
    if errors:
        raise AssertionError("baseline catalog failed: " + "; ".join(errors))
    schema = load_json(root / catalog["event_schema"])
    event = {
        "scenario": "harness-smoke",
        "cycle": 1,
        "event": "commit",
        "owner": "ROB",
        "identity": {"stid": 0, "bid": 1, "rid": 2, "lsid": None},
        "payload": {"pc": 4096, "insn": 19, "length": 4, "trap": False, "bi": 0},
        "lane": "expected",
        "implementation_owner": "fixture",
        "evidence_kind": "harness-fixture"
    }
    pyc = copy.deepcopy(event)
    pyc.update(lane="pycircuit", implementation_owner="fixture-pyc")
    chisel = copy.deepcopy(event)
    chisel.update(lane="chisel", implementation_owner="fixture-chisel")
    if compare_traces([event], [pyc], [chisel], schema):
        raise AssertionError("equivalent traces did not compare equal")
    bad = copy.deepcopy(chisel)
    bad["payload"]["pc"] += 4
    if not compare_traces([event], [pyc], [bad], schema):
        raise AssertionError("differential mismatch was not rejected")
    missing = copy.deepcopy(event)
    missing["payload"].pop("bi")
    if not validate_events([missing], schema):
        raise AssertionError("missing architectural commit field was not rejected")
    duplicate = copy.deepcopy(catalog)
    duplicate["scenarios"].append(copy.deepcopy(duplicate["scenarios"][0]))
    if not validate_catalog(root, duplicate)[0]:
        raise AssertionError("duplicate scenario was not rejected")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--catalog", default=SCENARIOS_PATH)
    parser.add_argument("--expected", type=Path)
    parser.add_argument("--pycircuit", type=Path)
    parser.add_argument("--chisel", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        catalog = load_json(root / args.catalog)
        errors, report = validate_catalog(root, catalog)
        if args.self_test:
            self_test(root, catalog)
        traces = (args.expected, args.pycircuit, args.chisel)
        if any(traces) and not all(traces):
            errors.append("expected, pycircuit, and chisel traces must be supplied together")
        elif all(traces):
            schema = load_json(root / catalog["event_schema"])
            errors.extend(compare_traces(
                load_jsonl(args.expected), load_jsonl(args.pycircuit), load_jsonl(args.chisel), schema
            ))
    except (OSError, ValueError, AssertionError, json.JSONDecodeError) as exc:
        errors = [str(exc)]
        report = {"valid": False, "errors": errors}
    report["valid"] = not errors
    report["errors"] = sorted(set(errors))
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
