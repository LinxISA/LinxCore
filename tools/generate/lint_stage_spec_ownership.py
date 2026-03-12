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
OWNER_PATH_RE = re.compile(r"`(src/[^`\s]+\.py)`")


def _parse_stage_owner_paths(text: str) -> dict[str, list[str]]:
    stage = ""
    owners: dict[str, list[str]] = {}
    for raw in text.splitlines():
        line = raw.rstrip()
        m = STAGE_RE.match(line)
        if m:
            stage = m.group(1).strip()
            owners.setdefault(stage, [])
            continue
        if not stage:
            continue
        for match in OWNER_PATH_RE.finditer(line):
            owners[stage].append(match.group(1))
    return owners


def main() -> int:
    if not DOC.is_file():
        raise SystemExit(f"missing pipeline stage catalog: {DOC}")

    owners = _parse_stage_owner_paths(DOC.read_text(encoding="utf-8"))
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

    if errors:
        print("stage spec ownership lint failed:", file=sys.stderr)
        for err in errors:
            print(err, file=sys.stderr)
        return 1

    print(f"stage spec ownership lint passed ({len(expected)} stages)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
