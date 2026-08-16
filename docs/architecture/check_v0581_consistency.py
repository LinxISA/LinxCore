#!/usr/bin/env python3
"""Check LinxCore architecture pages against the LinxISA 0.58.1 authority."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


DEFAULT_LINXCORE_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_ENGINES = {"CUBE": 12, "SFU": 56, "TLSU": 10, "VEC": 31}
EXPECTED_FAMILIES = {"CUBE": 12, "TEPL": 87, "TLSU": 10}
EXPECTED_TEPL_ASSEMBLY = ["BSTART.TEPL", "BSTART.VEC", "BSTART.SFU"]
EXPECTED_TEPL_ALIASES = {"SFU": "BSTART.SFU", "VEC": "BSTART.VEC"}


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--linxcore-root",
        type=Path,
        default=DEFAULT_LINXCORE_ROOT,
        help="LinxCore repository root (defaults to the checker repository)",
    )
    parser.add_argument(
        "--authority-root",
        type=Path,
        help="LinxISA superproject root; otherwise derived from the LinxCore path",
    )
    return parser.parse_args()


def _authority_files(root: Path) -> tuple[Path, Path]:
    return (
        root / "isa" / "v0.58" / "linxisa-v0.58.json",
        root / "docs" / "architecture" / "v0.58-architecture-contract.md",
    )


def _derive_authority_root(linxcore_root: Path) -> Path | None:
    for candidate in (linxcore_root, *linxcore_root.parents):
        if all(path.is_file() for path in _authority_files(candidate)):
            return candidate
    return None


def _load_json(path: Path, errors: list[str]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"cannot load {path}: {exc}")
        return {}
    if not isinstance(value, dict):
        errors.append(f"{path} root must be an object")
        return {}
    return value


def _active_readme_paths(readme: Path, arch: Path) -> set[Path]:
    paths: set[Path] = set()
    active_section = False
    for line in readme.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            active_section = line in {
                "## Contract pages",
                "## Deep dives retained here",
            }
            continue
        if not active_section:
            continue
        for token in re.findall(r"`([^`]+\.md)`", line):
            if token.startswith("rtl/LinxCore/"):
                token = token.removeprefix("rtl/LinxCore/")
            candidate = arch / token
            if token.startswith("docs/architecture/linxcore/"):
                candidate = arch / Path(token).name
            elif token.startswith("docs/architecture/"):
                candidate = arch.parents[1] / token
            paths.add(candidate.resolve())
    return paths


def _active_pages(
    linxcore_root: Path, manifest: dict[str, Any], errors: list[str]
) -> list[Path]:
    arch = linxcore_root / "docs" / "architecture"
    readme = arch / "README.md"
    paths: set[Path] = {readme.resolve()}
    documents = manifest.get("documents")
    if not isinstance(documents, list):
        errors.append("microarchitecture-contract.json documents must be a list")
        documents = []
    for item in documents:
        if not isinstance(item, dict) or item.get("class") != "canonical":
            continue
        rel = item.get("path")
        if isinstance(rel, str):
            paths.add((linxcore_root / rel).resolve())
    try:
        paths.update(_active_readme_paths(readme, arch))
    except OSError as exc:
        errors.append(f"cannot read active-page index {readme}: {exc}")
    active: list[Path] = []
    for path in sorted(paths):
        try:
            path.relative_to(arch.resolve())
        except ValueError:
            errors.append(f"active architecture page escapes {arch}: {path}")
            continue
        if not path.is_file():
            errors.append(f"active architecture page does not exist: {path}")
            continue
        active.append(path)
    return active


def _validate_authority(authority_root: Path, errors: list[str]) -> None:
    isa_path, contract_path = _authority_files(authority_root)
    if not isa_path.is_file() or not contract_path.is_file():
        errors.append(f"invalid LinxISA authority root: {authority_root}")
        return
    isa = _load_json(isa_path, errors)
    try:
        contract = contract_path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot load {contract_path}: {exc}")
        contract = ""

    if isa.get("version") != "0.58.1":
        errors.append("ISA version must be 0.58.1")
    state = isa.get("state") if isinstance(isa.get("state"), dict) else {}
    engine_ops = (
        state.get("engine_ops") if isinstance(state.get("engine_ops"), dict) else {}
    )
    pto_ops = state.get("pto_ops") if isinstance(state.get("pto_ops"), dict) else {}
    if engine_ops.get("semantic_engine_counts") != EXPECTED_ENGINES:
        errors.append(
            "semantic engine counts must be exactly "
            f"{EXPECTED_ENGINES}; got {engine_ops.get('semantic_engine_counts')!r}"
        )
    if pto_ops.get("engine_counts") != EXPECTED_ENGINES:
        errors.append("PTO operation engine counts do not match the 0.58.1 authority")
    if pto_ops.get("family_counts") != EXPECTED_FAMILIES:
        errors.append(
            "tile family counts must be exactly "
            f"{EXPECTED_FAMILIES}; got {pto_ops.get('family_counts')!r}"
        )
    if pto_ops.get("operation_count") != 109:
        errors.append("tile operation count must be exactly 109")
    if engine_ops.get("version") != "0.58.1" or engine_ops.get("profile") != "v0.58":
        errors.append("engine operation state must identify version 0.58.1 profile v0.58")
    if pto_ops.get("version") != "0.58.1" or pto_ops.get("profile") != "v0.58":
        errors.append("PTO operation state must identify version 0.58.1 profile v0.58")

    tepl = engine_ops.get("tepl") if isinstance(engine_ops.get("tepl"), dict) else {}
    tepl_ops = tepl.get("ops") if isinstance(tepl.get("ops"), list) else []
    tepl_engines = Counter(
        item.get("engine")
        for item in tepl_ops
        if isinstance(item, dict) and isinstance(item.get("engine"), str)
    )
    if tepl.get("kind") != "mode_function" or tepl.get("accepted_selector_count") != 87:
        errors.append("TEPL state must be the 87-selector Mode/Function carrier")
    if dict(tepl_engines) != {"VEC": 31, "SFU": 56}:
        errors.append(f"TEPL selectors must route to VEC=31 and SFU=56; got {tepl_engines}")
    tlsu = engine_ops.get("tlsu") if isinstance(engine_ops.get("tlsu"), dict) else {}
    cube = engine_ops.get("cube") if isinstance(engine_ops.get("cube"), dict) else {}
    tlsu_aliases = tlsu.get("legal_aliases")
    cube_aliases = cube.get("legal_aliases")
    if not isinstance(tlsu_aliases, list) or len(tlsu_aliases) != 10:
        errors.append("TLSU family must contain exactly 10 operations")
    if not isinstance(cube_aliases, list) or len(cube_aliases) != 12:
        errors.append("CUBE family must contain exactly 12 operations")

    instructions = isa.get("instructions") if isinstance(isa.get("instructions"), list) else []
    carriers = [
        item
        for item in instructions
        if isinstance(item, dict) and item.get("mnemonic") == "BSTART.TEPL"
    ]
    carrier = carriers[0] if len(carriers) == 1 else {}
    if (
        len(carriers) != 1
        or carrier.get("carrier_mnemonic") != "BSTART.TEPL"
        or carrier.get("accepted_assembly_mnemonics") != EXPECTED_TEPL_ASSEMBLY
        or carrier.get("canonical_assembly_by_engine") != EXPECTED_TEPL_ALIASES
    ):
        errors.append("BSTART.TEPL carrier identity and VEC/SFU aliases must be exact")

    if "The architectural execution engines are exactly:" not in contract:
        errors.append(f"{contract_path} is missing the execution-engine authority")
    if "it is not an execution engine" not in contract:
        errors.append(f"{contract_path} is missing the TEPL carrier-only authority")


def main() -> int:
    args = _arguments()
    linxcore_root = args.linxcore_root.resolve()
    errors: list[str] = []
    authority_root = (
        args.authority_root.resolve()
        if args.authority_root is not None
        else _derive_authority_root(linxcore_root)
    )
    if authority_root is None:
        errors.append(f"cannot derive LinxISA authority root from {linxcore_root}")
    else:
        _validate_authority(authority_root, errors)

    arch = linxcore_root / "docs" / "architecture"
    manifest = _load_json(arch / "microarchitecture-contract.json", errors)
    if manifest.get("architecture_version") != "0.58.1":
        errors.append("microarchitecture-contract.json architecture_version must be 0.58.1")
    stage_families = (
        manifest.get("stage_families")
        if isinstance(manifest.get("stage_families"), list)
        else []
    )
    block_family = next(
        (
            item
            for item in stage_families
            if isinstance(item, dict) and item.get("id") == "block-engine"
        ),
        {},
    )
    raw_block_stages = block_family.get("stages")
    block_stages: set[str] = set()
    if isinstance(raw_block_stages, list):
        block_stages = {
            stage for stage in raw_block_stages if isinstance(stage, str)
        }
    required_block_names = {"TEPL", "TLSU", "CUBE", "VEC", "SFU"}
    if not required_block_names.issubset(block_stages):
        errors.append(
            "block-engine index must retain the compiled TEPL block family and "
            "the TLSU/CUBE/VEC/SFU boundaries"
        )
    if {"TMA", "TAU"} & block_stages:
        errors.append("block-engine index must not restore TMA/TAU architecture names")
    active_paths = _active_pages(linxcore_root, manifest, errors)
    active_text = "\n".join(path.read_text(encoding="utf-8") for path in active_paths)
    normalized_text = " ".join(active_text.split())
    if re.search(r"docs/architecture/v0\.57[^`\s)]*", active_text):
        errors.append("active pages contain a v0.57 architecture link")

    required_statements = {
        "The Tile execution engines are exactly `VEC`, `SFU`, `TLSU`, and `CUBE`.",
        (
            "`TEPL` is the unchanged Mode/Function encoding carrier for `VEC` and "
            "`SFU`; it is not an execution engine."
        ),
        (
            "The compiled block-family domain contains `TEPL`, `TLSU`, and `CUBE`; "
            "VEC/SFU are TEPL semantic engines and assembly aliases."
        ),
    }
    for statement in sorted(required_statements):
        if statement not in normalized_text:
            errors.append(f"active pages are missing release statement: {statement}")

    forbidden_claims = (
        r"`TMA` remains selected",
        r"`TMA` integrates into LinxCore",
        r"`TAU` is the .* engine",
        r"`CUBE` and `TAU` .* engines",
        r"TEPL-to-TAU",
        r"target `TAU`",
    )
    for pattern in forbidden_claims:
        if re.search(pattern, active_text, flags=re.IGNORECASE):
            errors.append(f"active pages retain legacy architectural ownership: {pattern}")

    if errors:
        print("\n".join(f"error: {error}" for error in errors), file=sys.stderr)
        return 1
    print("ok: LinxCore architecture docs match the LinxISA 0.58.1 authority")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
