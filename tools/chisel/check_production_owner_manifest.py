#!/usr/bin/env python3
"""Validate the canonical LinxCore production-owner cutover manifest."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = ROOT / "docs/chisel/production-owner-manifest.md"
MANIFEST_BLOCK = re.compile(
    r"```json\s+production-owner-manifest\s*\n(?P<payload>.*?)\n```",
    re.DOTALL,
)
EMITTER_OBJECT = re.compile(r"\bobject\s+(?P<name>[A-Za-z_]\w*)\s+extends\s+App\b")
REQUIRED_SUBSYSTEMS = {"IFU", "CTU", "OOO", "IEX", "LSU", "DTU"}


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError(f"cannot read manifest {path}: {error}") from error
    match = MANIFEST_BLOCK.search(text)
    if not match:
        raise ValueError("missing fenced JSON production-owner-manifest block")
    try:
        value = json.loads(match.group("payload"))
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid production-owner manifest JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("production-owner manifest root must be an object")
    return value


def path_exists(root: Path, value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, str) or not value:
        errors.append(f"{label} must be a non-empty path")
    elif not (root / value).is_file():
        errors.append(f"missing {label}: {value}")


def path_list(
    root: Path,
    value: Any,
    label: str,
    errors: list[str],
    *,
    allow_empty: bool = False,
) -> None:
    if not isinstance(value, list) or (not value and not allow_empty):
        errors.append(f"{label} must be a {'possibly empty ' if allow_empty else 'non-empty '}list")
        return
    for index, item in enumerate(value):
        path_exists(root, item, f"{label}[{index}]", errors)


def scan_emitters(root: Path) -> list[tuple[str, str]]:
    source_root = root / "chisel/src/main/scala"
    if not source_root.is_dir():
        return []
    emitters: list[tuple[str, str]] = []
    for path in sorted(source_root.rglob("*.scala")):
        text = path.read_text(encoding="utf-8")
        if "emitSystemVerilog" not in text and "emitVerilog" not in text:
            continue
        relative = path.relative_to(root).as_posix()
        emitters.extend((match.group("name"), relative) for match in EMITTER_OBJECT.finditer(text))
    return emitters


def validate(manifest: dict[str, Any], root: Path) -> list[str]:
    errors: list[str] = []
    if manifest.get("schema_version") != 1:
        errors.append("schema_version must be 1")

    ndf = manifest.get("ndf")
    if not isinstance(ndf, dict):
        errors.append("missing NDF mapping")
    else:
        for layer in ("L1", "L2", "L3"):
            if layer not in ndf:
                errors.append(f"missing NDF {layer}")
            else:
                path_list(root, ndf[layer], f"NDF {layer}", errors)
        if "interface_manifest" not in ndf:
            errors.append("missing NDF interface_manifest")
        else:
            path_exists(root, ndf["interface_manifest"], "NDF interface_manifest", errors)

    owners = manifest.get("owners")
    if not isinstance(owners, list) or not owners:
        errors.append("owners must be a non-empty list")
        owners = []
    state_keys = [owner.get("state_key") for owner in owners if isinstance(owner, dict)]
    for state_key, count in Counter(state_keys).items():
        if state_key and count > 1:
            errors.append(f"duplicate owner for state_key {state_key}")
    subsystems = {
        owner.get("subsystem") for owner in owners if isinstance(owner, dict)
    }
    missing_subsystems = sorted(REQUIRED_SUBSYSTEMS - subsystems)
    if missing_subsystems:
        errors.append(f"missing subsystem state categories: {', '.join(missing_subsystems)}")

    for index, owner in enumerate(owners):
        if not isinstance(owner, dict):
            errors.append(f"owner[{index}] must be an object")
            continue
        state_key = owner.get("state_key")
        label = state_key if isinstance(state_key, str) and state_key else f"owner[{index}]"
        if owner.get("subsystem") not in REQUIRED_SUBSYSTEMS:
            errors.append(f"invalid subsystem for {label}")
        if not isinstance(owner.get("canonical_owner"), str) or not owner["canonical_owner"]:
            errors.append(f"missing canonical owner for {label}")
        if owner.get("public_box") != owner.get("subsystem"):
            errors.append(f"public box mismatch for {label}")
        path_list(root, owner.get("mechanism_files"), f"mechanism files for {label}", errors)
        path_exists(root, owner.get("public_box_file"), f"public box file for {label}", errors)
        path_list(root, owner.get("active_callers"), f"active callers for {label}", errors)
        path_list(
            root,
            owner.get("verification_fixtures"),
            f"verification fixtures for {label}",
            errors,
        )
        evidence = owner.get("production_evidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"missing production evidence for {label}")
        else:
            for evidence_index, item in enumerate(evidence):
                if not isinstance(item, dict):
                    errors.append(f"production evidence {label}[{evidence_index}] must be an object")
                    continue
                path_exists(
                    root,
                    item.get("fixture"),
                    f"production evidence fixture for {label}",
                    errors,
                )
                if item.get("level") != "L3":
                    errors.append(f"production evidence for {label} must be L3")
                if not isinstance(item.get("status"), str) or not item["status"]:
                    errors.append(f"production evidence for {label} needs status")
        if not isinstance(owner.get("cutover_task"), int):
            errors.append(f"missing cutover task for {label}")
        adapters = owner.get("adapters")
        if not isinstance(adapters, list):
            errors.append(f"adapters for {label} must be a list")
        else:
            for adapter in adapters:
                if not isinstance(adapter, dict):
                    errors.append(f"adapter for {label} must be an object")
                elif adapter.get("stateful") is not False:
                    errors.append(f"stateful adapter {adapter.get('name', '<unnamed>')}")
        deletion_targets = owner.get("deletion_targets")
        if not isinstance(deletion_targets, list):
            errors.append(f"deletion targets for {label} must be a list")
        else:
            for target in deletion_targets:
                if not isinstance(target, dict):
                    errors.append(f"deletion target for {label} must be an object")
                    continue
                target_path = target.get("path")
                path_exists(root, target_path, f"deletion target for {label}", errors)
                callers = target.get("active_callers")
                if not isinstance(callers, list):
                    errors.append(f"deletion target {target_path} active_callers must be a list")
                elif callers:
                    errors.append(f"deletion target {target_path} has active callers: {', '.join(callers)}")

    entry_points = manifest.get("entry_points")
    if not isinstance(entry_points, dict):
        errors.append("entry_points must be an object")
        entry_points = {}
    production = entry_points.get("production", [])
    non_production = entry_points.get("non_production", [])
    patterns = entry_points.get("non_production_patterns", [])
    if not isinstance(production, list) or not isinstance(non_production, list):
        errors.append("entry point registrations must be lists")
        production, non_production = [], []
    if not isinstance(patterns, list) or not all(isinstance(item, str) for item in patterns):
        errors.append("non-production emitter patterns must be strings")
        patterns = []
    registrations: dict[tuple[str, str], str] = {}
    for classification, entries in (("production", production), ("non-production", non_production)):
        for entry in entries:
            if not isinstance(entry, dict):
                errors.append(f"{classification} entry point must be an object")
                continue
            name, path = entry.get("name"), entry.get("path")
            path_exists(root, path, f"{classification} emitter {name}", errors)
            if isinstance(name, str) and isinstance(path, str):
                registrations[(name, path)] = classification
            if classification == "production" and not entry.get("production_evidence"):
                errors.append(f"missing production evidence for emitter {name}")

    for name, path in scan_emitters(root):
        if (name, path) in registrations:
            continue
        if any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns):
            continue
        errors.append(f"unknown emitter {name} in {path}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = args.manifest.resolve() if args.manifest else root / DEFAULT_MANIFEST.relative_to(ROOT)
    try:
        manifest = load_manifest(manifest_path)
    except ValueError as error:
        print(f"production-owner-manifest: ERROR: {error}", file=sys.stderr)
        return 1
    errors = validate(manifest, root)
    if errors:
        for error in errors:
            print(f"production-owner-manifest: ERROR: {error}", file=sys.stderr)
        return 1
    owners = manifest["owners"]
    emitters = scan_emitters(root)
    print(
        f"production-owner-manifest: {len(owners)} owners, "
        f"{len(emitters)} classified emitters, NDF L1/L2/L3 mapped"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
