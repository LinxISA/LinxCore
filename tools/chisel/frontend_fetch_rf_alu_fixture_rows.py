#!/usr/bin/env python3
"""Emit QEMU-shaped expected rows for the frontend fetch RF/ALU xcheck."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def _base_row(pc: int, insn: int, length: int) -> dict[str, Any]:
    return {
        "pc": pc,
        "insn": insn,
        "len": length,
        "wb_valid": 0,
        "wb_rd": 0,
        "wb_data": 0,
        "src0_valid": 0,
        "src0_reg": 0,
        "src0_data": 0,
        "src1_valid": 0,
        "src1_reg": 0,
        "src1_data": 0,
        "dst_valid": 0,
        "dst_reg": 0,
        "dst_data": 0,
        "mem_valid": 0,
        "mem_is_store": 0,
        "mem_addr": 0,
        "mem_wdata": 0,
        "mem_rdata": 0,
        "mem_size": 0,
        "trap_valid": 0,
        "trap_cause": 0,
        "traparg0": 0,
        "next_pc": pc + length,
    }


def fixture_rows() -> list[dict[str, Any]]:
    add = 0x00000005 | (3 << 7) | (4 << 15) | (5 << 20)
    addi = 0x00000015 | (6 << 7) | (3 << 15) | (0x7FF << 20)
    c_movr = 0x0006 | (6 << 6) | (5 << 11)

    r0 = _base_row(0x1000, add, 4)
    r0.update(
        {
            "wb_valid": 1,
            "wb_rd": 3,
            "wb_data": 42,
            "src0_valid": 1,
            "src0_reg": 4,
            "src0_data": 10,
            "src1_valid": 1,
            "src1_reg": 5,
            "src1_data": 32,
            "dst_valid": 1,
            "dst_reg": 3,
            "dst_data": 42,
        }
    )

    r1 = _base_row(0x1004, addi, 4)
    r1.update(
        {
            "wb_valid": 1,
            "wb_rd": 6,
            "wb_data": 2089,
            "src0_valid": 1,
            "src0_reg": 3,
            "src0_data": 42,
            "dst_valid": 1,
            "dst_reg": 6,
            "dst_data": 2089,
        }
    )

    r2 = _base_row(0x1008, c_movr, 2)
    r2.update(
        {
            "wb_valid": 1,
            "wb_rd": 5,
            "wb_data": 2089,
            "src0_valid": 1,
            "src0_reg": 6,
            "src0_data": 2089,
            "dst_valid": 1,
            "dst_reg": 5,
            "dst_data": 2089,
        }
    )
    return [r0, r1, r2]


def write_rows(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as f:
        for row in fixture_rows():
            f.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")


def self_test() -> None:
    rows = fixture_rows()
    assert len(rows) == 3
    assert rows[0]["pc"] == 0x1000
    assert rows[0]["wb_data"] == 42
    assert rows[1]["src0_data"] == 42
    assert rows[2]["len"] == 2
    assert rows[2]["next_pc"] == 0x100A


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", help="JSONL rows to write")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("frontend-fetch-rf-alu-fixture-rows self-test: ok")
        return 0
    if not args.output:
        parser.error("--output is required unless --self-test is used")

    output = Path(args.output)
    write_rows(output)
    print(f"frontend-fetch-rf-alu-fixture-rows={output} rows={len(fixture_rows())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
