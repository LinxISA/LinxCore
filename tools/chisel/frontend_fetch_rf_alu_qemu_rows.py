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
C_BSTART_EXACT = {
    0x08C0,  # C.BSTART.MPAR
    0x48C0,  # C.BSTART.MSEQ
    0x2000,  # C.BSTART.STD.CALL
    0x1800,  # C.BSTART.STD.COND
    0x1000,  # C.BSTART.STD.DIRECT
    0x0800,  # C.BSTART.STD.FALL
    0x3000,  # C.BSTART.STD.ICALL
    0x2800,  # C.BSTART.STD.IND
    0x3800,  # C.BSTART.STD.RET
    0x0840,  # C.BSTART.SYS
    0x88C0,  # C.BSTART.VPAR
    0xC8C0,  # C.BSTART.VSEQ
}
HL_BSTART_MASK = 0xFFFF_0000_7FFF_000F
HL_BSTART_MATCHES = {
    0x4101_000E,  # HL.BSTART.FP.CALL
    0x3101_000E,  # HL.BSTART.FP.COND
    0x2101_000E,  # HL.BSTART.FP.DIRECT
    0x1101_000E,  # HL.BSTART.FP.FALL
    0x4001_000E,  # HL.BSTART.STD.CALL
    0x3001_000E,  # HL.BSTART.STD.COND
    0x2001_000E,  # HL.BSTART.STD.DIRECT
    0x1001_000E,  # HL.BSTART.STD.FALL
    0x1081_000E,  # HL.BSTART.SYS
}
HL_LUI_MASK = 0xFFFF_0000_007F_000F
HL_LUI_MATCH = 0x0000_0000_0017_000E


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
        if (insn & 0xFFF) == 0x0507:
            return None
        if (insn & 0x7F) == 0x0007:
            return "ADDTPC"
        if key == 0x0041:
            return "FENTRY"
    if row["len"] == 2:
        if (insn & 0xF83F) == 0x5016:
            return "C.SETRET"
        key = insn & 0x3F
        if key == 0x0016:
            return "C.MOVI"
        if key == 0x0006:
            return "C.MOVR"
    if row["len"] == 6:
        if (insn & HL_LUI_MASK) == HL_LUI_MATCH:
            return "HL.LUI"
    return None


def _classify_block_marker(row: dict[str, int]) -> tuple[str, bool, bool] | None:
    insn = _mask_insn(row["insn"], row["len"])
    if row["len"] == 2:
        if insn == 0:
            return ("C.BSTOP", False, True)
        if (
            insn in C_BSTART_EXACT
            or (insn & 0x000F) == 0x0002  # C.BSTART.DIRECT immediate form
            or (insn & 0x000F) == 0x0004  # C.BSTART.COND immediate form
            or (insn & 0xC7FF) == 0x0080  # C.BSTART.FP
        ):
            return ("C.BSTART", True, False)
    if row["len"] == 6 and (insn & HL_BSTART_MASK) in HL_BSTART_MATCHES:
        return ("HL.BSTART", True, False)
    return None


def _uimm12(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0xFFF


def _simm20_shifted(row: dict[str, int]) -> int:
    return (_sext((_mask_insn(row["insn"], row["len"]) >> 12) & 0xFFFFF, 20) << 12) & MASK64


def _simm5_6(row: dict[str, int]) -> int:
    return _sext((_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F, 5)


def _c_setret_imm(row: dict[str, int]) -> int:
    return ((_mask_insn(row["insn"], row["len"]) >> 6) & 0x1F) << 1


def _fentry_imm(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    return (((insn >> 7) & 0x1F) << 10) | (((insn >> 25) & 0x7F) << 3)


def _fentry_m(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 15) & 0x1F


def _fentry_n(row: dict[str, int]) -> int:
    return (_mask_insn(row["insn"], row["len"]) >> 20) & 0x1F


def _hl_lui_imm(row: dict[str, int]) -> int:
    insn = _mask_insn(row["insn"], row["len"])
    imm32 = (((insn >> 4) & 0xFFF) << 20) | ((insn >> 28) & 0xFFFFF)
    return _sext(imm32, 32) & MASK64


def _require_scalar_reg(row: dict[str, int], field: str, opcode: str) -> None:
    reg = row[field]
    if reg >= 24:
        raise RowExtractionError(
            f"{opcode} row uses non-scalar GPR {field}={reg}; reduced RF/ALU gate supports arch regs 0..23"
        )


def _is_reduced_tu_destination(reg: int) -> bool:
    # FrontendRegAliasClassify maps architectural destination alias 30 to U
    # and 31 to T. Keep admission opcode-specific so future T/U producers are
    # forced through their own QEMU-backed gate.
    return reg == 30 or reg == 31


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
    if row["dst_reg"] != row["wb_rd"]:
        raise RowExtractionError(f"{opcode} row dst_reg={row['dst_reg']} differs from wb_rd={row['wb_rd']}")
    if not _is_reduced_tu_destination(row["dst_reg"]):
        _require_scalar_reg(row, "dst_reg", opcode)
        _require_scalar_reg(row, "wb_rd", opcode)
    elif row["dst_reg"] == 30 and opcode != "ADDI":
        raise RowExtractionError(f"{opcode} row writes reduced U destination alias {row['dst_reg']}")
    elif row["dst_reg"] == 31 and opcode != "HL.LUI":
        raise RowExtractionError(f"{opcode} row writes reduced T destination alias {row['dst_reg']}")
    if row["dst_data"] != row["wb_data"]:
        raise RowExtractionError(f"{opcode} row dst_data={row['dst_data']} differs from wb_data={row['wb_data']}")


def _expected_result(row: dict[str, int], opcode: str) -> int:
    if opcode == "ADD":
        return (row["src0_data"] + row["src1_data"]) & MASK64
    if opcode == "ADDI":
        return (row["src0_data"] + _uimm12(row)) & MASK64
    if opcode == "ADDTPC":
        return ((row["pc"] & ~0xFFF) + _simm20_shifted(row)) & MASK64
    if opcode == "C.MOVI":
        return _simm5_6(row) & MASK64
    if opcode == "C.MOVR":
        return row["src0_data"] & MASK64
    if opcode == "C.SETRET":
        return (row["pc"] + _c_setret_imm(row)) & MASK64
    if opcode == "FENTRY":
        return (row["mem_addr"] + 8 - _fentry_imm(row)) & MASK64
    if opcode == "HL.LUI":
        return _hl_lui_imm(row)
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
    if row["mem_valid"] and opcode != "FENTRY":
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
    elif opcode == "ADDTPC":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "C.MOVI":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "C.MOVR":
        _require_sources(row, opcode, src0=True, src1=False)
    elif opcode == "C.SETRET":
        _require_sources(row, opcode, src0=False, src1=False)
    elif opcode == "FENTRY":
        _require_sources(row, opcode, src0=False, src1=False)
        if _fentry_m(row) != _fentry_n(row):
            raise RowExtractionError(
                f"row {index} FENTRY saves multiple registers: m={_fentry_m(row)} n={_fentry_n(row)}"
            )
        if _fentry_m(row) < 2 or _fentry_m(row) > 23:
            raise RowExtractionError(f"row {index} FENTRY save register is outside scalar GPR range")
        if not row["mem_valid"] or not row["mem_is_store"] or row["mem_size"] != 8:
            raise RowExtractionError(f"row {index} FENTRY must carry one 8-byte store")
    elif opcode == "HL.LUI":
        _require_sources(row, opcode, src0=False, src1=False)
    _require_writeback(row, opcode)

    expected = _expected_result(row, opcode)
    if row["dst_data"] != expected:
        raise RowExtractionError(
            f"row {index} {opcode} result mismatch: dst_data=0x{row['dst_data']:x}, expected 0x{expected:x}"
        )

    out = {field: int(row[field]) for field in REQUIRED_TRACE_FIELDS}
    out["insn"] = _mask_insn(out["insn"], out["len"])
    return out


def _validate_block_marker_row(
    row: dict[str, int],
    index: int,
    marker: tuple[str, bool, bool]) -> dict[str, Any]:
    opcode, boundary, stop = marker
    if row["trap_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has trap_valid=1")
    if row["mem_valid"]:
        raise RowExtractionError(f"row {index} {opcode} has mem_valid=1")
    if not stop and row["next_pc"] != row["pc"] + row["len"]:
        raise RowExtractionError(
            f"row {index} {opcode} is not sequential: next_pc=0x{row['next_pc']:x}, "
            f"expected 0x{row['pc'] + row['len']:x}"
        )

    out: dict[str, Any] = {field: int(row[field]) for field in REQUIRED_TRACE_FIELDS}
    out["insn"] = _mask_insn(out["insn"], out["len"])
    out["skip"] = 1
    out["skip_kind"] = opcode
    out["block_boundary"] = int(boundary)
    out["block_stop"] = int(stop)
    return out


def _is_zero_advance_marker_artifact(row: dict[str, int], marker: tuple[str, bool, bool]) -> bool:
    opcode, boundary, stop = marker
    return (
        boundary and
        not stop and
        opcode.endswith("BSTART") and
        row["next_pc"] == row["pc"] and
        not row["trap_valid"] and
        not row["mem_valid"] and
        not row["wb_valid"]
    )


def _normalized_rows(path: Path) -> Iterable[dict[str, int]]:
    for seq, obj in enumerate(load_jsonl(path)):
        yield normalize_row(obj, seq)


def extract_rows(
    input_path: Path,
    output_path: Path,
    max_rows: int = 0,
    allow_block_markers: bool = False) -> int:
    if max_rows < 0:
        raise RowExtractionError("--max-rows must be non-negative")

    rows: list[dict[str, Any]] = []
    expected_pc: int | None = None
    reduced_rows = 0
    for index, row in enumerate(_normalized_rows(input_path)):
        if max_rows > 0 and reduced_rows >= max_rows:
            break
        if expected_pc is not None and row["pc"] != expected_pc:
            raise RowExtractionError(
                f"row {index} breaks the strict sequential prefix: pc=0x{row['pc']:x}, expected 0x{expected_pc:x}"
            )

        marker = _classify_block_marker(row) if allow_block_markers else None
        if marker is not None and _is_zero_advance_marker_artifact(row, marker):
            continue
        if marker is not None:
            checked = _validate_block_marker_row(row, index, marker)
        else:
            checked = _validate_reduced_row(row, index)
            reduced_rows += 1
        rows.append(checked)
        expected_pc = checked["next_pc"]

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

        addtpc_source = tmp / "addtpc.jsonl"
        addtpc_output = tmp / "addtpc.rows.jsonl"
        addtpc = {
            **rows[0],
            "pc": 0x400054F8,
            "insn": 0x00009187,
            "len": 4,
            "next_pc": 0x400054FC,
            "wb_valid": 1,
            "wb_rd": 3,
            "wb_data": 0x4000E000,
            "dst_valid": 1,
            "dst_reg": 3,
            "dst_data": 0x4000E000,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(addtpc_source, [addtpc])
        count = extract_rows(addtpc_source, addtpc_output)
        assert count == 1
        extracted_addtpc = [json.loads(line) for line in addtpc_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_addtpc[0]["dst_data"] == 0x4000E000

        c_setret_source = tmp / "c-setret.jsonl"
        c_setret_output = tmp / "c-setret.rows.jsonl"
        c_setret = {
            **rows[0],
            "pc": 0x40005506,
            "insn": 0x5096,
            "len": 2,
            "next_pc": 0x40005508,
            "wb_valid": 1,
            "wb_rd": 10,
            "wb_data": 0x4000550A,
            "dst_valid": 1,
            "dst_reg": 10,
            "dst_data": 0x4000550A,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(c_setret_source, [c_setret])
        count = extract_rows(c_setret_source, c_setret_output)
        assert count == 1
        extracted_c_setret = [json.loads(line) for line in c_setret_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_c_setret[0]["dst_data"] == 0x4000550A

        fentry_source = tmp / "fentry.jsonl"
        fentry_output = tmp / "fentry.rows.jsonl"
        fentry = {
            **rows[0],
            "pc": 0x4000550E,
            "insn": 0x90A50041,
            "len": 4,
            "next_pc": 0x40005512,
            "wb_valid": 1,
            "wb_rd": 1,
            "wb_data": 0x4FFE_FDB0,
            "dst_valid": 1,
            "dst_reg": 1,
            "dst_data": 0x4FFE_FDB0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "mem_valid": 1,
            "mem_is_store": 1,
            "mem_addr": 0x4FFE_FFE8,
            "mem_wdata": 0x4000550A,
            "mem_rdata": 0,
            "mem_size": 8,
        }
        _write_jsonl(fentry_source, [fentry])
        count = extract_rows(fentry_source, fentry_output)
        assert count == 1
        extracted_fentry = [json.loads(line) for line in fentry_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_fentry[0]["dst_data"] == 0x4FFE_FDB0
        assert extracted_fentry[0]["mem_wdata"] == 0x4000550A

        u_dst_source = tmp / "addi-u-dst.jsonl"
        u_dst_output = tmp / "addi-u-dst.rows.jsonl"
        addi_u_dst = {
            **rows[0],
            "pc": 0x40005516,
            "insn": 0x02000F15,
            "len": 4,
            "next_pc": 0x4000551A,
            "wb_valid": 1,
            "wb_rd": 30,
            "wb_data": 32,
            "dst_valid": 1,
            "dst_reg": 30,
            "dst_data": 32,
            "src0_valid": 1,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(u_dst_source, [addi_u_dst])
        count = extract_rows(u_dst_source, u_dst_output)
        assert count == 1
        extracted_u_dst = [json.loads(line) for line in u_dst_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_u_dst[0]["dst_reg"] == 30
        assert extracted_u_dst[0]["wb_rd"] == 30
        assert extracted_u_dst[0]["dst_data"] == 32

        hl_lui_source = tmp / "hl-lui.jsonl"
        hl_lui_output = tmp / "hl-lui.rows.jsonl"
        hl_lui = {
            **rows[0],
            "pc": 0x4000551A,
            "insn": 0x1F97000E,
            "len": 6,
            "next_pc": 0x40005520,
            "wb_valid": 1,
            "wb_rd": 31,
            "wb_data": 1,
            "dst_valid": 1,
            "dst_reg": 31,
            "dst_data": 1,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
        }
        _write_jsonl(hl_lui_source, [hl_lui])
        count = extract_rows(hl_lui_source, hl_lui_output)
        assert count == 1
        extracted_hl_lui = [json.loads(line) for line in hl_lui_output.read_text(encoding="utf-8").splitlines()]
        assert extracted_hl_lui[0]["dst_reg"] == 31
        assert extracted_hl_lui[0]["wb_rd"] == 31
        assert extracted_hl_lui[0]["dst_data"] == 1

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

        block_source = tmp / "block-prefix.jsonl"
        block_output = tmp / "block-prefix.rows.jsonl"
        bstart = {
            **rows[0],
            "pc": 0x2000,
            "insn": 0x0800,
            "len": 2,
            "next_pc": 0x2002,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        scalar = {**rows[0], "pc": 0x2002, "next_pc": 0x2006}
        bstop = {
            **rows[0],
            "pc": 0x2006,
            "insn": 0,
            "len": 2,
            "next_pc": 0x2008,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        _write_jsonl(block_source, [bstart, scalar, bstop])
        count = extract_rows(block_source, block_output, allow_block_markers=True)
        assert count == 3
        block_rows = [json.loads(line) for line in block_output.read_text(encoding="utf-8").splitlines()]
        assert block_rows[0]["skip"] == 1
        assert block_rows[0]["block_boundary"] == 1
        assert "skip" not in block_rows[1]
        assert block_rows[2]["block_stop"] == 1

        hl_block_source = tmp / "hl-block-prefix.jsonl"
        hl_block_output = tmp / "hl-block-prefix.rows.jsonl"
        hl_artifact = {
            **rows[0],
            "pc": 0x40005500,
            "insn": 0x3C001000E,
            "len": 6,
            "next_pc": 0x40005500,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        hl_bstart = {**hl_artifact, "next_pc": 0x40005506}
        c_setret_after_hl = {**c_setret, "pc": 0x40005506, "next_pc": 0x40005508}
        c_bstop_after_hl = {
            **rows[0],
            "pc": 0x40005508,
            "insn": 0,
            "len": 2,
            "next_pc": 0x4000550E,
            "wb_valid": 0,
            "dst_valid": 0,
            "src0_valid": 0,
            "src1_valid": 0,
        }
        _write_jsonl(hl_block_source, [hl_artifact, hl_bstart, c_setret_after_hl, c_bstop_after_hl])
        count = extract_rows(hl_block_source, hl_block_output, allow_block_markers=True)
        assert count == 3
        hl_rows = [json.loads(line) for line in hl_block_output.read_text(encoding="utf-8").splitlines()]
        assert hl_rows[0]["skip"] == 1
        assert hl_rows[0]["skip_kind"] == "HL.BSTART"
        assert hl_rows[1]["pc"] == 0x40005506
        assert hl_rows[2]["block_stop"] == 1
        assert hl_rows[2]["next_pc"] == 0x4000550E


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", help="Input QEMU commit JSONL trace")
    ap.add_argument("--output", help="Output reduced expected-row JSONL")
    ap.add_argument("--max-rows", type=int, default=0, help="Maximum reduced rows to extract; 0 means all")
    ap.add_argument("--allow-block-markers", action="store_true",
                    help="Preserve legal BSTART/BSTOP marker rows as skip entries while extracting scalar compare rows")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        print("frontend-fetch-rf-alu-qemu-rows self-test: ok")
        return 0
    if not args.input or not args.output:
        raise SystemExit("error: --input and --output are required unless --self-test is set")

    try:
        count = extract_rows(
            Path(args.input),
            Path(args.output),
            max_rows=args.max_rows,
            allow_block_markers=args.allow_block_markers)
    except RowExtractionError as exc:
        raise SystemExit(f"error: {exc}") from exc
    print(f"frontend-fetch-rf-alu-qemu-rows={args.output} rows={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
