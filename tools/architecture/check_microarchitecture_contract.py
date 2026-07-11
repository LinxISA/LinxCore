#!/usr/bin/env python3
"""Validate the LinxCore golden microarchitecture ownership contract."""

from __future__ import annotations

import argparse
import copy
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


MANIFEST_PATH = "docs/architecture/microarchitecture-contract.json"
DOCUMENT_CLASSES = {"canonical", "evidence", "migration-input", "obsolete"}
TOP_SHELL_ROLES = {
    "export",
    "export-wrapper",
    "full-composition",
    "reduced-bringup",
    "full-replacement",
}
PROMOTION_STATES = {
    "absent",
    "stub",
    "unit-proven",
    "integrated",
    "cross-rtl-aligned",
    "workload-proven",
}
LANES = {"pycircuit", "chisel"}
CONTRACT_ID_RE = re.compile(r"^LC-[A-Z0-9]+(?:-[A-Z0-9]+)+$")


def _load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError("manifest root must be an object")
    return value


def _path_exists(root: Path, rel: str) -> bool:
    return bool(rel) and (root / rel).exists()


def _require_list(
    value: Any, field: str, errors: list[str], *, nonempty: bool = False
) -> list[Any]:
    if not isinstance(value, list):
        errors.append(f"{field} must be a list")
        return []
    if nonempty and not value:
        errors.append(f"{field} must not be empty")
    return value


def _require_object(value: Any, field: str, errors: list[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        errors.append(f"{field} must be an object")
        return {}
    return value


def _validate_mechanism_intake(
    root: Path,
    intake_path: str,
    *,
    contract_ids: set[str],
    module_family_ids: set[str],
    scenario_ids: set[str],
    require_no_legacy: bool,
    errors: list[str],
    checked_paths: set[str],
) -> tuple[int, int]:
    if not isinstance(intake_path, str) or not intake_path:
        errors.append("mechanism_intake must be a non-empty path")
        return 0, 0
    checked_paths.add(intake_path)
    path = root / intake_path
    try:
        intake = _load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"cannot load mechanism intake {intake_path}: {exc}")
        return 0, 0
    if intake.get("schema_version") != 1:
        errors.append("mechanism intake schema_version must be 1")

    snapshots = _require_list(
        intake.get("source_snapshots"),
        "mechanism_intake.source_snapshots",
        errors,
        nonempty=True,
    )
    snapshot_ids: set[str] = set()
    snapshot_commits: dict[str, str] = {}
    verify_git = (root / ".git").exists()
    for index, raw in enumerate(snapshots):
        item = _require_object(
            raw, f"mechanism_intake.source_snapshots[{index}]", errors
        )
        snapshot_id = item.get("id")
        commit = item.get("commit")
        if not isinstance(snapshot_id, str) or not snapshot_id:
            errors.append(f"source_snapshots[{index}].id must be non-empty")
        elif snapshot_id in snapshot_ids:
            errors.append(f"duplicate source snapshot id: {snapshot_id}")
        else:
            snapshot_ids.add(snapshot_id)
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            errors.append(f"source snapshot {snapshot_id!r} needs a full Git commit")
        elif isinstance(snapshot_id, str):
            snapshot_commits[snapshot_id] = commit
            if verify_git:
                result = subprocess.run(
                    ["git", "-C", str(root), "cat-file", "-e", f"{commit}^{{commit}}"],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                if result.returncode != 0:
                    errors.append(
                        f"source snapshot {snapshot_id!r} commit is not recoverable: "
                        f"{commit}"
                    )

    mechanisms = _require_list(
        intake.get("mechanisms"),
        "mechanism_intake.mechanisms",
        errors,
        nonempty=True,
    )
    mechanism_ids: set[str] = set()
    mechanism_items: list[dict[str, Any]] = []
    for index, raw in enumerate(mechanisms):
        item = _require_object(raw, f"mechanism_intake.mechanisms[{index}]", errors)
        mechanism_id = item.get("id")
        if not isinstance(mechanism_id, str) or not mechanism_id:
            errors.append(f"mechanisms[{index}].id must be non-empty")
            continue
        if mechanism_id in mechanism_ids:
            errors.append(f"duplicate mechanism id: {mechanism_id}")
            continue
        mechanism_ids.add(mechanism_id)
        mechanism_items.append(item)

    source_files = _require_list(
        intake.get("source_files"),
        "mechanism_intake.source_files",
        errors,
        nonempty=True,
    )
    source_ids: set[str] = set()
    referenced_mechanisms: set[str] = set()
    for index, raw in enumerate(source_files):
        item = _require_object(
            raw, f"mechanism_intake.source_files[{index}]", errors
        )
        source_id = item.get("id")
        source_path = item.get("path")
        if not isinstance(source_id, str) or not source_id:
            errors.append(f"source_files[{index}].id must be non-empty")
        elif source_id in source_ids:
            errors.append(f"duplicate source file id: {source_id}")
        else:
            source_ids.add(source_id)
        if item.get("snapshot") not in snapshot_ids:
            errors.append(
                f"source file {source_id!r} references unknown snapshot: "
                f"{item.get('snapshot')!r}"
            )
        elif (
            verify_git
            and isinstance(source_path, str)
            and isinstance(item.get("snapshot"), str)
        ):
            commit = snapshot_commits.get(item["snapshot"])
            if commit:
                result = subprocess.run(
                    ["git", "-C", str(root), "cat-file", "-e", f"{commit}:{source_path}"],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                if result.returncode != 0:
                    errors.append(
                        f"source file {source_id!r} is not recoverable from "
                        f"{item['snapshot']} at {source_path}"
                    )
        if item.get("status") != "classified":
            errors.append(f"source file {source_id!r} must be classified")
        removed = item.get("removed")
        if not isinstance(removed, bool):
            errors.append(f"source file {source_id!r}.removed must be boolean")
        exists = isinstance(source_path, str) and _path_exists(root, source_path)
        if isinstance(source_path, str):
            checked_paths.add(source_path)
        else:
            errors.append(f"source file {source_id!r}.path must be non-empty")
        if removed is True and exists:
            errors.append(f"classified source marked removed still exists: {source_path}")
        if removed is False and not exists:
            errors.append(f"classified live source does not exist: {source_path}")
        if require_no_legacy and exists:
            errors.append(f"legacy source file remains: {source_path}")
        source_mechanisms = _require_list(
            item.get("mechanisms"),
            f"source file {source_id}.mechanisms",
            errors,
            nonempty=True,
        )
        for mechanism_id in source_mechanisms:
            if mechanism_id not in mechanism_ids:
                errors.append(
                    f"source file {source_id} references unknown mechanism: "
                    f"{mechanism_id}"
                )
            elif isinstance(mechanism_id, str):
                referenced_mechanisms.add(mechanism_id)

    for item in mechanism_items:
        mechanism_id = item["id"]
        disposition = item.get("disposition")
        status = item.get("status")
        if disposition not in {"adopt", "adapt", "parameterize", "reject"}:
            errors.append(
                f"mechanism {mechanism_id} has invalid disposition: {disposition!r}"
            )
        expected_status = "rejected" if disposition == "reject" else "promoted"
        if status != expected_status:
            errors.append(
                f"mechanism {mechanism_id} status must be {expected_status}"
            )
        for field in ("title", "rationale", "linx_binding"):
            if not isinstance(item.get(field), str) or not item.get(field):
                errors.append(f"mechanism {mechanism_id}.{field} must be non-empty")
        targets = _require_list(
            item.get("target_contracts"),
            f"mechanism {mechanism_id}.target_contracts",
            errors,
            nonempty=True,
        )
        for contract_id in targets:
            if contract_id not in contract_ids:
                errors.append(
                    f"mechanism {mechanism_id} references unknown contract: "
                    f"{contract_id}"
                )
        families = _require_list(
            item.get("module_families"),
            f"mechanism {mechanism_id}.module_families",
            errors,
            nonempty=disposition != "reject",
        )
        scenarios = _require_list(
            item.get("scenarios"),
            f"mechanism {mechanism_id}.scenarios",
            errors,
            nonempty=disposition != "reject",
        )
        for family_id in families:
            if family_id not in module_family_ids:
                errors.append(
                    f"mechanism {mechanism_id} references unknown module family: "
                    f"{family_id}"
                )
        for scenario_id in scenarios:
            if scenario_id not in scenario_ids:
                errors.append(
                    f"mechanism {mechanism_id} references unknown scenario: "
                    f"{scenario_id}"
                )
        lane_owners = _require_object(
            item.get("lane_owners"),
            f"mechanism {mechanism_id}.lane_owners",
            errors,
        )
        if set(lane_owners) != LANES:
            errors.append(
                f"mechanism {mechanism_id} must define lane owners "
                f"{sorted(LANES)}"
            )
        for lane in sorted(LANES):
            owners = _require_list(
                lane_owners.get(lane),
                f"mechanism {mechanism_id}.lane_owners.{lane}",
                errors,
                nonempty=disposition != "reject",
            )
            if disposition == "reject" and owners:
                errors.append(
                    f"rejected mechanism {mechanism_id} must not have RTL owners"
                )
            for owner in owners:
                if not isinstance(owner, str) or not _path_exists(root, owner):
                    errors.append(
                        f"mechanism {mechanism_id}.{lane} owner does not exist: "
                        f"{owner!r}"
                    )
                elif isinstance(owner, str):
                    checked_paths.add(owner)

    for mechanism_id in sorted(mechanism_ids - referenced_mechanisms):
        errors.append(f"mechanism has no source-file evidence: {mechanism_id}")
    return len(mechanism_ids), len(source_ids)


def validate_contract(
    root: Path,
    manifest: dict[str, Any],
    *,
    strict: bool = False,
    require_no_legacy: bool = False,
) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    checked_paths: set[str] = set()

    required_root_fields = {
        "schema_version",
        "architecture_version",
        "authority",
        "vocabulary",
        "documents",
        "top_shells",
        "stage_families",
        "module_families",
        "scenarios",
        "contracts",
        "migration_inputs",
        "mechanism_intake",
        "rtl_adapters",
        "extension_categories",
    }
    missing = sorted(required_root_fields - set(manifest))
    for field in missing:
        errors.append(f"manifest missing required field: {field}")
    if strict:
        for field in sorted(set(manifest) - required_root_fields):
            errors.append(f"manifest contains unknown root field: {field}")

    if manifest.get("schema_version") != 1:
        errors.append("schema_version must be 1")

    authority = _require_object(manifest.get("authority"), "authority", errors)
    canonical_root = authority.get("canonical_root")
    if not isinstance(canonical_root, str) or not _path_exists(root, canonical_root):
        errors.append(f"authority canonical_root does not exist: {canonical_root!r}")
    if authority.get("prose_source_of_truth") is not True:
        errors.append("authority prose_source_of_truth must be true")
    if authority.get("machine_index_role") != "ownership-and-verification-index":
        errors.append(
            "authority machine_index_role must be ownership-and-verification-index"
        )

    vocabulary = _require_object(manifest.get("vocabulary"), "vocabulary", errors)
    vocabulary_expectations = {
        "document_classes": DOCUMENT_CLASSES,
        "top_shell_roles": TOP_SHELL_ROLES,
        "promotion_states": PROMOTION_STATES,
        "mechanism_dispositions": {"adopt", "adapt", "parameterize", "reject"},
    }
    for field, expected in vocabulary_expectations.items():
        actual = set(
            item
            for item in _require_list(vocabulary.get(field), f"vocabulary.{field}", errors)
            if isinstance(item, str)
        )
        if actual != expected:
            errors.append(
                f"vocabulary.{field} must equal {sorted(expected)}; got {sorted(actual)}"
            )

    documents = _require_list(manifest.get("documents"), "documents", errors, nonempty=True)
    document_classes: dict[str, str] = {}
    canonical_documents: list[str] = []
    for index, raw in enumerate(documents):
        item = _require_object(raw, f"documents[{index}]", errors)
        path = item.get("path")
        doc_class = item.get("class")
        if not isinstance(path, str) or not path:
            errors.append(f"documents[{index}].path must be a non-empty string")
            continue
        if path in document_classes:
            errors.append(f"duplicate document path: {path}")
            continue
        if doc_class not in DOCUMENT_CLASSES:
            errors.append(f"invalid document class for {path}: {doc_class!r}")
            continue
        document_classes[path] = doc_class
        checked_paths.add(path)
        if not _path_exists(root, path):
            errors.append(f"document path does not exist: {path}")
        if doc_class == "canonical":
            canonical_documents.append(path)
            if isinstance(canonical_root, str) and not (
                path == canonical_root or path.startswith(f"{canonical_root}/")
            ):
                errors.append(f"canonical document lies outside canonical_root: {path}")
        if doc_class == "obsolete" and _path_exists(root, path):
            message = f"obsolete document remains in live tree: {path}"
            if require_no_legacy:
                errors.append(message)
            else:
                warnings.append(message)

    top_shells = _require_list(manifest.get("top_shells"), "top_shells", errors, nonempty=True)
    top_shell_ids: set[str] = set()
    for index, raw in enumerate(top_shells):
        item = _require_object(raw, f"top_shells[{index}]", errors)
        shell_id = item.get("id")
        lane = item.get("lane")
        path = item.get("path")
        role = item.get("role")
        status = item.get("status")
        if not isinstance(shell_id, str) or not shell_id:
            errors.append(f"top_shells[{index}].id must be a non-empty string")
        elif shell_id in top_shell_ids:
            errors.append(f"duplicate top shell id: {shell_id}")
        else:
            top_shell_ids.add(shell_id)
        if lane not in LANES:
            errors.append(f"invalid top shell lane for {shell_id}: {lane!r}")
        if role not in TOP_SHELL_ROLES:
            errors.append(f"invalid top shell role for {shell_id}: {role!r}")
        if status not in PROMOTION_STATES:
            errors.append(f"invalid top shell status for {shell_id}: {status!r}")
        if not isinstance(path, str) or not _path_exists(root, path):
            errors.append(f"top shell path does not exist for {shell_id}: {path!r}")
        elif isinstance(path, str):
            checked_paths.add(path)

    stage_families = _require_list(
        manifest.get("stage_families"), "stage_families", errors, nonempty=True
    )
    stage_family_ids: set[str] = set()
    stage_ids: set[str] = set()
    for index, raw in enumerate(stage_families):
        item = _require_object(raw, f"stage_families[{index}]", errors)
        family_id = item.get("id")
        owner_document = item.get("owner_document")
        if not isinstance(family_id, str) or not family_id:
            errors.append(f"stage_families[{index}].id must be a non-empty string")
        elif family_id in stage_family_ids:
            errors.append(f"duplicate stage family id: {family_id}")
        else:
            stage_family_ids.add(family_id)
        if document_classes.get(owner_document) != "canonical":
            errors.append(
                f"stage family {family_id} owner is not a canonical document: "
                f"{owner_document!r}"
            )
        for stage in _require_list(
            item.get("stages"), f"stage_families[{index}].stages", errors, nonempty=True
        ):
            if not isinstance(stage, str) or not stage:
                errors.append(f"stage family {family_id} contains an invalid stage")
            elif stage in stage_ids:
                errors.append(f"duplicate stage id: {stage}")
            else:
                stage_ids.add(stage)

    module_families = _require_list(
        manifest.get("module_families"), "module_families", errors, nonempty=True
    )
    module_family_ids: set[str] = set()
    for index, raw in enumerate(module_families):
        item = _require_object(raw, f"module_families[{index}]", errors)
        family_id = item.get("id")
        if not isinstance(family_id, str) or not family_id:
            errors.append(f"module_families[{index}].id must be a non-empty string")
            continue
        if family_id in module_family_ids:
            errors.append(f"duplicate module family id: {family_id}")
            continue
        module_family_ids.add(family_id)
        lanes = _require_object(item.get("lanes"), f"module family {family_id}.lanes", errors)
        if set(lanes) != LANES:
            errors.append(
                f"module family {family_id} must define lanes {sorted(LANES)}"
            )
        for lane in sorted(LANES):
            lane_info = _require_object(
                lanes.get(lane), f"module family {family_id}.{lane}", errors
            )
            status = lane_info.get("status")
            owners = _require_list(
                lane_info.get("owners"),
                f"module family {family_id}.{lane}.owners",
                errors,
                nonempty=status != "absent",
            )
            if status not in PROMOTION_STATES:
                errors.append(
                    f"invalid status for module family {family_id}.{lane}: {status!r}"
                )
            for owner in owners:
                if not isinstance(owner, str) or not _path_exists(root, owner):
                    errors.append(
                        f"owner path does not exist for module family "
                        f"{family_id}.{lane}: {owner!r}"
                    )
                elif isinstance(owner, str):
                    checked_paths.add(owner)

    scenarios = _require_list(manifest.get("scenarios"), "scenarios", errors, nonempty=True)
    scenario_ids: set[str] = set()
    scenario_contracts: dict[str, set[str]] = {}
    for index, raw in enumerate(scenarios):
        item = _require_object(raw, f"scenarios[{index}]", errors)
        scenario_id = item.get("id")
        if not isinstance(scenario_id, str) or not scenario_id:
            errors.append(f"scenarios[{index}].id must be a non-empty string")
            continue
        if scenario_id in scenario_ids:
            errors.append(f"duplicate scenario id: {scenario_id}")
            continue
        scenario_ids.add(scenario_id)
        scenario_contracts[scenario_id] = set(
            contract
            for contract in _require_list(
                item.get("contracts"),
                f"scenario {scenario_id}.contracts",
                errors,
                nonempty=True,
            )
            if isinstance(contract, str)
        )

    contracts = _require_list(manifest.get("contracts"), "contracts", errors, nonempty=True)
    contract_ids: set[str] = set()
    contract_items: list[dict[str, Any]] = []
    for index, raw in enumerate(contracts):
        item = _require_object(raw, f"contracts[{index}]", errors)
        contract_id = item.get("id")
        if not isinstance(contract_id, str) or not CONTRACT_ID_RE.fullmatch(contract_id):
            errors.append(f"invalid contract id at contracts[{index}]: {contract_id!r}")
            continue
        if contract_id in contract_ids:
            errors.append(f"duplicate contract id: {contract_id}")
            continue
        contract_ids.add(contract_id)
        contract_items.append(item)

    canonical_heading_lines: dict[str, list[str]] = {}
    for document in canonical_documents:
        path = root / document
        if not path.is_file():
            continue
        canonical_heading_lines[document] = [
            line for line in path.read_text(encoding="utf-8").splitlines() if line.startswith("#")
        ]

    for item in contract_items:
        contract_id = item["id"]
        definition = _require_object(
            item.get("definition"), f"contract {contract_id}.definition", errors
        )
        document = definition.get("document")
        heading = definition.get("heading")
        if document_classes.get(document) != "canonical":
            errors.append(
                f"contract {contract_id} definition is not in a canonical document: "
                f"{document!r}"
            )
        if not isinstance(heading, str) or not heading.startswith("#"):
            errors.append(f"contract {contract_id} definition heading is invalid")
        else:
            matches = sum(
                line == heading
                for lines in canonical_heading_lines.values()
                for line in lines
            )
            if matches != 1:
                errors.append(
                    f"contract {contract_id} definition heading must appear exactly once; "
                    f"found {matches}: {heading}"
                )

        families = _require_list(
            item.get("module_families"),
            f"contract {contract_id}.module_families",
            errors,
            nonempty=True,
        )
        for family_id in families:
            if family_id not in module_family_ids:
                errors.append(
                    f"contract {contract_id} references unknown module family: {family_id}"
                )

        contract_scenarios = _require_list(
            item.get("scenarios"),
            f"contract {contract_id}.scenarios",
            errors,
            nonempty=True,
        )
        for scenario_id in contract_scenarios:
            if scenario_id not in scenario_ids:
                errors.append(
                    f"contract {contract_id} references unknown scenario: {scenario_id}"
                )
            elif contract_id not in scenario_contracts.get(scenario_id, set()):
                errors.append(
                    f"scenario {scenario_id} does not reference contract {contract_id}"
                )

    for scenario_id, references in sorted(scenario_contracts.items()):
        for contract_id in sorted(references):
            if contract_id not in contract_ids:
                errors.append(
                    f"scenario {scenario_id} references unknown contract: {contract_id}"
                )

    migration_inputs = _require_list(
        manifest.get("migration_inputs"), "migration_inputs", errors
    )
    for index, raw in enumerate(migration_inputs):
        item = _require_object(raw, f"migration_inputs[{index}]", errors)
        path = item.get("path")
        if item.get("class") != "migration-input":
            errors.append(f"migration input {path!r} must use class migration-input")
        if item.get("status") not in {"pending", "migrated", "rejected"}:
            errors.append(f"migration input {path!r} has invalid status")
        if not isinstance(item.get("snapshot"), str) or not item.get("snapshot"):
            errors.append(f"migration input {path!r} requires snapshot evidence")
        exists = isinstance(path, str) and _path_exists(root, path)
        if isinstance(path, str):
            checked_paths.add(path)
        if item.get("status") == "pending" and not exists:
            errors.append(f"pending migration input does not exist: {path!r}")
        if require_no_legacy and exists:
            errors.append(f"legacy migration input remains: {path}")

    mechanism_count, source_file_count = _validate_mechanism_intake(
        root,
        manifest.get("mechanism_intake"),
        contract_ids=contract_ids,
        module_family_ids=module_family_ids,
        scenario_ids=scenario_ids,
        require_no_legacy=require_no_legacy,
        errors=errors,
        checked_paths=checked_paths,
    )

    rtl_adapters = _require_object(manifest.get("rtl_adapters"), "rtl_adapters", errors)
    for lane, adapter_path in rtl_adapters.items():
        if lane not in LANES:
            errors.append(f"rtl_adapters references unknown lane: {lane!r}")
            continue
        if not isinstance(adapter_path, str) or not adapter_path:
            errors.append(f"rtl_adapters.{lane} must be a non-empty path")
            continue
        checked_paths.add(adapter_path)
        try:
            adapter = _load_json(root / adapter_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"cannot load {lane} RTL adapter {adapter_path}: {exc}")
            continue
        if adapter.get("lane") != lane:
            errors.append(
                f"RTL adapter {adapter_path} declares lane {adapter.get('lane')!r}, "
                f"expected {lane!r}"
            )

    extension_categories = _require_list(
        manifest.get("extension_categories"),
        "extension_categories",
        errors,
        nonempty=True,
    )
    if len(extension_categories) != len(set(extension_categories)):
        errors.append("extension_categories contains duplicates")
    for required in ("ifu", "cache"):
        if required not in extension_categories:
            errors.append(f"extension_categories missing required category: {required}")

    return {
        "ok": not errors,
        "schema_version": manifest.get("schema_version"),
        "architecture_version": manifest.get("architecture_version"),
        "errors": sorted(set(errors)),
        "warnings": sorted(set(warnings)),
        "summary": {
            "contracts": len(contract_ids),
            "scenarios": len(scenario_ids),
            "module_families": len(module_family_ids),
            "stages": len(stage_ids),
            "top_shells": len(top_shell_ids),
            "migration_inputs": len(migration_inputs),
            "mechanisms": mechanism_count,
            "source_files": source_file_count,
            "rtl_adapters": len(rtl_adapters),
        },
        "checked_paths": sorted(checked_paths),
    }


def _write_fixture(root: Path) -> dict[str, Any]:
    (root / "docs/architecture").mkdir(parents=True)
    (root / "src").mkdir()
    (root / "chisel").mkdir()
    (root / "docs/temp").mkdir(parents=True)
    (root / "docs/architecture/README.md").write_text(
        "# Contract\n\n## Definition (LC-TEST-001)\n", encoding="utf-8"
    )
    (root / "src/owner.py").write_text("# owner\n", encoding="utf-8")
    (root / "chisel/Owner.scala").write_text("// owner\n", encoding="utf-8")
    (root / "docs/temp/input.md").write_text("# input\n", encoding="utf-8")
    fixture = {
        "schema_version": 1,
        "architecture_version": "test",
        "authority": {
            "canonical_root": "docs/architecture",
            "prose_source_of_truth": True,
            "machine_index_role": "ownership-and-verification-index",
        },
        "vocabulary": {
            "document_classes": sorted(DOCUMENT_CLASSES),
            "top_shell_roles": sorted(TOP_SHELL_ROLES),
            "promotion_states": sorted(PROMOTION_STATES),
            "mechanism_dispositions": [
                "adapt",
                "adopt",
                "parameterize",
                "reject",
            ],
        },
        "documents": [
            {"path": "docs/architecture/README.md", "class": "canonical"},
            {"path": "docs/temp/input.md", "class": "migration-input"},
        ],
        "top_shells": [
            {
                "id": "py-top",
                "lane": "pycircuit",
                "path": "src/owner.py",
                "role": "full-composition",
                "status": "integrated",
            },
            {
                "id": "chisel-top",
                "lane": "chisel",
                "path": "chisel/Owner.scala",
                "role": "reduced-bringup",
                "status": "unit-proven",
            },
        ],
        "stage_families": [
            {
                "id": "test",
                "owner_document": "docs/architecture/README.md",
                "stages": ["T0"],
            }
        ],
        "module_families": [
            {
                "id": "test",
                "lanes": {
                    "pycircuit": {
                        "status": "integrated",
                        "owners": ["src/owner.py"],
                    },
                    "chisel": {
                        "status": "unit-proven",
                        "owners": ["chisel/Owner.scala"],
                    },
                },
            }
        ],
        "scenarios": [
            {"id": "scenario-test", "contracts": ["LC-TEST-001"]}
        ],
        "contracts": [
            {
                "id": "LC-TEST-001",
                "definition": {
                    "document": "docs/architecture/README.md",
                    "heading": "## Definition (LC-TEST-001)",
                },
                "module_families": ["test"],
                "scenarios": ["scenario-test"],
            }
        ],
        "migration_inputs": [
            {
                "path": "docs/temp/input.md",
                "class": "migration-input",
                "status": "pending",
                "snapshot": "fixture",
            }
        ],
        "mechanism_intake": "docs/architecture/mechanism-intake.json",
        "rtl_adapters": {},
        "extension_categories": ["cache", "ifu"],
    }
    intake = {
        "schema_version": 1,
        "source_snapshots": [
            {"id": "fixture", "commit": "0" * 40}
        ],
        "source_files": [
            {
                "id": "fixture-source",
                "path": "docs/temp/input.md",
                "snapshot": "fixture",
                "status": "classified",
                "removed": False,
                "mechanisms": ["fixture-mechanism"],
            }
        ],
        "mechanisms": [
            {
                "id": "fixture-mechanism",
                "title": "Fixture mechanism",
                "disposition": "adapt",
                "status": "promoted",
                "rationale": "Exercise mechanism validation.",
                "linx_binding": "Bind to the test contract.",
                "target_contracts": ["LC-TEST-001"],
                "module_families": ["test"],
                "scenarios": ["scenario-test"],
                "lane_owners": {
                    "pycircuit": ["src/owner.py"],
                    "chisel": ["chisel/Owner.scala"],
                },
            }
        ],
    }
    (root / "docs/architecture/mechanism-intake.json").write_text(
        json.dumps(intake), encoding="utf-8"
    )
    return fixture


def run_self_tests() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="linxcore-contract-") as temp_dir:
        root = Path(temp_dir)
        fixture = _write_fixture(root)

        if not validate_contract(root, fixture, strict=True)["ok"]:
            failures.append("valid fixture did not pass")

        duplicate = copy.deepcopy(fixture)
        duplicate["contracts"].append(copy.deepcopy(duplicate["contracts"][0]))
        if validate_contract(root, duplicate)["ok"]:
            failures.append("duplicate contract id was accepted")

        invalid_class = copy.deepcopy(fixture)
        invalid_class["documents"][1]["class"] = "archive"
        if validate_contract(root, invalid_class)["ok"]:
            failures.append("invalid document class was accepted")

        missing_owner = copy.deepcopy(fixture)
        missing_owner["module_families"][0]["lanes"]["chisel"]["owners"] = [
            "chisel/Missing.scala"
        ]
        if validate_contract(root, missing_owner)["ok"]:
            failures.append("missing owner path was accepted")

        uncovered = copy.deepcopy(fixture)
        uncovered["contracts"][0]["scenarios"] = []
        if validate_contract(root, uncovered)["ok"]:
            failures.append("contract without a scenario was accepted")

        bad_role = copy.deepcopy(fixture)
        bad_role["top_shells"][1]["role"] = "full"
        if validate_contract(root, bad_role)["ok"]:
            failures.append("invalid top-shell role was accepted")

        intake_path = root / "docs/architecture/mechanism-intake.json"
        original_intake = _load_json(intake_path)
        unknown_mechanism = copy.deepcopy(original_intake)
        unknown_mechanism["source_files"][0]["mechanisms"] = ["missing"]
        intake_path.write_text(json.dumps(unknown_mechanism), encoding="utf-8")
        if validate_contract(root, fixture)["ok"]:
            failures.append("unknown source mechanism was accepted")

        removed_but_live = copy.deepcopy(original_intake)
        removed_but_live["source_files"][0]["removed"] = True
        intake_path.write_text(json.dumps(removed_but_live), encoding="utf-8")
        if validate_contract(root, fixture)["ok"]:
            failures.append("live source marked removed was accepted")

        intake_path.write_text(json.dumps(original_intake), encoding="utf-8")
        if validate_contract(root, fixture, require_no_legacy=True)["ok"]:
            failures.append("require-no-legacy accepted a live migration input")

    if failures:
        for failure in failures:
            print(f"error: self-test: {failure}", file=sys.stderr)
        return 1
    print("ok: LinxCore microarchitecture contract self-tests passed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Validate the LinxCore golden microarchitecture contract"
    )
    parser.add_argument("--root", default=".", help="LinxCore repository root")
    parser.add_argument(
        "--manifest", default=MANIFEST_PATH, help="manifest path relative to root"
    )
    parser.add_argument("--strict", action="store_true", help="reject unknown fields")
    parser.add_argument(
        "--require-no-legacy",
        action="store_true",
        help="fail while migration or obsolete inputs remain",
    )
    parser.add_argument("--out", default="", help="optional deterministic JSON report")
    parser.add_argument("--self-test", action="store_true", help="run checker fixtures")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_tests()

    root = Path(args.root).resolve()
    manifest_path = root / args.manifest
    try:
        manifest = _load_json(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: cannot load manifest {manifest_path}: {exc}", file=sys.stderr)
        return 1

    report = validate_contract(
        root,
        manifest,
        strict=args.strict,
        require_no_legacy=args.require_no_legacy,
    )
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.out:
        output = Path(args.out)
        if not output.is_absolute():
            output = root / output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")

    for warning in report["warnings"]:
        print(f"warn: {warning}", file=sys.stderr)
    for error in report["errors"]:
        print(f"error: {error}", file=sys.stderr)
    if not report["ok"]:
        return 1
    print(
        "ok: LinxCore microarchitecture contract passed "
        f"({report['summary']['contracts']} contracts, "
        f"{report['summary']['scenarios']} scenarios)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
