#!/usr/bin/env python3
"""ROBID semantic checks derived from LinxCoreModel ROBID.cpp."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys


@dataclass(frozen=True)
class RobId:
    valid: bool = True
    wrap: bool = False
    value: int = 0


def add_robid(rid: RobId, offset: int, size: int) -> RobId:
    assert offset < size
    total = rid.value + offset
    if total < size:
        return RobId(rid.valid, rid.wrap, total)
    return RobId(rid.valid, not rid.wrap, total % size)


def inc_robid(rid: RobId, size: int) -> RobId:
    return add_robid(rid, 1, size)


def sub_robid(rid: RobId, offset: int, size: int) -> RobId:
    assert offset < size
    if rid.value < offset:
        return RobId(rid.valid, not rid.wrap, size - (offset - rid.value))
    return RobId(rid.valid, rid.wrap, rid.value - offset)


def less(lhs: RobId, rhs: RobId) -> bool:
    if lhs.wrap == rhs.wrap:
        return lhs.value < rhs.value
    return lhs.value > rhs.value


def less_equal(lhs: RobId, rhs: RobId) -> bool:
    return less(lhs, rhs) or (lhs.wrap == rhs.wrap and lhs.value == rhs.value)


def cal_gap(newer: RobId, older: RobId, size: int) -> int:
    if newer.wrap == older.wrap:
        return newer.value - older.value
    return (newer.value + size) - older.value


def require(name: str, got: object, expected: object) -> None:
    if got != expected:
        raise AssertionError(f"{name}: got {got!r}, expected {expected!r}")


def check_source(root: Path) -> None:
    robid_scala = root / "chisel/src/main/scala/linxcore/rob/ROBID.scala"
    commit_scala = root / "chisel/src/main/scala/linxcore/commit/CommitIdentity.scala"
    for path in (robid_scala, commit_scala):
        if not path.is_file():
            raise AssertionError(f"missing source: {path}")
    text = robid_scala.read_text(encoding="utf-8")
    for needle in ("def add", "def sub", "def less", "def lessEqual", "def gap"):
        if needle not in text:
            raise AssertionError(f"missing ROBID helper in {robid_scala}: {needle}")


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path(__file__).resolve().parents[2]
    check_source(root)

    size = 8
    require("inc no wrap", inc_robid(RobId(value=6), size), RobId(value=7))
    require("inc wrap", inc_robid(RobId(value=7), size), RobId(wrap=True, value=0))
    require("add wrap", add_robid(RobId(value=6), 3, size), RobId(wrap=True, value=1))
    require("sub wrap", sub_robid(RobId(wrap=True, value=1), 3, size), RobId(value=6))
    require("same-wrap less", less(RobId(value=2), RobId(value=3)), True)
    require("cross-wrap older less", less(RobId(value=7), RobId(wrap=True, value=0)), True)
    require("cross-wrap newer not less", less(RobId(wrap=True, value=0), RobId(value=7)), False)
    require("less-equal equal", less_equal(RobId(value=4), RobId(value=4)), True)
    require("gap same wrap", cal_gap(RobId(value=5), RobId(value=3), size), 2)
    require("gap cross wrap", cal_gap(RobId(wrap=True, value=1), RobId(value=6), size), 3)

    print("ROBID semantic check: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
