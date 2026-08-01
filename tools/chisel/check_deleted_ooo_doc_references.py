#!/usr/bin/env python3
"""Reject live documentation references to deleted O3 wrapper owners."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DOC_ROOT = ROOT / "docs/chisel"
DELETED = ("OooO3RenameCoordinator", "OooO3IexStorePipeline")
HISTORICAL_MARKERS = ("historical", "deleted", "displaced", "removed", "old")


def main() -> int:
    stale: list[str] = []
    for path in sorted(DOC_ROOT.rglob("*.md")):
        if path.name == "agent-loop.md":
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not any(symbol in line for symbol in DELETED):
                continue
            if any(marker in line.lower() for marker in HISTORICAL_MARKERS):
                continue
            stale.append(f"{path.relative_to(ROOT)}:{line_number}: {line.strip()}")
    if stale:
        for item in stale:
            print(f"deleted-ooo-doc-reference: ERROR: {item}")
        return 1
    print("deleted-ooo-doc-reference: no live deleted-wrapper references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
