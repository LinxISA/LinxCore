#!/usr/bin/env python3
"""Check that current cross-check wrappers bind the one callable TOP."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "tools/chisel/run_top_natural.sh"
EMITTER = ROOT / "chisel/src/main/scala/linxcore/top/EmitTOP.scala"
TOP = ROOT / "chisel/src/main/scala/linxcore/top/TOP.scala"
OLD_TOP = re.compile(r"LinxCore[A-Za-z0-9_]*Top")


def errors() -> list[str]:
    result: list[str] = []
    required = {
        RUNNER: ("linxcore.top.EmitTOP", "TOP", "top_natural_tb.cpp"),
        EMITTER: ("object EmitTOP", "new TOP"),
        TOP: ("class TOP", "new IFU", "new CTU", "new OOO", "new IEX", "new LSU", "new DTU"),
    }
    for path, tokens in required.items():
        if not path.is_file():
            result.append(f"missing canonical file: {path.relative_to(ROOT)}")
            continue
        source = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in source:
                result.append(f"{path.relative_to(ROOT)} lacks {token}")
    for root in (ROOT / "chisel/src/main/scala", ROOT / "tools/chisel"):
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".scala", ".sh", ".cpp", ".py"}:
                continue
            if path == Path(__file__):
                continue
            match = OLD_TOP.search(path.read_text(encoding="utf-8", errors="replace"))
            if match:
                result.append(f"legacy callable top reference {match.group(0)} in {path.relative_to(ROOT)}")
    return result


def main() -> int:
    failures = errors()
    if failures:
        for failure in failures:
            print(f"crosscheck-wrapper: ERROR: {failure}", file=sys.stderr)
        return 1
    print("canonical-emitter=linxcore.top.EmitTOP")
    print("canonical-top=TOP")
    print("canonical-runner=tools/chisel/run_top_natural.sh")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
