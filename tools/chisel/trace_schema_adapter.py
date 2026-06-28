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
    "mem_is_store": ("mem_is_store", "mem_isStore"),
    "trap_cause": ("trap_cause", "cause"),
    "traparg0": ("traparg0", "trap_arg0", "trap_arg"),
    "next_pc": ("next_pc", "nextPc", "npc"),
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
    "block_bid_valid",
    "block_bid",
]

SIDEBAND_ALIASES = {
    "bid": ("bid", "identity.bid"),
    "gid": ("gid", "identity.gid"),
    "rid": ("rid", "identity.rid"),
    "rob_valid": ("rob_valid", "rob.valid"),
    "rob_wrap": ("rob_wrap", "rob.wrap"),
    "rob_value": ("rob_value", "rob.value"),
    "block_bid_valid": ("block_bid_valid", "blockBidValid"),
    "block_bid": ("block_bid", "blockBid"),
}


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
        "mem_is_store": ("mem.is_store", "mem.isStore", "mem.store"),
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


def _slot_valid(obj: dict[str, Any]) -> bool:
    for key in ("valid", "slot_valid", "commit_valid"):
        if key not in obj:
            continue
        value = obj[key]
        if isinstance(value, bool):
            return value
        if isinstance(value, int):
            return value != 0
        if isinstance(value, str):
            lowered = value.strip().lower()
            if lowered in ("0", "false", "no", "n"):
                return False
            if lowered in ("1", "true", "yes", "y"):
                return True
    return True


def normalize_row(obj: dict[str, Any], seq: int) -> dict[str, int]:
    row: dict[str, int] = {}
    for field in REQUIRED_TRACE_FIELDS:
        row[field] = _to_int(_get(obj, field, 0))
    row["seq"] = _to_int(_get(obj, "seq", seq), seq)

    for field in SIDEBAND_FIELDS:
        if field in row:
            continue
        for candidate in SIDEBAND_ALIASES.get(field, (field,)):
            if "." in candidate:
                value = _nested_get(obj, candidate)
                if value is not None:
                    row[field] = _to_int(value)
                    break
            elif candidate in obj:
                row[field] = _to_int(obj[candidate])
                break

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
            if not _slot_valid(obj):
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
            "nextPc": "0x1002",
            "identity": {"bid": 7, "rid": 3},
            "blockBidValid": 1,
            "blockBid": "0x20000007f",
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
    assert row["block_bid"] == 0x20000007F
    assert not _slot_valid({"valid": 0})
    assert not _slot_valid({"valid": "false"})
    assert _slot_valid({"valid": "true"})
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
