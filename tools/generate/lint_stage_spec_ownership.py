#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"
DOC = ROOT / "docs" / "architecture" / "pipeline-stage-catalog.md"

if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from common.stage_tokens import LINXTRACE_STAGE_ID_ORDER  # noqa: E402


STAGE_RE = re.compile(r"^###\s+([A-Z0-9]+)\s*$")
CANONICAL_STAGE_RE = re.compile(r"^###\s+([A-Z][A-Z0-9]*)(?:\s|/|—|$)")
OWNER_PATH_RE = re.compile(r"`(src/[^`\s]+\.py)`")

CANONICAL_STAGE_COORDINATES = (
    "F0", "F1", "F2", "F3", "F4",
    "D1", "D2", "D3",
    "S1", "S2", "S3",
    "P0", "P1", "I1", "I2",
    "E1", "E2", "E3", "E4", "E5", "E6",
    "W1", "W2", "W3",
    "ROB", "R0", "R1", "R2", "R3", "R4", "CMT", "FLS",
)


def _parse_stage_owner_paths(text: str) -> dict[str, list[str]]:
    stage = ""
    owner_block = False
    owners: dict[str, list[str]] = {}
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("## "):
            stage = ""
            owner_block = False
            continue
        if line.startswith("### "):
            match = STAGE_RE.match(line)
            stage = match.group(1).strip() if match else ""
            owner_block = False
            if stage:
                owners.setdefault(stage, [])
            continue
        if not stage:
            continue
        if re.match(r"^-\s+.*\bowners?\b", line, re.IGNORECASE):
            owner_block = True
        elif owner_block and not (line.startswith(" ") and OWNER_PATH_RE.search(line)):
            owner_block = False
        if owner_block:
            for match in OWNER_PATH_RE.finditer(line):
                owners[stage].append(match.group(1))
    return owners


def _parse_canonical_owner_paths(text: str) -> dict[str, list[str]]:
    required = set(CANONICAL_STAGE_COORDINATES)
    stage = ""
    owner_block = False
    owners: dict[str, list[str]] = {}
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("## "):
            stage = ""
            owner_block = False
            continue
        if line.startswith("### "):
            match = CANONICAL_STAGE_RE.match(line)
            stage = match.group(1) if match and match.group(1) in required else ""
            owner_block = False
            if stage:
                owners.setdefault(stage, [])
            continue
        if not stage:
            continue
        if re.match(r"^-\s+.*\bowners?\b", line, re.IGNORECASE):
            owner_block = True
        elif owner_block and not (line.startswith(" ") and OWNER_PATH_RE.search(line)):
            owner_block = False
        if owner_block:
            for match in OWNER_PATH_RE.finditer(line):
                owners[stage].append(match.group(1))
    return owners


def main() -> int:
    if not DOC.is_file():
        raise SystemExit(f"missing pipeline stage catalog: {DOC}")

    owners = _parse_stage_owner_paths(DOC.read_text(encoding="utf-8"))
    doc_text = DOC.read_text(encoding="utf-8")
    canonical_owners = _parse_canonical_owner_paths(doc_text)
    canonical_headings = {
        match.group(1) for line in doc_text.splitlines()
        if (match := CANONICAL_STAGE_RE.match(line.rstrip()))
    }
    errors: list[str] = []

    expected = list(LINXTRACE_STAGE_ID_ORDER)
    for stage in expected:
        stage_owners = owners.get(stage)
        if not stage_owners:
            errors.append(f"{DOC}: missing stage section or owner paths for {stage}")
            continue
        for rel in stage_owners:
            path = ROOT / rel
            if not path.is_file():
                errors.append(f"{DOC}: missing owner file for {stage}: {rel}")
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if "@module" not in text:
                errors.append(f"{DOC}: owner file for {stage} has no @module boundary: {rel}")

    extra = sorted(set(owners) - set(expected))
    if extra:
        errors.append(f"{DOC}: undocumented stage ids not present in stage token catalog: {', '.join(extra)}")

    missing_canonical = [
        stage for stage in CANONICAL_STAGE_COORDINATES
        if stage not in canonical_headings
    ]
    if missing_canonical:
        errors.append(
            f"{DOC}: missing canonical stage headings: "
            + ", ".join(missing_canonical)
        )

    for stage in CANONICAL_STAGE_COORDINATES:
        stage_owners = canonical_owners.get(stage, [])
        if not stage_owners:
            errors.append(f"{DOC}: missing canonical owner path for {stage}")
            continue
        for rel in stage_owners:
            path = ROOT / rel
            if not path.is_file():
                errors.append(f"{DOC}: missing canonical owner file for {stage}: {rel}")
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if "@module" not in text:
                errors.append(
                    f"{DOC}: canonical owner file for {stage} has no @module boundary: {rel}"
                )

    if "`F4` and `IB` are two names for the same final fetch stage." not in doc_text:
        errors.append(f"{DOC}: missing canonical F4/IB alias statement")

    if errors:
        print("stage spec ownership lint failed:", file=sys.stderr)
        for err in errors:
            print(err, file=sys.stderr)
        return 1

    print(
        "stage spec ownership lint passed "
        f"({len(expected)} trace stages, "
        f"{len(CANONICAL_STAGE_COORDINATES)} canonical coordinates)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
