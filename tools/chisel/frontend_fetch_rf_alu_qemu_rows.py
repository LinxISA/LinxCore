#!/usr/bin/env python3
"""Extract reduced scalar expected rows from a QEMU commit JSONL trace."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any, Iterable

from trace_schema_adapter import REQUIRED_TRACE_FIELDS, load_jsonl, normalize_row


MASK64 = (1 << 64) - 1


class RowExtractionError(RuntimeError):
    pass


def _mask_insn(insn: int, length: int) -> int:
    if length == 2:
        return insn & 0xFFFF
    if length == 4:
        return insn & 0xFFFF_FFFF
    if length == 6:
        return insn & 0xFFFF_FFFF_FFFF
    if length == 8:
        return insn & 0xFFFF_FFFF_FFFF_FFFF
    raise RowExtractionError(f"unsupported instruction length: {length}")


def _sext(value: int, width: int) -> int:
    sign = 1 << (width - 1)
    mask = (1 << width) - 1
    value &= mask
    if value & sign:
        return value | (MASK64 ^ mask)
    return value


def _classify(row: dict[str, int]) -> str | None:
    insn = _mask_insn(row["insn"], row["len"])
    if row["len"] == 4:
        key = insn & 0x707F
        if key == 0x0005:
            return "ADD"
        if key == 0x0015:
            return "ADDI"
    if row["len"] == 2:
        key = insn & 0x3F
        if key == 0x0016:
            return "C.MOVI"
        if key == 0x0006:
            return "C.MOVR"
    return None


def _uimm12(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0xFFF


def _simm5_6(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F, 5)


def _require_scalar_reg(row: dict[str, int], field: str, opcode: str) -> None:
    reg = row[field]
    if reg >= 24:
        raise RowExtractionError(
            f"{opcode} row uses non-scalar GPR {field}={reg}; reduced RF/ALU gate supports arch regs 0..23"
        )


def _require_sources(row: dict[str, int], opcode: str, src0: bool, src1: bool) -> None:
    if bool(row["src0_valid"]) != src0:
        raise RowExtractionError(f"{opcode} row has src0_valid={row['src0_valid']}, expected {int(src0)}")
    if bool(row["src1_valid"]) != src1:
        raise RowExtractionError(f"{opcode} row has src1_valid={row['src1_valid']}, expected {int(src1)}")
    if src0:
        _require_scalar_reg(row, "src0_reg", opcode)
    if src1:
        _require_scalar_reg(row, "src1_reg", opcode)


def _require_writeback(row: dict[str, int], opcode: str) -> None:
    if not row["dst_valid"] or not row["wb_valid"]:
        raise RowExtractionError(f"{opcode} row must have destination/writeback valid")
    _require_scalar_reg(row, "dst_reg", opcode)
    _require_scalar_reg(row, "wb_rd", opcode)
    if row["dst_reg"] != row["wb_rd"]:
        raise RowExtractionError(f"{opcode} row dst_reg={row['dst_reg']} differs from wb_rd={row['wb_rd']}")
    if row["dst_data"] != row["wb_data"]:
        raise RowExtractionError(f"{opcode} row dst_data={row['dst_data']} differs from wb_data={row['wb_data']}")


def _expected_result(row: dict[str, int], opcode: str) -> int:
    if opcode == "ADD":
        return (row["src0_data"] + row["src1_data"]) & MASK64
    if opcode == "ADDI":
        return (row["src0_data"] + _uimm12(row)) & MASK64
    if opcode == "C.MOVI":
        return _simm5_6(row) & MASK64
    if opcode == "C.MOVR":
        return row["src0_data"] & MASK64
    raise AssertionError(opcode)


def _validate_reduced_row(row: dict[str, int], index: int) -> dict[str, int]:
    opcode = _classify(row)
    if opcode is None:
        raise RowExtractionError(
            f"row {index} has unsupported opcode for reduced RF/ALU gate: "
            f"pc=0x{row['pc']:x} insn=0x{_mask_insn(row['insn'], row['len']):x} len={row['len']}"
        )
    if row["trap_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has trap_valid=1")
    if row["mem_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has mem_valid=1")
    if row["next_pc"] != row["pc"] + row["len"]:
        raise RowExtractionError(
            f"row {index} {opcode} is not sequential: next_pc=0x{row['next_pc']:x}, "
            f"expected 0x{row['pc'] + row['len']:x}"
        )

    if opcode == "ADD":
        _require_sources(row, opcode, src0=True, src1=True)
    elif opcode == "ADDI":
        _require_sources(row, opcode, src0=True, src1=False)
    elif opcode == "C.MOVI":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "C.MOVR":
        _require_sources(row, opcode, src0=True, src1=False)
    _require_writeback(row, opcode)

    expected = _expected_result(row, opcode)
    if row["dst_data"] != expected:
        raise RowExtractionError(
            f"row {index} {opcode} result mismatch: dst_data=0x{row['dst_data']:x}, expected 0x{expected:x}"
        )

    out = {field: int(row[field]) for field in REQUIRED_TRACE_FIELDS}
    out["insn"] = _mask_insn(out["insn"], out["len"])
    return out


def _normalized_rows(path: Path) -> Iterable[dict[str, int]]:
    for seq, obj in enumerate(load_jsonl(path)):
        yield normalize_row(obj, seq)


def extract_rows(input_path: Path, output_path: Path, max_rows: int = 0) -> int:
    if max_rows < 0:
        raise RowExtractionError("--max-rows must be non-negative")

    rows: list[dict[str, int]] = []
    expected_pc: int | None = None
    for index, row in enumerate(_normalized_rows(input_path)):
        if max_rows > 0 and len(rows) >= max_rows:
            break
        if expected_pc is not None and row["pc"] != expected_pc:
            raise RowExtractionError(
                f"row {index} breaks the strict sequential prefix: pc=0x{row['pc']:x}, expected 0x{expected_pc:x}"
            )
        checked = _validate_reduced_row(row, index)
        rows.append(checked)
        expected_pc = checked["pc"] + checked["len"]

    if not rows:
        raise RowExtractionError(f"no reduced RF/ALU rows were extracted from {input_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
    return len(rows)


def _write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")


def self_test() -> None:
    from frontend_fetch_rf_alu_fixture_rows import fixture_rows

    with tempfile.TemporaryDirectory(prefix="linx-qemu-rows-") as td:
        tmp = Path(td)
        source = tmp / "qemu.jsonl"
        output = tmp / "rows.jsonl"
        rows = fixture_rows()
        _write_jsonl(source, [{"type": "META", "schema": "ignored"}, {"valid": 0}, *rows])

        count = extract_rows(source, output, max_rows=3)
        assert count == 3
        extracted = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
        assert extracted[0]["pc"] == 0x1000
        assert extracted[0]["dst_data"] == 42
        assert extracted[1]["src0_data"] == 42
        assert extracted[2]["len"] == 2

        unsupported = tmp / "unsupported.jsonl"
        bad = dict(rows[0])
        bad["mem_valid"] = 1
        _write_jsonl(unsupported, [bad])
        try:
            extract_rows(unsupported, tmp / "bad.jsonl")
        except RowExtractionError as exc:
            assert "mem_valid" in str(exc)
        else:
            raise AssertionError("unsupported row did not fail")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", help="Input QEMU commit JSONL trace")
    ap.add_argument("--output", help="Output reduced expected-row JSONL")
    ap.add_argument("--max-rows", type=int, default=0, help="Maximum reduced rows to extract; 0 means all")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        print("frontend-fetch-rf-alu-qemu-rows self-test: ok")
        return 0
    if not args.input or not args.output:
        raise SystemExit("error: --input and --output are required unless --self-test is set")

    try:
        count = extract_rows(Path(args.input), Path(args.output), max_rows=args.max_rows)
    except RowExtractionError as exc:
        raise SystemExit(f"error: {exc}") from exc
    print(f"frontend-fetch-rf-alu-qemu-rows={args.output} rows={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
