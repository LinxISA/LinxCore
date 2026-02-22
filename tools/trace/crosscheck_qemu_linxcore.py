#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class Commit:
    seq: int
    pc: int
    insn: int
    length: int
    wb_valid: int
    wb_rd: int
    wb_data: int
    src0_valid: int
    src0_reg: int
    src0_data: int
    src1_valid: int
    src1_reg: int
    src1_data: int
    dst_valid: int
    dst_reg: int
    dst_data: int
    mem_valid: int
    mem_is_store: int
    mem_addr: int
    mem_wdata: int
    mem_rdata: int
    mem_size: int
    trap_valid: int
    trap_cause: int
    traparg0: int
    next_pc: int


def _mask_insn(raw: int, length: int) -> int:
    if length == 2:
        return raw & 0xFFFF
    if length == 4:
        return raw & 0xFFFFFFFF
    if length == 6:
        return raw & 0xFFFFFFFFFFFF
    return raw & 0xFFFFFFFFFFFFFFFF


def _is_bstart16(hw: int) -> bool:
    if (hw & 0xC7FF) in (0x0000, 0x0080):
        brtype = (hw >> 11) & 0x7
        if brtype != 0:
            return True
    if (hw & 0x000F) in (0x0002, 0x0004):
        return True
    return hw in (0x0840, 0x08C0, 0x48C0, 0x88C0, 0xC8C0)


def _is_bstart32(insn: int) -> bool:
    return (insn & 0x00007FFF) in (
        0x00001001,
        0x00002001,
        0x00003001,
        0x00004001,
        0x00005001,
        0x00006001,
        0x00007001,
    )


def _is_bstart48(raw: int) -> bool:
    prefix = raw & 0xFFFF
    main32 = (raw >> 16) & 0xFFFFFFFF
    if (prefix & 0xF) != 0xE:
        return False
    return ((main32 & 0xFF) == 0x01) and (((main32 >> 12) & 0x7) != 0)


def _is_macro_marker32(insn: int) -> bool:
    return (insn & 0x0000707F) in (0x00000041, 0x00001041, 0x00002041, 0x00003041)


def _is_template_parent_marker(r: Commit) -> bool:
    """Detect macro/template parent marker rows emitted by QEMU commit trace.

    In LinxCore, template parents may not retire as standalone rows; only
    expanded child uops retire. To keep compare streams aligned, treat an
    architecturally empty template parent marker as metadata.
    """
    if r.length != 4:
        return False
    insn = _mask_insn(r.insn, r.length)
    if not _is_macro_marker32(insn):
        return False
    if r.trap_valid != 0:
        return False
    if r.wb_valid != 0 or r.mem_valid != 0 or r.dst_valid != 0:
        return False
    return True


def _is_sideeffect_free_sysreg_marker(r: Commit) -> bool:
    # QEMU can emit sysreg marker rows (e.g. SSRSET) as committed instructions
    # with no architectural WB/MEM/TRAP payload. LinxCore may currently model
    # these as internal control ops without a retire row.
    if r.length != 4:
        return False
    insn = _mask_insn(r.insn, r.length)
    if (insn & 0x7F) != 0x3B:
        return False
    if r.wb_valid != 0 or r.dst_valid != 0 or r.mem_valid != 0 or r.trap_valid != 0:
        return False
    return True


def _is_metadata_commit(r: Commit) -> bool:
    # Treat fully zeroed placeholders and template parent markers as metadata.
    return ((r.length == 0) and (r.insn == 0) and (r.pc == 0)) or _is_template_parent_marker(r)


def _to_int(v: Any, default: int = 0) -> int:
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        try:
            return int(v, 0)
        except ValueError:
            return default
    return default


def _load_trace(path: Path, limit: int) -> list[Commit]:
    rows: list[Commit] = []
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for idx, line in enumerate(f):
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            seq = _to_int(obj.get("seq", len(rows)))
            row = Commit(
                seq=seq,
                pc=_to_int(obj.get("pc", 0)),
                insn=_to_int(obj.get("insn", 0)),
                length=_to_int(obj.get("len", 0)),
                wb_valid=_to_int(obj.get("wb_valid", 0)),
                wb_rd=_to_int(obj.get("wb_rd", 0)),
                wb_data=_to_int(obj.get("wb_data", 0)),
                src0_valid=_to_int(obj.get("src0_valid", 0)),
                src0_reg=_to_int(obj.get("src0_reg", 0)),
                src0_data=_to_int(obj.get("src0_data", 0)),
                src1_valid=_to_int(obj.get("src1_valid", 0)),
                src1_reg=_to_int(obj.get("src1_reg", 0)),
                src1_data=_to_int(obj.get("src1_data", 0)),
                dst_valid=_to_int(obj.get("dst_valid", obj.get("wb_valid", 0))),
                dst_reg=_to_int(obj.get("dst_reg", obj.get("wb_rd", 0))),
                dst_data=_to_int(obj.get("dst_data", obj.get("wb_data", 0))),
                mem_valid=_to_int(obj.get("mem_valid", 0)),
                mem_is_store=_to_int(obj.get("mem_is_store", 0)),
                mem_addr=_to_int(obj.get("mem_addr", 0)),
                mem_wdata=_to_int(obj.get("mem_wdata", 0)),
                mem_rdata=_to_int(obj.get("mem_rdata", 0)),
                mem_size=_to_int(obj.get("mem_size", 0)),
                trap_valid=_to_int(obj.get("trap_valid", 0)),
                trap_cause=_to_int(obj.get("trap_cause", 0)),
                traparg0=_to_int(obj.get("traparg0", 0)),
                next_pc=_to_int(obj.get("next_pc", 0)),
            )
            rows.append(row)
            if limit > 0 and len(rows) >= limit:
                break
    return rows


def _cmp_commit(q: Commit, d: Commit) -> tuple[bool, str, int, int]:
    # Match lockstep runner normalization rules.
    checks: list[tuple[str, int, int]] = [
        ("pc", q.pc, d.pc),
        ("len", q.length, d.length),
        ("insn", _mask_insn(q.insn, q.length), _mask_insn(d.insn, d.length)),
        ("wb_valid", q.wb_valid, d.wb_valid),
        ("mem_valid", q.mem_valid, d.mem_valid),
        ("trap_valid", q.trap_valid, d.trap_valid),
        ("next_pc", q.next_pc, d.next_pc),
    ]
    for name, qv, dv in checks:
        if qv != dv:
            return False, name, qv, dv

    if q.wb_valid:
        if q.wb_rd != d.wb_rd:
            return False, "wb_rd", q.wb_rd, d.wb_rd
        if q.wb_data != d.wb_data:
            return False, "wb_data", q.wb_data, d.wb_data
    if q.src0_valid and not d.src0_valid:
        return False, "src0_valid", q.src0_valid, d.src0_valid
    if q.src0_valid and d.src0_valid:
        if q.src0_reg != d.src0_reg:
            return False, "src0_reg", q.src0_reg, d.src0_reg
        if q.src0_data != d.src0_data:
            return False, "src0_data", q.src0_data, d.src0_data
    if q.src1_valid and not d.src1_valid:
        return False, "src1_valid", q.src1_valid, d.src1_valid
    if q.src1_valid and d.src1_valid:
        if q.src1_reg != d.src1_reg:
            return False, "src1_reg", q.src1_reg, d.src1_reg
        if q.src1_data != d.src1_data:
            return False, "src1_data", q.src1_data, d.src1_data
    if q.dst_valid != d.dst_valid:
        return False, "dst_valid", q.dst_valid, d.dst_valid
    if q.dst_valid:
        if q.dst_reg != d.dst_reg:
            return False, "dst_reg", q.dst_reg, d.dst_reg
        if q.dst_data != d.dst_data:
            return False, "dst_data", q.dst_data, d.dst_data

    if q.mem_valid:
        if q.mem_is_store != d.mem_is_store:
            return False, "mem_is_store", q.mem_is_store, d.mem_is_store
        if q.mem_addr != d.mem_addr:
            return False, "mem_addr", q.mem_addr, d.mem_addr
        if q.mem_size != d.mem_size:
            return False, "mem_size", q.mem_size, d.mem_size
        if q.mem_is_store:
            if q.mem_wdata != d.mem_wdata:
                return False, "mem_wdata", q.mem_wdata, d.mem_wdata
        else:
            if q.mem_rdata != d.mem_rdata:
                return False, "mem_rdata", q.mem_rdata, d.mem_rdata

    if q.trap_valid:
        if q.trap_cause != d.trap_cause:
            return False, "trap_cause", q.trap_cause, d.trap_cause
        if q.traparg0 != d.traparg0:
            return False, "traparg0", q.traparg0, d.traparg0

    return True, "", 0, 0


def _cbstop_count(rows: list[Commit]) -> int:
    return sum(1 for r in rows if r.length == 2 and (_mask_insn(r.insn, r.length) == 0))


def _hex(v: int) -> str:
    return f"0x{v:x}"


def _row_dict(r: Commit) -> dict[str, Any]:
    return {
        "seq": r.seq,
        "pc": r.pc,
        "insn": _mask_insn(r.insn, r.length),
        "len": r.length,
        "wb": {
            "valid": r.wb_valid,
            "rd": r.wb_rd,
            "data": r.wb_data,
        },
        "src0": {
            "valid": r.src0_valid,
            "reg": r.src0_reg,
            "data": r.src0_data,
        },
        "src1": {
            "valid": r.src1_valid,
            "reg": r.src1_reg,
            "data": r.src1_data,
        },
        "dst": {
            "valid": r.dst_valid,
            "reg": r.dst_reg,
            "data": r.dst_data,
        },
        "mem": {
            "valid": r.mem_valid,
            "is_store": r.mem_is_store,
            "addr": r.mem_addr,
            "wdata": r.mem_wdata,
            "rdata": r.mem_rdata,
            "size": r.mem_size,
        },
        "trap": {
            "valid": r.trap_valid,
            "cause": r.trap_cause,
            "arg0": r.traparg0,
        },
        "next_pc": r.next_pc,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Cross-check QEMU and LinxCore commit traces.")
    ap.add_argument("--qemu-trace", required=True, help="QEMU JSONL trace path")
    ap.add_argument("--dut-trace", required=True, help="LinxCore TB JSONL trace path")
    ap.add_argument("--mode", choices=("diagnostic", "failfast"), default="diagnostic")
    ap.add_argument("--max-commits", type=int, default=1000)
    ap.add_argument("--report-dir", default="", help="Output directory for report files")
    ap.add_argument(
        "--cbstop-inflation-threshold",
        type=float,
        default=8.0,
        help="Warn when DUT C.BSTOP count exceeds threshold * QEMU count in compared window",
    )
    args = ap.parse_args()

    qemu_trace = Path(args.qemu_trace)
    dut_trace = Path(args.dut_trace)
    if not qemu_trace.is_file():
        raise SystemExit(f"error: missing qemu trace: {qemu_trace}")
    if not dut_trace.is_file():
        raise SystemExit(f"error: missing dut trace: {dut_trace}")

    report_dir = Path(args.report_dir) if args.report_dir else dut_trace.parent
    report_dir.mkdir(parents=True, exist_ok=True)
    report_json = report_dir / "crosscheck_report.json"
    report_md = report_dir / "crosscheck_report.md"
    mismatch_json = report_dir / "crosscheck_mismatches.json"

    limit = max(args.max_commits, 0)
    raw_limit = 0
    if limit > 0:
        # Metadata rows are filtered before compare. Read a wider raw window so
        # the compare window can still reach `max_commits` architectural rows.
        raw_limit = limit * 32 + 1024
    q_rows = _load_trace(qemu_trace, raw_limit)
    d_rows = _load_trace(dut_trace, raw_limit)
    mismatches: list[dict[str, Any]] = []
    q_idx = 0
    d_idx = 0
    compared = 0
    q_meta_skipped = 0
    d_meta_skipped = 0
    q_compared_rows: list[Commit] = []
    d_compared_rows: list[Commit] = []

    compare_limit = limit if limit > 0 else None
    while q_idx < len(q_rows) and d_idx < len(d_rows):
        while q_idx < len(q_rows) and _is_metadata_commit(q_rows[q_idx]):
            q_meta_skipped += 1
            q_idx += 1
        while d_idx < len(d_rows) and _is_metadata_commit(d_rows[d_idx]):
            d_meta_skipped += 1
            d_idx += 1
        if q_idx >= len(q_rows) or d_idx >= len(d_rows):
            break
        if compare_limit is not None and compared >= compare_limit:
            break
        q = q_rows[q_idx]
        d = d_rows[d_idx]
        q_compared_rows.append(q)
        d_compared_rows.append(d)
        ok, field, qv, dv = _cmp_commit(q, d)
        if not ok:
            # Bounded, local resync: skip known side-effect-free marker rows if
            # the next row aligns exactly. This keeps compare strict for
            # architectural WB/MEM/TRAP effects.
            if _is_sideeffect_free_sysreg_marker(q) and (q_idx + 1) < len(q_rows):
                next_q = q_rows[q_idx + 1]
                ok_next, _, _, _ = _cmp_commit(next_q, d)
                if ok_next:
                    q_meta_skipped += 1
                    q_idx += 1
                    continue
            if _is_sideeffect_free_sysreg_marker(d) and (d_idx + 1) < len(d_rows):
                next_d = d_rows[d_idx + 1]
                ok_next, _, _, _ = _cmp_commit(q, next_d)
                if ok_next:
                    d_meta_skipped += 1
                    d_idx += 1
                    continue
            mismatches.append(
                {
                    "seq": d.seq,
                    "field": field,
                    "qemu": qv,
                    "dut": dv,
                    "qemu_pc": q.pc,
                    "dut_pc": d.pc,
                    "qemu_insn": _mask_insn(q.insn, q.length),
                    "dut_insn": _mask_insn(d.insn, d.length),
                    "qemu_row": _row_dict(q),
                    "dut_row": _row_dict(d),
                }
            )
            if args.mode == "failfast":
                break
        compared += 1
        q_idx += 1
        d_idx += 1

    q_rem_nonmeta = sum(1 for r in q_rows[q_idx:] if not _is_metadata_commit(r))
    d_rem_nonmeta = sum(1 for r in d_rows[d_idx:] if not _is_metadata_commit(r))
    if compare_limit is None:
        if q_rem_nonmeta != 0 or d_rem_nonmeta != 0:
            mismatches.append(
                {
                    "seq": compared if not d_compared_rows else d_compared_rows[-1].seq,
                    "field": "trace_length",
                    "qemu": q_rem_nonmeta,
                    "dut": d_rem_nonmeta,
                    "qemu_pc": q_compared_rows[-1].pc if q_compared_rows else 0,
                    "dut_pc": d_compared_rows[-1].pc if d_compared_rows else 0,
                    "qemu_insn": 0,
                    "dut_insn": 0,
                    "qemu_row": _row_dict(q_compared_rows[-1]) if q_compared_rows else {},
                    "dut_row": _row_dict(d_compared_rows[-1]) if d_compared_rows else {},
                }
            )
    else:
        # Bounded compare window: QEMU can legitimately have extra tail rows
        # because DUT halts at MAX_COMMITS while QEMU is sampled asynchronously.
        # Only fail for DUT overflow or when both traces terminate too early.
        if d_rem_nonmeta > 0 or ((q_rem_nonmeta == 0) and (d_rem_nonmeta == 0) and (compared < compare_limit)):
            mismatches.append(
                {
                    "seq": compared if not d_compared_rows else d_compared_rows[-1].seq,
                    "field": "trace_length",
                    "qemu": q_rem_nonmeta,
                    "dut": d_rem_nonmeta,
                    "qemu_pc": q_compared_rows[-1].pc if q_compared_rows else 0,
                    "dut_pc": d_compared_rows[-1].pc if d_compared_rows else 0,
                    "qemu_insn": 0,
                    "dut_insn": 0,
                    "qemu_row": _row_dict(q_compared_rows[-1]) if q_compared_rows else {},
                    "dut_row": _row_dict(d_compared_rows[-1]) if d_compared_rows else {},
                }
            )

    q_cbstop = _cbstop_count(q_compared_rows)
    d_cbstop = _cbstop_count(d_compared_rows)
    if q_cbstop == 0:
        inflation = math.inf if d_cbstop > 0 else 1.0
    else:
        inflation = float(d_cbstop) / float(q_cbstop)

    inflation_warn = False
    if q_cbstop == 0:
        inflation_warn = d_cbstop >= 8
    else:
        inflation_warn = inflation > args.cbstop_inflation_threshold

    first = mismatches[0] if mismatches else None
    report: dict[str, Any] = {
        "mode": args.mode,
        "max_commits": limit,
        "qemu_trace": str(qemu_trace),
        "dut_trace": str(dut_trace),
        "qemu_rows": len(q_rows),
        "dut_rows": len(d_rows),
        "compared_rows": compared,
        "qemu_meta_skipped": q_meta_skipped,
        "dut_meta_skipped": d_meta_skipped,
        "mismatch_count": len(mismatches),
        "first_mismatch": first,
        "cbstop_counts": {
            "qemu": q_cbstop,
            "dut": d_cbstop,
            "inflation_ratio": inflation if math.isfinite(inflation) else "inf",
            "warn": inflation_warn,
            "threshold": args.cbstop_inflation_threshold,
        },
    }

    report_json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    mismatch_json.write_text(json.dumps(mismatches, indent=2) + "\n", encoding="utf-8")

    md = []
    md.append("# QEMU vs LinxCore Cross-Check Report")
    md.append("")
    md.append("## Summary")
    md.append("")
    md.append(f"- Mode: `{args.mode}`")
    md.append(f"- Compared commits: `{compared}`")
    md.append(f"- QEMU rows loaded: `{len(q_rows)}`")
    md.append(f"- LinxCore rows loaded: `{len(d_rows)}`")
    md.append(f"- Metadata skipped (QEMU): `{q_meta_skipped}`")
    md.append(f"- Metadata skipped (LinxCore): `{d_meta_skipped}`")
    md.append(f"- Mismatches: `{len(mismatches)}`")
    md.append(f"- C.BSTOP count (QEMU): `{q_cbstop}`")
    md.append(f"- C.BSTOP count (LinxCore): `{d_cbstop}`")
    if math.isfinite(inflation):
        md.append(f"- C.BSTOP inflation ratio: `{inflation:.3f}`")
    else:
        md.append("- C.BSTOP inflation ratio: `inf`")
    md.append(f"- Inflation warning: `{inflation_warn}`")
    md.append("")
    md.append("## First Mismatch")
    md.append("")
    if first is None:
        md.append("- None")
    else:
        md.append(f"- seq: `{first['seq']}`")
        md.append(f"- field: `{first['field']}`")
        md.append(f"- qemu: `{_hex(int(first['qemu']))}`")
        md.append(f"- dut: `{_hex(int(first['dut']))}`")
        md.append(f"- qemu_pc: `{_hex(int(first['qemu_pc']))}`")
        md.append(f"- dut_pc: `{_hex(int(first['dut_pc']))}`")
        md.append(f"- qemu_insn: `{_hex(int(first['qemu_insn']))}`")
        md.append(f"- dut_insn: `{_hex(int(first['dut_insn']))}`")
        if isinstance(first.get("qemu_row"), dict) and first["qemu_row"]:
            md.append(f"- qemu_row: `{json.dumps(first['qemu_row'], sort_keys=True)}`")
        if isinstance(first.get("dut_row"), dict) and first["dut_row"]:
            md.append(f"- dut_row: `{json.dumps(first['dut_row'], sort_keys=True)}`")
    md.append("")
    md.append("## Files")
    md.append("")
    md.append(f"- Report JSON: `{report_json}`")
    md.append(f"- Mismatches JSON: `{mismatch_json}`")
    report_md.write_text("\n".join(md) + "\n", encoding="utf-8")

    print(f"report_json={report_json}")
    print(f"report_md={report_md}")
    print(f"mismatch_json={mismatch_json}")
    print(f"compared={compared} mismatches={len(mismatches)} cbstop_qemu={q_cbstop} cbstop_dut={d_cbstop}")
    if mismatches:
        first_mm = mismatches[0]
        print("first_mismatch:")
        print(
            f"  seq={first_mm['seq']} field={first_mm['field']} "
            f"qemu={_hex(int(first_mm['qemu']))} dut={_hex(int(first_mm['dut']))}"
        )
        if isinstance(first_mm.get("qemu_row"), dict) and first_mm["qemu_row"]:
            print(f"  qemu_row={json.dumps(first_mm['qemu_row'], sort_keys=True)}")
        if isinstance(first_mm.get("dut_row"), dict) and first_mm["dut_row"]:
            print(f"  dut_row={json.dumps(first_mm['dut_row'], sort_keys=True)}")

    if mismatches:
        if args.mode == "failfast":
            return 1
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
