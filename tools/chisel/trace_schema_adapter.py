#!/usr/bin/env python3
"""Normalize Chisel/pyCircuit/QEMU commit rows to the cross-check JSONL schema."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable


REQUIRED_TRACE_FIELDS = [
    "pc",
    "insn",
    "len",
    "wb_valid",
    "wb_rd",
    "wb_data",
    "src0_valid",
    "src0_reg",
    "src0_data",
    "src1_valid",
    "src1_reg",
    "src1_data",
    "dst_valid",
    "dst_reg",
    "dst_data",
    "mem_valid",
    "mem_is_store",
    "mem_addr",
    "mem_wdata",
    "mem_rdata",
    "mem_size",
    "trap_valid",
    "trap_cause",
    "traparg0",
    "next_pc",
]

ALIASES = {
    "len": ("len", "length", "insn_len", "length_bytes"),
    "wb_rd": ("wb_rd", "rd", "dst_reg", "wb_reg"),
    "wb_data": ("wb_data", "dst_data", "wb_value"),
    "dst_valid": ("dst_valid", "wb_valid"),
    "dst_reg": ("dst_reg", "wb_rd", "rd"),
    "dst_data": ("dst_data", "wb_data", "wb_value"),
    "trap_cause": ("trap_cause", "cause"),
    "traparg0": ("traparg0", "trap_arg0", "trap_arg"),
}

SIDEBAND_FIELDS = [
    "seq",
    "cycle",
    "slot",
    "bid",
    "gid",
    "rid",
    "rob_valid",
    "rob_wrap",
    "rob_value",
]


def _nested_get(obj: dict[str, Any], dotted: str) -> Any:
    cur: Any = obj
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def _get(obj: dict[str, Any], key: str, default: Any = 0) -> Any:
    for candidate in ALIASES.get(key, (key,)):
        if "." in candidate:
            value = _nested_get(obj, candidate)
            if value is not None:
                return value
        elif candidate in obj:
            return obj[candidate]

    nested_aliases = {
        "wb_valid": ("wb.valid",),
        "wb_rd": ("wb.rd", "wb.reg"),
        "wb_data": ("wb.data",),
        "src0_valid": ("src0.valid",),
        "src0_reg": ("src0.reg",),
        "src0_data": ("src0.data",),
        "src1_valid": ("src1.valid",),
        "src1_reg": ("src1.reg",),
        "src1_data": ("src1.data",),
        "dst_valid": ("dst.valid", "wb.valid"),
        "dst_reg": ("dst.reg", "wb.rd", "wb.reg"),
        "dst_data": ("dst.data", "wb.data"),
        "mem_valid": ("mem.valid",),
        "mem_is_store": ("mem.is_store", "mem.store"),
        "mem_addr": ("mem.addr",),
        "mem_wdata": ("mem.wdata",),
        "mem_rdata": ("mem.rdata",),
        "mem_size": ("mem.size",),
        "trap_valid": ("trap.valid",),
        "trap_cause": ("trap.cause",),
        "traparg0": ("trap.arg0", "trap.traparg0"),
    }
    for candidate in nested_aliases.get(key, ()):
        value = _nested_get(obj, candidate)
        if value is not None:
            return value
    return default


def _to_int(value: Any, default: int = 0) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value, 0)
        except ValueError:
            return default
    return default


def normalize_row(obj: dict[str, Any], seq: int) -> dict[str, int]:
    row: dict[str, int] = {}
    for field in REQUIRED_TRACE_FIELDS:
        row[field] = _to_int(_get(obj, field, 0))
    row["seq"] = _to_int(_get(obj, "seq", seq), seq)

    for field in SIDEBAND_FIELDS:
        if field in obj and field not in row:
            row[field] = _to_int(obj[field])

    if row["dst_valid"] == 0 and row["wb_valid"] != 0:
        row["dst_valid"] = row["wb_valid"]
        row["dst_reg"] = row["wb_rd"]
        row["dst_data"] = row["wb_data"]
    return row


def load_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"error: {path}:{line_no}: invalid JSON: {exc}") from exc
            if not isinstance(obj, dict):
                raise SystemExit(f"error: {path}:{line_no}: expected JSON object row")
            if obj.get("type") == "META":
                continue
            yield obj


def normalize_file(input_path: Path, output_path: Path, max_rows: int = 0) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with output_path.open("w", encoding="utf-8") as out:
        for seq, obj in enumerate(load_jsonl(input_path)):
            row = normalize_row(obj, seq)
            out.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
            count += 1
            if max_rows > 0 and count >= max_rows:
                break
    return count


def self_test() -> None:
    row = normalize_row(
        {
            "pc": "0x1000",
            "insn": "0x1234",
            "length": 2,
            "wb": {"valid": True, "rd": "5", "data": "0x44"},
            "mem": {"valid": 1, "store": 0, "addr": "0x2000", "rdata": "0xab", "size": 4},
            "trap": {"valid": 0},
            "next_pc": "0x1002",
            "bid": 7,
            "rid": 3,
        },
        0,
    )
    assert row["pc"] == 0x1000
    assert row["len"] == 2
    assert row["wb_valid"] == 1
    assert row["wb_rd"] == 5
    assert row["dst_valid"] == 1
    assert row["dst_reg"] == 5
    assert row["mem_rdata"] == 0xAB
    assert row["bid"] == 7
    assert row["rid"] == 3
    missing = [field for field in REQUIRED_TRACE_FIELDS if field not in row]
    if missing:
        raise AssertionError(f"missing normalized fields: {missing}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", help="Input JSONL trace")
    ap.add_argument("--output", help="Output normalized JSONL trace")
    ap.add_argument("--max-rows", type=int, default=0)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        print("trace schema adapter self-test: ok")
        return 0

    if not args.input or not args.output:
        raise SystemExit("error: --input and --output are required unless --self-test is set")

    rows = normalize_file(Path(args.input), Path(args.output), args.max_rows)
    print(f"normalized trace rows={rows} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
