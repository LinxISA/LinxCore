#!/usr/bin/env python3
"""Check the LinxCore architecture pages against the LinxISA 0.58.1 taxonomy."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ARCH = ROOT / "docs" / "architecture"
MANIFEST = ARCH / "microarchitecture-contract.json"
EXPECTED_ENGINES = {"VEC", "SFU", "TLSU", "CUBE"}
LEGACY_ENGINE_NAMES = {"TMA", "TAU", "TEPL"}


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--authority-root",
        type=Path,
        help="LinxISA superproject root containing isa/v0.58 and docs/architecture",
    )
    return parser.parse_args()


def main() -> None:
    args = _arguments()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    errors: list[str] = []

    if args.authority_root is not None:
        authority_root = args.authority_root.resolve()
        isa_path = authority_root / "isa" / "v0.58" / "linxisa-v0.58.json"
        contract_path = (
            authority_root / "docs" / "architecture" / "v0.58-architecture-contract.md"
        )
        authority_isa = json.loads(isa_path.read_text(encoding="utf-8"))
        authority_contract = contract_path.read_text(encoding="utf-8")
        if authority_isa.get("version") != "0.58.1":
            errors.append(f"{isa_path} version must be 0.58.1")
        if "The architectural execution engines are exactly:" not in authority_contract:
            errors.append(f"{contract_path} is missing the execution-engine authority")
        if "it is not an execution engine" not in authority_contract:
            errors.append(f"{contract_path} is missing the TEPL carrier-only authority")

    if manifest.get("architecture_version") != "0.58.1":
        errors.append("microarchitecture-contract.json architecture_version must be 0.58.1")

    block_family = next(
        (item for item in manifest["stage_families"] if item.get("id") == "block-engine"),
        None,
    )
    if block_family is None:
        errors.append("microarchitecture-contract.json is missing block-engine stage family")
    else:
        stages = set(block_family.get("stages", []))
        engine_names = stages & (EXPECTED_ENGINES | LEGACY_ENGINE_NAMES)
        if engine_names != EXPECTED_ENGINES:
            errors.append(
                "block-engine architectural engines must be exactly "
                f"{sorted(EXPECTED_ENGINES)}; got {sorted(engine_names)}"
            )

    canonical_paths = [
        ROOT / item["path"]
        for item in manifest["documents"]
        if item.get("class") == "canonical" and item["path"].endswith(".md")
    ]
    active_text = "\n".join(path.read_text(encoding="utf-8") for path in canonical_paths)
    normalized_text = " ".join(active_text.split())
    if re.search(r"docs/architecture/v0\.57[^`\s)]*", active_text):
        errors.append("canonical pages contain an active v0.57 architecture link")

    required_statements = {
        "The Tile execution engines are exactly `VEC`, `SFU`, `TLSU`, and `CUBE`.",
        "`TEPL` is the unchanged Mode/Function encoding carrier for `VEC` and `SFU`; it is not an execution engine.",
    }
    for statement in sorted(required_statements):
        if statement not in normalized_text:
            errors.append(f"canonical pages are missing release statement: {statement}")

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
            errors.append(f"canonical pages retain legacy architectural ownership: {pattern}")

    if errors:
        raise SystemExit("\n".join(f"error: {error}" for error in errors))
    print("ok: LinxCore architecture docs match the LinxISA 0.58.1 engine taxonomy")


if __name__ == "__main__":
    main()
