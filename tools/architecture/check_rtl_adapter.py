#!/usr/bin/env python3
"""Validate implementation evidence for a LinxCore RTL lane."""

from __future__ import annotations

import argparse
import ast
import copy
import fnmatch
import json
import re
import sys
from pathlib import Path
from typing import Any


PROMOTION_STATES = (
    "absent",
    "stub",
    "unit-proven",
    "integrated",
    "cross-rtl-aligned",
    "workload-proven",
)
PROMOTION_RANK = {state: rank for rank, state in enumerate(PROMOTION_STATES)}
LANES = {"pycircuit", "chisel"}


def _load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError("adapter root must be an object")
    return value


def _list(value: Any, field: str, errors: list[str], *, nonempty: bool = False) -> list[Any]:
    if not isinstance(value, list):
        errors.append(f"{field} must be a list")
        return []
    if nonempty and not value:
        errors.append(f"{field} must not be empty")
    return value


def _object(value: Any, field: str, errors: list[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        errors.append(f"{field} must be an object")
        return {}
    return value


def _source_facts(path: Path) -> tuple[set[str], set[str], dict[str, dict[str, Any]], str]:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".scala":
        without_comments = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
        without_comments = re.sub(r"//.*", "", without_comments)
        symbols = set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", without_comments))
        constants: dict[str, Any] = {}
        for name, raw_value in re.findall(
            r"\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:Int|BigInt)\s*=\s*"
            r"(-?(?:0x[0-9A-Fa-f]+|[0-9]+))",
            without_comments,
        ):
            constants.setdefault(name, int(raw_value, 0))
        classes = {
            class_name: constants
            for class_name in re.findall(
                r"\b(?:case\s+class|class)\s+([A-Za-z_][A-Za-z0-9_]*)",
                without_comments,
            )
        }
        return symbols, set(), classes, text
    if path.suffix != ".py":
        raise ValueError(f"unsupported evidence source type: {path.suffix}")
    tree = ast.parse(text, filename=str(path))
    symbols: set[str] = set()
    modules: set[str] = set()
    class_constants: dict[str, dict[str, Any]] = {}

    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            symbols.add(node.name)
        elif isinstance(node, ast.Name):
            symbols.add(node.id)

        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for decorator in node.decorator_list:
                if not isinstance(decorator, ast.Call):
                    continue
                function = decorator.func
                is_module = isinstance(function, ast.Name) and function.id == "module"
                is_module = is_module or (
                    isinstance(function, ast.Attribute) and function.attr == "module"
                )
                if not is_module:
                    continue
                for keyword in decorator.keywords:
                    if (
                        keyword.arg == "name"
                        and isinstance(keyword.value, ast.Constant)
                        and isinstance(keyword.value.value, str)
                    ):
                        modules.add(keyword.value.value)

    for node in tree.body:
        if not isinstance(node, ast.ClassDef):
            continue
        constants: dict[str, Any] = {}
        for statement in node.body:
            target: ast.expr | None = None
            value: ast.expr | None = None
            if isinstance(statement, ast.AnnAssign):
                target, value = statement.target, statement.value
            elif isinstance(statement, ast.Assign) and len(statement.targets) == 1:
                target, value = statement.targets[0], statement.value
            if isinstance(target, ast.Name) and isinstance(value, ast.Constant):
                constants[target.id] = value.value
        class_constants[node.name] = constants

    return symbols, modules, class_constants, text


def _check_evidence(
    root: Path,
    raw: Any,
    field: str,
    errors: list[str],
    checked_paths: set[str],
) -> None:
    item = _object(raw, field, errors)
    rel = item.get("path")
    if not isinstance(rel, str) or not rel:
        errors.append(f"{field}.path must be non-empty")
        return
    checked_paths.add(rel)
    path = root / rel
    if not path.is_file():
        errors.append(f"missing evidence path: {rel}")
        return
    try:
        symbols, modules, _, text = _source_facts(path)
    except (OSError, SyntaxError, UnicodeError) as exc:
        errors.append(f"cannot inspect evidence {rel}: {exc}")
        return

    for symbol in _list(item.get("symbols", []), f"{field}.symbols", errors):
        if not isinstance(symbol, str) or not symbol:
            errors.append(f"{field}.symbols entries must be non-empty strings")
        elif symbol not in symbols:
            errors.append(f"{rel} lacks required symbol: {symbol}")
    for module_name in _list(item.get("module_names", []), f"{field}.module_names", errors):
        if not isinstance(module_name, str) or not module_name:
            errors.append(f"{field}.module_names entries must be non-empty strings")
        elif module_name not in modules:
            errors.append(f"{rel} lacks @module name: {module_name}")
    for fragment in _list(item.get("text_contains", []), f"{field}.text_contains", errors):
        if not isinstance(fragment, str) or not fragment:
            errors.append(f"{field}.text_contains entries must be non-empty strings")
        elif fragment not in text:
            errors.append(f"{rel} lacks required evidence text: {fragment}")


def validate_adapter(
    root: Path,
    adapter: dict[str, Any],
    *,
    expected_lane: str | None = None,
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    checked_paths: set[str] = set()
    if adapter.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    lane = adapter.get("lane")
    if lane not in LANES:
        errors.append(f"lane must be one of {sorted(LANES)}")
    if expected_lane is not None and lane != expected_lane:
        errors.append(f"adapter lane {lane!r} does not match {expected_lane!r}")

    manifest_rel = adapter.get("architecture_manifest")
    manifest: dict[str, Any] = {}
    if not isinstance(manifest_rel, str) or not manifest_rel:
        errors.append("architecture_manifest must be non-empty")
    else:
        checked_paths.add(manifest_rel)
        try:
            manifest = _load_json(root / manifest_rel)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"cannot load architecture manifest {manifest_rel}: {exc}")

    manifest_shells = {
        item.get("id"): item
        for item in manifest.get("top_shells", [])
        if isinstance(item, dict) and item.get("lane") == lane
    }
    shell_ids: set[str] = set()
    for index, raw in enumerate(_list(adapter.get("top_shells"), "top_shells", errors, nonempty=True)):
        item = _object(raw, f"top_shells[{index}]", errors)
        shell_id = item.get("id")
        if not isinstance(shell_id, str) or not shell_id:
            errors.append(f"top_shells[{index}].id must be non-empty")
            continue
        if shell_id in shell_ids:
            errors.append(f"duplicate top shell id: {shell_id}")
        shell_ids.add(shell_id)
        declared = manifest_shells.get(shell_id)
        if declared is None:
            errors.append(f"adapter top shell is absent from architecture manifest: {shell_id}")
        else:
            for key in ("path", "role"):
                if item.get(key) != declared.get(key):
                    errors.append(f"top shell {shell_id}.{key} disagrees with architecture manifest")
        forbidden_roles = _list(
            item.get("forbidden_roles", []),
            f"top_shells[{index}].forbidden_roles",
            errors,
        )
        if item.get("role") in forbidden_roles:
            errors.append(f"top shell {shell_id} claims forbidden role: {item.get('role')}")
        _check_evidence(root, item, f"top_shells[{index}]", errors, checked_paths)
    missing_shells = sorted(set(manifest_shells) - shell_ids)
    if missing_shells:
        errors.append(f"adapter omits manifest top shells: {', '.join(missing_shells)}")

    parameter_count = 0
    for index, raw in enumerate(_list(adapter.get("parameter_sets"), "parameter_sets", errors, nonempty=True)):
        item = _object(raw, f"parameter_sets[{index}]", errors)
        rel = item.get("path")
        class_name = item.get("class")
        values = _object(item.get("values"), f"parameter_sets[{index}].values", errors)
        if not isinstance(rel, str) or not rel:
            errors.append(f"parameter_sets[{index}].path must be non-empty")
            continue
        checked_paths.add(rel)
        try:
            _, _, classes, text = _source_facts(root / rel)
        except (OSError, SyntaxError, UnicodeError) as exc:
            errors.append(f"cannot inspect parameter source {rel}: {exc}")
            continue
        if not isinstance(class_name, str) or class_name not in classes:
            errors.append(f"{rel} lacks parameter class: {class_name}")
            constants: dict[str, Any] = {}
        else:
            constants = classes[class_name]
        for name, expected in values.items():
            parameter_count += 1
            if constants.get(name) != expected:
                errors.append(
                    f"parameter {class_name}.{name} is {constants.get(name)!r}, expected {expected!r}"
                )
        for fragment in _list(item.get("validation_text"), f"parameter_sets[{index}].validation_text", errors, nonempty=True):
            if not isinstance(fragment, str) or fragment not in text:
                errors.append(f"{rel} lacks parameter validation: {fragment!r}")

    event_kinds: set[str] = set()
    conformance = manifest.get("conformance", {})
    if isinstance(conformance, dict) and isinstance(conformance.get("event_schema"), str):
        try:
            event_schema = _load_json(root / conformance["event_schema"])
            event_kinds = set(event_schema.get("event_kinds", []))
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"cannot load normalized event schema: {exc}")
    source_keys: set[tuple[str, str]] = set()
    mapped_events: set[str] = set()
    for index, raw in enumerate(_list(adapter.get("event_sources"), "event_sources", errors, nonempty=True)):
        item = _object(raw, f"event_sources[{index}]", errors)
        owner = item.get("owner")
        if not isinstance(owner, str) or not owner:
            errors.append(f"event_sources[{index}].owner must be non-empty")
            continue
        events = _list(item.get("events"), f"event_sources[{index}].events", errors, nonempty=True)
        for event in events:
            if event not in event_kinds:
                errors.append(f"event source {owner} references unknown event: {event!r}")
            elif (owner, event) in source_keys:
                errors.append(f"duplicate event source mapping: {owner}/{event}")
            else:
                source_keys.add((owner, event))
                mapped_events.add(event)
        evidence = _list(item.get("evidence"), f"event_sources[{index}].evidence", errors, nonempty=True)
        for evidence_index, evidence_item in enumerate(evidence):
            _check_evidence(
                root,
                evidence_item,
                f"event_sources[{index}].evidence[{evidence_index}]",
                errors,
                checked_paths,
            )

    family_ids = {
        item.get("id")
        for item in manifest.get("module_families", [])
        if isinstance(item, dict)
    }
    contract_ids = {
        item.get("id") for item in manifest.get("contracts", []) if isinstance(item, dict)
    }
    capability_ids: set[str] = set()
    gap_count = 0
    status_counts = {state: 0 for state in PROMOTION_STATES}
    for index, raw in enumerate(_list(adapter.get("capabilities"), "capabilities", errors, nonempty=True)):
        item = _object(raw, f"capabilities[{index}]", errors)
        capability_id = item.get("id")
        status = item.get("status")
        if not isinstance(capability_id, str) or not capability_id:
            errors.append(f"capabilities[{index}].id must be non-empty")
            continue
        if capability_id in capability_ids:
            errors.append(f"duplicate capability id: {capability_id}")
        capability_ids.add(capability_id)
        if status not in PROMOTION_RANK:
            errors.append(f"capability {capability_id} has invalid status: {status!r}")
            continue
        status_counts[status] += 1
        if item.get("module_family") not in family_ids:
            errors.append(f"capability {capability_id} references unknown module family")
        contracts = _list(item.get("contracts"), f"capability {capability_id}.contracts", errors, nonempty=True)
        for contract_id in contracts:
            if contract_id not in contract_ids:
                errors.append(f"capability {capability_id} references unknown contract: {contract_id}")
        evidence = _list(item.get("evidence", []), f"capability {capability_id}.evidence", errors)
        known_gap = item.get("known_gap")
        if status == "absent":
            gap_count += 1
            if not isinstance(known_gap, str) or not known_gap:
                errors.append(f"absent capability {capability_id} needs known_gap")
            checks = _list(item.get("absence_checks"), f"capability {capability_id}.absence_checks", errors, nonempty=True)
            for check_index, check_raw in enumerate(checks):
                check = _object(check_raw, f"capability {capability_id}.absence_checks[{check_index}]", errors)
                rel = check.get("path")
                symbols = check.get("symbols")
                if not isinstance(rel, str) or not rel:
                    errors.append(f"capability {capability_id} absence check needs path")
                    continue
                checked_paths.add(rel)
                try:
                    found, _, _, _ = _source_facts(root / rel)
                except (OSError, SyntaxError, UnicodeError) as exc:
                    errors.append(f"cannot inspect absence source {rel}: {exc}")
                    continue
                for symbol in _list(symbols, f"capability {capability_id}.absence symbols", errors, nonempty=True):
                    if symbol in found:
                        errors.append(f"absent capability {capability_id} unexpectedly found symbol {symbol} in {rel}")
        else:
            if not evidence:
                errors.append(f"capability {capability_id} needs implementation evidence")
            if status == "stub":
                gap_count += 1
                if not isinstance(known_gap, str) or not known_gap:
                    errors.append(f"stub capability {capability_id} needs known_gap")
            for evidence_index, evidence_item in enumerate(evidence):
                _check_evidence(
                    root,
                    evidence_item,
                    f"capability {capability_id}.evidence[{evidence_index}]",
                    errors,
                    checked_paths,
                )
            if PROMOTION_RANK[status] >= PROMOTION_RANK["unit-proven"]:
                verification = _list(
                    item.get("verification"),
                    f"capability {capability_id}.verification",
                    errors,
                    nonempty=True,
                )
                for verify_index, verify_raw in enumerate(verification):
                    verify = _object(
                        verify_raw,
                        f"capability {capability_id}.verification[{verify_index}]",
                        errors,
                    )
                    verify_path = verify.get("path")
                    command = verify.get("command")
                    if not isinstance(verify_path, str) or not (root / verify_path).is_file():
                        errors.append(
                            f"capability {capability_id} verification path is missing: "
                            f"{verify_path!r}"
                        )
                    else:
                        checked_paths.add(verify_path)
                    if not isinstance(command, str) or not command:
                        errors.append(
                            f"capability {capability_id} verification command must be non-empty"
                        )

    forbidden = _object(adapter.get("forbidden_architecture"), "forbidden_architecture", errors)
    roots = _list(forbidden.get("roots"), "forbidden_architecture.roots", errors, nonempty=True)
    globs = _list(forbidden.get("globs"), "forbidden_architecture.globs", errors, nonempty=True)
    terms = _list(forbidden.get("terms"), "forbidden_architecture.terms", errors, nonempty=True)
    for rel_root in roots:
        if not isinstance(rel_root, str) or not (root / rel_root).is_dir():
            errors.append(f"forbidden architecture scan root is missing: {rel_root!r}")
            continue
        checked_paths.add(rel_root)
        for path in sorted((root / rel_root).rglob("*")):
            if not path.is_file() or not any(fnmatch.fnmatch(path.name, pattern) for pattern in globs):
                continue
            text = path.read_text(encoding="utf-8").lower()
            for term in terms:
                if isinstance(term, str) and term.lower() in text:
                    errors.append(f"rejected architecture term {term!r} appears in {path.relative_to(root)}")

    report = {
        "schema_version": 1,
        "lane": lane,
        "valid": not errors,
        "counts": {
            "capabilities": len(capability_ids),
            "checked_paths": len(checked_paths),
            "known_gaps": gap_count,
            "parameters": parameter_count,
            "top_shells": len(shell_ids),
            "event_sources": len(source_keys),
            "mapped_event_kinds": len(mapped_events),
        },
        "promotion_states": status_counts,
        "errors": sorted(errors),
    }
    return errors, report


def _self_test(root: Path, adapter: dict[str, Any]) -> None:
    errors, _ = validate_adapter(root, adapter, expected_lane=adapter.get("lane"))
    if errors:
        raise AssertionError("baseline adapter failed: " + "; ".join(errors))

    cases: list[tuple[str, dict[str, Any]]] = []
    bad_status = copy.deepcopy(adapter)
    bad_status["capabilities"][0]["status"] = "claimed"
    cases.append(("invalid status", bad_status))
    missing_gap = copy.deepcopy(adapter)
    absent = next(item for item in missing_gap["capabilities"] if item["status"] == "absent")
    absent.pop("known_gap", None)
    cases.append(("missing known gap", missing_gap))
    overstated = copy.deepcopy(adapter)
    absent = next(item for item in overstated["capabilities"] if item["status"] == "absent")
    absent["status"] = "integrated"
    absent.pop("evidence", None)
    cases.append(("overstated capability", overstated))
    bad_parameter = copy.deepcopy(adapter)
    first_value = next(iter(bad_parameter["parameter_sets"][0]["values"]))
    bad_parameter["parameter_sets"][0]["values"][first_value] = -1
    cases.append(("parameter drift", bad_parameter))
    bad_shell = copy.deepcopy(adapter)
    bad_shell["top_shells"][0]["role"] = "full-replacement"
    cases.append(("top role drift", bad_shell))

    for name, candidate in cases:
        candidate_errors, _ = validate_adapter(root, candidate, expected_lane=adapter.get("lane"))
        if not candidate_errors:
            raise AssertionError(f"self-test did not reject {name}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--adapter", required=True)
    parser.add_argument("--lane", choices=sorted(LANES))
    parser.add_argument("--out", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    try:
        adapter = _load_json(root / args.adapter)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: cannot load adapter: {exc}", file=sys.stderr)
        return 2
    if args.self_test:
        try:
            _self_test(root, adapter)
        except AssertionError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1

    errors, report = validate_adapter(root, adapter, expected_lane=args.lane)
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
