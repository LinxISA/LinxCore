#!/usr/bin/env python3
"""Validate the canonical LinxCore production-owner cutover manifest."""

from __future__ import annotations

import argparse
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
APP_ENTRY = re.compile(r"\bobject\s+(?P<name>[A-Za-z_]\w*)\s+extends\s+App\b")
OBJECT_DECLARATION = re.compile(r"\bobject\s+(?P<name>[A-Za-z_]\w*)\b")
DEF_MAIN = re.compile(r"\bdef\s+main\s*\(")
AT_MAIN_ENTRY = re.compile(r"@main\s+def\s+(?P<name>[A-Za-z_]\w*)\s*\(")
ADAPTER_CLASS = re.compile(
    r"\bclass\s+(?P<name>[A-Za-z_]\w*(?:Adapter|Wrapper))\b"
)
ANY_DEFINITION = re.compile(
    r"(?m)^\s*(?:private(?:\[[^\]]+\])?\s+)?(?:final\s+)?"
    r"(?:class|object)\s+(?P<name>[A-Za-z_]\w*)\b"
)
INSTANTIATION = re.compile(r"\bnew\s+(?P<name>[A-Za-z_]\w*)\b")
PACKAGE_DECLARATION = re.compile(r"(?m)^\s*package\s+(?P<name>[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")
STATE_TOKEN = re.compile(r"\b(?:Reg|RegInit|Mem|SyncReadMem)\s*\(|\bQueue\s*\(")
SYMBOL_DEFINITION = (
    r"(?m)^\s*(?:private(?:\[[^\]]+\])?\s+)?(?:final\s+)?"
    r"(?:class|object)\s+{symbol}\b"
)
REQUIRED_SUBSYSTEMS = {"IFU", "CTU", "OOO", "IEX", "LSU", "DTU"}
EVIDENCE_STATUSES = {
    "public-box-verified",
    "standalone-verified",
    "mechanism-verified-cutover-pending",
    "production-promoted",
}
PUBLIC_INTERFACES = {
    box: f"chisel/src/main/scala/linxcore/top/interface/{box}IO.scala"
    for box in REQUIRED_SUBSYSTEMS
}
PUBLIC_MODULES = {
    "IFU": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
    "CTU": "chisel/src/main/scala/linxcore/ctu/CTU.scala",
    "OOO": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
}

# Closed inventory: the manifest may report these decisions but cannot invent,
# alias, omit, or redirect a state domain to a different primary owner.
STATE_DOMAINS: dict[str, tuple[str, str, str]] = {
    "instruction_cache": (
        "IFU",
        "ISideL1I",
        "chisel/src/main/scala/linxcore/frontend/ISideL1I.scala",
    ),
    "predictor_history": (
        "IFU",
        "BSide",
        "chisel/src/main/scala/linxcore/ifu/BSide.scala",
    ),
    "ifu_recovery_redirect": (
        "IFU",
        "IFURecovery",
        "chisel/src/main/scala/linxcore/ifu/IFURecovery.scala",
    ),
    "instruction_buffer": (
        "CTU",
        "InstructionBuffer",
        "chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala",
    ),
    "rename_p": (
        "OOO",
        "PRename",
        "chisel/src/main/scala/linxcore/ooo/PRename.scala",
    ),
    "rename_tu": (
        "OOO",
        "TURename",
        "chisel/src/main/scala/linxcore/ooo/TURename.scala",
    ),
    "rob": ("OOO", "ROB", "chisel/src/main/scala/linxcore/ooo/ROB.scala"),
    "brob": ("OOO", "BROB", "chisel/src/main/scala/linxcore/ooo/BROB.scala"),
    "commit": (
        "OOO",
        "CommitControl",
        "chisel/src/main/scala/linxcore/ooo/CommitControl.scala",
    ),
    "recovery": (
        "OOO",
        "RecoveryControl",
        "chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala",
    ),
    "dispatch_reservation": (
        "OOO",
        "OooD3ReservationAllocator",
        "chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala",
    ),
    "issue_queue": (
        "IEX",
        "OooIexIssue",
        "chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala",
    ),
    "physical_register_data_readiness": (
        "IEX",
        "OooIexOperandFiles",
        "chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala",
    ),
    "execution_pipeline": (
        "IEX",
        "OooIexExecutionPipeline",
        "chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala",
    ),
    "lsu_pipeline": (
        "LSU",
        "ScalarLSU",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala",
    ),
    "store_queue": (
        "LSU",
        "STQEntryBank",
        "chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala",
    ),
    "store_commit_buffer": (
        "LSU",
        "SCBRowBank",
        "chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala",
    ),
    "load_inflight_queue": (
        "LSU",
        "LoadInflightQueue",
        "chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala",
    ),
    "load_resolve_queue": (
        "LSU",
        "LoadResolveQueue",
        "chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala",
    ),
    "memory_dependency": (
        "LSU",
        "ScalarLSUMDBPath",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala",
    ),
    "cache": (
        "LSU",
        "ScalarL1D",
        "chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala",
    ),
    "lsu_recovery": (
        "LSU",
        "ScalarLSURecoveryBoundary",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSURecoveryBoundary.scala",
    ),
    "trace_debug_performance_observation": (
        "DTU",
        "CommitTraceMonitor",
        "chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala",
    ),
}


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


def contained_path(root: Path, value: Any, label: str, errors: list[str]) -> Path | None:
    if not isinstance(value, str) or not value:
        errors.append(f"{label} must be a non-empty path")
        return None
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        errors.append(f"path escapes repository root: {value}")
        return None
    resolved = (root / candidate).resolve()
    if not resolved.is_relative_to(root):
        errors.append(f"path escapes repository root: {value}")
        return None
    if not resolved.is_file():
        errors.append(f"missing {label}: {value}")
        return None
    return resolved


def path_list(
    root: Path,
    value: Any,
    label: str,
    errors: list[str],
) -> list[str]:
    if not isinstance(value, list) or not value:
        errors.append(f"{label} must be a non-empty list")
        return []
    result: list[str] = []
    for index, item in enumerate(value):
        if contained_path(root, item, f"{label}[{index}]", errors):
            result.append(item)
    return result


def source_defines(path: Path, symbol: str) -> bool:
    pattern = re.compile(SYMBOL_DEFINITION.format(symbol=re.escape(symbol)))
    return bool(pattern.search(path.read_text(encoding="utf-8")))


def source_defines_fqcn(path: Path, symbol: str) -> bool:
    if "." not in symbol:
        return False
    package, simple = symbol.rsplit(".", 1)
    text = path.read_text(encoding="utf-8")
    package_match = PACKAGE_DECLARATION.search(text)
    return (
        package_match is not None
        and package_match.group("name") == package
        and bool(re.search(SYMBOL_DEFINITION.format(symbol=re.escape(simple)), text))
    )


def source_texts(root: Path) -> dict[Path, str]:
    source_root = root / "chisel/src/main/scala"
    if not source_root.is_dir():
        return {}
    return {
        path: path.read_text(encoding="utf-8")
        for path in sorted(source_root.rglob("*.scala"))
    }


def scan_emitters(root: Path, sources: dict[Path, str] | None = None) -> list[tuple[str, str]]:
    sources = sources if sources is not None else source_texts(root)
    emitters: set[tuple[str, str]] = set()
    for path, text in sources.items():
        if "emitSystemVerilog" not in text and "emitVerilog" not in text:
            continue
        relative = path.relative_to(root).as_posix()
        for pattern in (APP_ENTRY, AT_MAIN_ENTRY):
            emitters.update((match.group("name"), relative) for match in pattern.finditer(text))
        object_declarations = list(OBJECT_DECLARATION.finditer(text))
        for main in DEF_MAIN.finditer(text):
            owners = [item for item in object_declarations if item.start() < main.start()]
            if owners:
                emitters.add((owners[-1].group("name"), relative))
    return sorted(emitters)


def checker_emitter_class(name: str) -> str | None:
    if name == "EmitD1DecodeRenameROBIngress":
        return "fixture"
    if name in {"Elaborate", "EmitLinxCoreTopXcheck"}:
        return "legacy-top"
    if "Reduced" in name:
        return "reduced"
    if name.endswith("Probe"):
        return "probe"
    if name.startswith("EmitLinxCore") and "Top" in name:
        return "legacy-top"
    return None


def scan_adapters(
    root: Path,
    sources: dict[Path, str] | None = None,
) -> dict[tuple[str, str], bool]:
    sources = sources if sources is not None else source_texts(root)
    result: dict[tuple[str, str], bool] = {}
    managed_root = root / "chisel/src/main/scala/linxcore"
    for path, text in sources.items():
        if not path.is_relative_to(managed_root):
            continue
        relative = path.relative_to(root).as_posix()
        stateful = bool(STATE_TOKEN.search(text))
        for match in ADAPTER_CLASS.finditer(text):
            result[(match.group("name"), relative)] = stateful
    return result


def discover_callers(
    root: Path,
    symbol: str,
    sources: dict[Path, str] | None = None,
) -> list[str]:
    sources = sources if sources is not None else source_texts(root)
    if "." not in symbol:
        return []
    target_package, simple = symbol.rsplit(".", 1)
    simple_new = re.compile(r"\bnew\s+" + re.escape(simple) + r"\b")
    fq_new = re.compile(r"\bnew\s+" + re.escape(symbol) + r"\b")
    exact_import = re.compile(r"(?m)^\s*import\s+" + re.escape(symbol) + r"\s*$")
    wildcard_import = re.compile(
        r"(?m)^\s*import\s+" + re.escape(target_package) + r"\.(?:_|\*)\s*$"
    )
    braced_import = re.compile(
        r"(?m)^\s*import\s+"
        + re.escape(target_package)
        + r"\.\{[^}]*\b"
        + re.escape(simple)
        + r"\b[^}]*\}"
    )
    callers = []
    for path, text in sources.items():
        package_match = PACKAGE_DECLARATION.search(text)
        same_package = package_match is not None and package_match.group("name") == target_package
        simple_visible = (
            same_package
            or bool(exact_import.search(text))
            or bool(wildcard_import.search(text))
            or bool(braced_import.search(text))
        )
        if fq_new.search(text) or (simple_visible and simple_new.search(text)):
            callers.append(path.relative_to(root).as_posix())
    return callers


def instantiation_graph(sources: dict[Path, str]) -> dict[str, set[str]]:
    graph: dict[str, set[str]] = {}
    for text in sources.values():
        definitions = {match.group("name") for match in ANY_DEFINITION.finditer(text)}
        instantiated = {match.group("name") for match in INSTANTIATION.finditer(text)}
        for definition in definitions:
            graph.setdefault(definition, set()).update(instantiated)
    return graph


def reachable(graph: dict[str, set[str]], start: str, target: str) -> bool:
    pending = [start]
    visited: set[str] = set()
    while pending:
        current = pending.pop()
        if current == target:
            return True
        if current in visited:
            continue
        visited.add(current)
        pending.extend(graph.get(current, set()) - visited)
    return False


def validate_ndf(ndf: Any, root: Path, errors: list[str]) -> None:
    if not isinstance(ndf, dict):
        errors.append("missing NDF mapping")
        return
    layers: dict[str, list[str]] = {}
    for layer in ("L1", "L2", "L3"):
        if layer not in ndf:
            errors.append(f"missing NDF {layer}")
            layers[layer] = []
        else:
            layers[layer] = path_list(root, ndf[layer], f"NDF {layer}", errors)
    for value in layers["L1"]:
        path = Path(value)
        if not value.startswith("docs/spec/") or path.suffix != ".md":
            errors.append(f"NDF L1 path must be Markdown under docs/spec: {value}")
    for value in layers["L2"]:
        is_interface_source = (
            value.startswith("chisel/src/main/scala/linxcore/top/interface/")
            and Path(value).suffix == ".scala"
        )
        is_generated_manifest = value == "docs/chisel/generated/top-interface-manifest.json"
        if not (is_interface_source or is_generated_manifest):
            errors.append(f"NDF L2 path has invalid mechanism role: {value}")
    for value in layers["L3"]:
        is_scala_test = value.startswith("chisel/src/test/scala/") and value.endswith(".scala")
        is_python_test = value.startswith("tests/") and value.endswith(".py")
        if not (is_scala_test or is_python_test):
            errors.append(f"NDF L3 path must be an executable test: {value}")
    seen: dict[str, str] = {}
    for layer, values in layers.items():
        for value in values:
            if value in seen and seen[value] != layer:
                errors.append(f"NDF path reused across layer roles: {value}")
            seen[value] = layer
    interface_manifest = ndf.get("interface_manifest")
    if contained_path(root, interface_manifest, "NDF interface_manifest", errors):
        if interface_manifest not in layers["L2"]:
            errors.append("interface manifest must be an NDF L2 member")
        if interface_manifest != "docs/chisel/generated/top-interface-manifest.json":
            errors.append("interface manifest must use the canonical generated JSON path")


def validate_public_box(owner: dict[str, Any], root: Path, errors: list[str]) -> None:
    state_key = owner["state_key"]
    box = owner.get("public_box")
    status = owner.get("public_box_status")
    interface = owner.get("public_interface_file")
    expected_interface = PUBLIC_INTERFACES.get(box)
    if interface != expected_interface:
        errors.append(f"public interface mismatch for {state_key}")
    else:
        contained_path(root, interface, f"public interface for {state_key}", errors)
    if status == "module":
        box_file = owner.get("public_box_file")
        if box_file != PUBLIC_MODULES.get(box):
            errors.append(f"public box {box} is not a Module definition")
            return
        resolved = contained_path(root, box_file, f"public box for {state_key}", errors)
        if resolved:
            pattern = re.compile(
                rf"(?m)^\s*class\s+{re.escape(box)}\b[^\n]*extends\s+Module\b"
            )
            if not pattern.search(resolved.read_text(encoding="utf-8")):
                errors.append(f"public box {box} is not a Module definition")
    elif status == "pending":
        if box in PUBLIC_MODULES:
            errors.append(f"existing public Module {box} cannot be marked pending")
        if owner.get("public_box_file") not in (None, ""):
            errors.append(f"pending public box {box} must not name an IO Bundle as a box")
    else:
        errors.append(f"invalid public_box_status for {state_key}")


def validate_evidence(
    owner: dict[str, Any],
    entry_points: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    graph: dict[str, set[str]],
    errors: list[str],
) -> None:
    state_key = owner["state_key"]
    fixtures = set(owner.get("verification_fixtures", []))
    evidence = owner.get("production_evidence")
    if not isinstance(evidence, list) or not evidence:
        errors.append(f"missing production evidence for {state_key}")
        return
    for item in evidence:
        if not isinstance(item, dict):
            errors.append(f"production evidence for {state_key} must be an object")
            continue
        fixture = item.get("fixture")
        contained_path(root, fixture, f"production evidence fixture for {state_key}", errors)
        if fixture not in fixtures:
            errors.append(f"evidence fixture for {state_key} is not a verification fixture")
        if item.get("level") != "L3":
            errors.append(f"production evidence for {state_key} must be L3")
        status = item.get("status")
        if status not in EVIDENCE_STATUSES:
            errors.append(f"invalid evidence status for {state_key}: {status}")
        if status == "public-box-verified" and owner.get("public_box_status") != "module":
            errors.append(f"public-box evidence for {state_key} lacks a public Module")
        if status == "public-box-verified" and not reachable(
            graph,
            owner["public_box"],
            owner["canonical_owner"],
        ):
            errors.append(
                f"public-box evidence for {state_key} lacks box-to-owner reachability"
            )
        if status == "production-promoted":
            production = entry_points.get("production", [])
            kinds = set(item.get("proof_kinds", []))
            reached = False
            for entry in production:
                if not isinstance(entry, dict):
                    continue
                emitter_path = entry.get("path")
                root_symbol = entry.get("root_symbol")
                source_path = root / emitter_path if isinstance(emitter_path, str) else None
                emitter_instantiates_root = (
                    isinstance(root_symbol, str)
                    and source_path in sources
                    and re.search(
                        r"\bnew\s+" + re.escape(root_symbol) + r"\b",
                        sources[source_path],
                    )
                )
                if (
                    emitter_instantiates_root
                    and owner.get("public_box") in entry.get("boxes", [])
                    and reachable(graph, root_symbol, owner["public_box"])
                    and reachable(graph, owner["public_box"], owner["canonical_owner"])
                ):
                    reached = True
                    break
            if owner.get("public_box_status") != "module" or not reached or not {
                "generated-rtl-activation",
                "bounded-workload",
            }.issubset(kinds):
                errors.append(
                    f"production-promoted evidence for {state_key} lacks "
                    "active emitter reachability and activation proof"
                )


def validate_deletion_targets(
    owner: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    errors: list[str],
) -> None:
    state_key = owner["state_key"]
    targets = owner.get("deletion_targets")
    if not isinstance(targets, list):
        errors.append(f"deletion targets for {state_key} must be a list")
        return
    for target in targets:
        if not isinstance(target, dict):
            errors.append(f"deletion target for {state_key} must be an object")
            continue
        path = target.get("path")
        symbol = target.get("symbol")
        resolved = contained_path(root, path, f"deletion target for {state_key}", errors)
        if not isinstance(symbol, str) or "." not in symbol:
            errors.append(f"deletion target for {state_key} needs an exact FQCN symbol")
            continue
        if resolved and not source_defines_fqcn(resolved, symbol):
            errors.append(f"deletion target {path} does not define {symbol}")
        declared = target.get("active_callers")
        if not isinstance(declared, list) or not all(isinstance(item, str) for item in declared):
            errors.append(f"deletion target {symbol} active_callers must be a list")
            continue
        for caller in declared:
            contained_path(root, caller, f"declared caller for {symbol}", errors)
        discovered = discover_callers(root, symbol, sources)
        if sorted(declared) != discovered:
            errors.append(
                f"deletion target {symbol} caller declaration mismatch: "
                f"declared={sorted(declared)} discovered={discovered}"
            )
        status = target.get("status")
        if status == "planned-active":
            if not discovered:
                errors.append(f"planned-active deletion target {symbol} has no discovered callers")
        elif status == "deletion-ready":
            if discovered:
                errors.append(f"deletion-ready target {symbol} still has discovered callers")
        else:
            errors.append(f"invalid deletion status for {symbol}")


def validate_adapters(
    manifest: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    errors: list[str],
) -> None:
    discovered = scan_adapters(root, sources)
    declared_items = manifest.get("adapters")
    if not isinstance(declared_items, list):
        errors.append("adapters must be a root-level list")
        declared_items = []
    declared: dict[tuple[str, str], dict[str, Any]] = {}
    for item in declared_items:
        if not isinstance(item, dict):
            errors.append("adapter declaration must be an object")
            continue
        key = (item.get("symbol"), item.get("path"))
        if not all(isinstance(value, str) and value for value in key):
            errors.append("adapter declaration needs exact symbol and path")
            continue
        declared[key] = item
        resolved = contained_path(root, key[1], f"adapter {key[0]}", errors)
        if resolved and not source_defines(resolved, key[0]):
            errors.append(f"adapter path {key[1]} does not define {key[0]}")
    for key, stateful in discovered.items():
        item = declared.get(key)
        if item is None:
            errors.append(f"undeclared adapter {key[0]} in {key[1]}")
            continue
        if item.get("stateful") is not stateful:
            errors.append(f"adapter {key[0]} stateful declaration mismatch")
        role = item.get("role")
        if role == "compatibility":
            if stateful:
                errors.append(f"stateful adapter {key[0]}")
        elif role == "legacy-state-owner":
            if item.get("status") != "planned-deletion":
                errors.append(f"legacy adapter {key[0]} must be planned for deletion")
            if item.get("owner_domain") not in STATE_DOMAINS:
                errors.append(f"legacy adapter {key[0]} needs a closed owner domain")
            if not isinstance(item.get("cutover_task"), int):
                errors.append(f"legacy adapter {key[0]} needs a cutover task")
        else:
            errors.append(f"invalid adapter role for {key[0]}")
    for key in sorted(set(declared) - set(discovered)):
        errors.append(f"declared adapter not discovered: {key[0]} in {key[1]}")


def validate_entry_points(
    manifest: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    errors: list[str],
) -> dict[str, Any]:
    entry_points = manifest.get("entry_points")
    if not isinstance(entry_points, dict):
        errors.append("entry_points must be an object")
        return {}
    if "non_production_patterns" in entry_points:
        errors.append("manifest-controlled emitter patterns are forbidden")
    production = entry_points.get("production", [])
    non_production = entry_points.get("non_production", [])
    if not isinstance(production, list) or not isinstance(non_production, list):
        errors.append("entry point registrations must be lists")
        return entry_points
    registrations: dict[tuple[str, str], str] = {}
    for classification, entries in (("production", production), ("non-production", non_production)):
        for entry in entries:
            if not isinstance(entry, dict):
                errors.append(f"{classification} entry point must be an object")
                continue
            name, path = entry.get("name"), entry.get("path")
            contained_path(root, path, f"{classification} emitter {name}", errors)
            if isinstance(name, str) and isinstance(path, str):
                registrations[(name, path)] = classification
            if classification == "production" and not entry.get("production_evidence"):
                errors.append(f"missing production evidence for emitter {name}")
    discovered_emitters = set(scan_emitters(root, sources))
    for name, path in discovered_emitters:
        registration = registrations.get((name, path))
        fixed_class = checker_emitter_class(name)
        if registration == "production":
            continue
        if registration == "non-production" or fixed_class is not None:
            continue
        errors.append(f"unknown emitter {name} in {path}")
    for name, path in sorted(set(registrations) - discovered_emitters):
        errors.append(f"registered emitter not discovered: {name} in {path}")
    return entry_points


def validate(manifest: dict[str, Any], root: Path) -> list[str]:
    errors: list[str] = []
    sources = source_texts(root)
    graph = instantiation_graph(sources)
    if manifest.get("schema_version") != 2:
        errors.append("schema_version must be 2")
    validate_ndf(manifest.get("ndf"), root, errors)
    entry_points = validate_entry_points(manifest, root, sources, errors)

    owners = manifest.get("owners")
    if not isinstance(owners, list) or not owners:
        errors.append("owners must be a non-empty list")
        owners = []
    state_keys = [owner.get("state_key") for owner in owners if isinstance(owner, dict)]
    for state_key, count in Counter(state_keys).items():
        if state_key and count > 1:
            errors.append(f"duplicate owner for state domain {state_key}")
    for state_key in sorted(set(STATE_DOMAINS) - set(state_keys)):
        errors.append(f"missing state domain {state_key}")
    for state_key in sorted(set(state_keys) - set(STATE_DOMAINS)):
        errors.append(f"unknown state domain {state_key}")

    for index, owner in enumerate(owners):
        if not isinstance(owner, dict):
            errors.append(f"owner[{index}] must be an object")
            continue
        state_key = owner.get("state_key")
        if state_key not in STATE_DOMAINS:
            continue
        subsystem, canonical_symbol, primary_file = STATE_DOMAINS[state_key]
        if owner.get("subsystem") != subsystem:
            errors.append(f"subsystem mismatch for {state_key}")
        if owner.get("canonical_owner") != canonical_symbol:
            errors.append(f"canonical owner mismatch for {state_key}")
        mechanisms = path_list(
            root,
            owner.get("mechanism_files"),
            f"mechanism files for {state_key}",
            errors,
        )
        if primary_file not in mechanisms:
            errors.append(f"primary mechanism mismatch for {state_key}")
        definition_count = 0
        for mechanism in mechanisms:
            resolved = contained_path(root, mechanism, f"mechanism for {state_key}", errors)
            if resolved and source_defines(resolved, canonical_symbol):
                definition_count += 1
        if definition_count != 1:
            errors.append(
                f"canonical symbol {canonical_symbol} must be defined exactly once "
                f"across {state_key} mechanisms"
            )
        if owner.get("public_box") != subsystem:
            errors.append(f"public box mismatch for {state_key}")
        validate_public_box(owner, root, errors)
        path_list(root, owner.get("active_callers"), f"active callers for {state_key}", errors)
        path_list(
            root,
            owner.get("verification_fixtures"),
            f"verification fixtures for {state_key}",
            errors,
        )
        validate_evidence(owner, entry_points, root, sources, graph, errors)
        if not isinstance(owner.get("cutover_task"), int):
            errors.append(f"missing cutover task for {state_key}")
        if owner.get("adapters") not in (None, []):
            errors.append(f"owner-local adapters must be empty for {state_key}")
        validate_deletion_targets(owner, root, sources, errors)

    subsystems = {owner.get("subsystem") for owner in owners if isinstance(owner, dict)}
    missing_subsystems = sorted(REQUIRED_SUBSYSTEMS - subsystems)
    if missing_subsystems:
        errors.append(f"missing subsystem state categories: {', '.join(missing_subsystems)}")
    validate_adapters(manifest, root, sources, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = (
        args.manifest.resolve()
        if args.manifest
        else root / DEFAULT_MANIFEST.relative_to(ROOT)
    )
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
    adapters = scan_adapters(root)
    print(
        f"production-owner-manifest: {len(owners)} closed owners, "
        f"{len(emitters)} classified emitters, {len(adapters)} declared adapters, "
        "NDF L1/L2/L3 roles mapped"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
