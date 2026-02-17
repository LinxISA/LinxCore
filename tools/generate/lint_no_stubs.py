#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"

ALLOWLIST = {
    SRC / "linxcore_top.py",
    SRC / "__init__.py",
    SRC / "top" / "__init__.py",
    SRC / "bcc" / "__init__.py",
    SRC / "bcc" / "backend" / "__init__.py",
    SRC / "bcc" / "backend" / "backend.py",  # thin shell by design
}

TARGET_DIRS = [
    SRC / "bcc",
    SRC / "tmu",
    SRC / "tma",
    SRC / "cube",
    SRC / "vec",
    SRC / "tau",
]

NON_EMPTY = re.compile(r"\S")
COMMENT = re.compile(r"^\s*#")
OUTPUT_RE = re.compile(r"m\.(?:output|out)\(")
STATE_RE = re.compile(r"\.set\(|m\.out\([^\n]*clk=|m\.instance\(")
DATAFLOW_RE = re.compile(r"_select_internal\(|\.select\(|for\s+\w+\s+in\s+range\(")


def iter_files() -> list[Path]:
    files: list[Path] = []
    for td in TARGET_DIRS:
        for p in td.rglob("*.py"):
            if p.name == "__init__.py":
                continue
            if p in ALLOWLIST:
                continue
            files.append(p)
    return sorted(files)


def non_comment_loc(text: str) -> int:
    n = 0
    for line in text.splitlines():
        if not NON_EMPTY.search(line):
            continue
        if COMMENT.search(line):
            continue
        n += 1
    return n


def main() -> int:
    errors: list[str] = []
    for p in iter_files():
        txt = p.read_text(encoding="utf-8")
        loc = non_comment_loc(txt)
        output_cnt = len(re.findall(r"m\.(?:output|out)\(", txt))
        has_state = bool(STATE_RE.search(txt))
        has_dataflow = bool(DATAFLOW_RE.search(txt))

        # Heuristic gates: tiny files with no state/dataflow are stubs.
        if loc < 20:
            errors.append(f"{p}: too small ({loc} LOC), likely stub")
            continue
        if output_cnt > 0 and (not has_state) and (not has_dataflow):
            # Allow dense combinational decode/classifier files.
            if loc >= 40 and output_cnt >= 10:
                continue
            errors.append(f"{p}: outputs without state/dataflow behavior, likely pass-through stub")

    if errors:
        print("no-stub lint failed:", file=sys.stderr)
        for e in errors:
            print(e, file=sys.stderr)
        return 1

    print("no-stub lint passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
