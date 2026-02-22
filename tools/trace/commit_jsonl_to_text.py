#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any


def _to_int(v: Any, default: int = 0) -> int:
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        try:
            return int(v, 0)
        except ValueError:
            return default
    return default


def _fmt_hex(v: int, width: int = 0) -> str:
    if width <= 0:
        return f"0x{v:x}"
    return f"0x{v:0{width}x}"


def _mask_insn(insn: int, length: int) -> int:
    if length == 2:
        return insn & 0xFFFF
    if length == 4:
        return insn & 0xFFFFFFFF
    if length == 6:
        return insn & 0xFFFFFFFFFFFF
    if length == 8:
        return insn & 0xFFFFFFFFFFFFFFFF
    return insn


def _choose_objdump(cli_tool: str | None) -> str | None:
    if cli_tool:
        return cli_tool
    for cand in (
        "llvm-objdump",
        "/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-objdump",
        "objdump",
    ):
        p = shutil.which(cand)
        if p:
            return p
        if Path(cand).is_file():
            return cand
    return None


def _build_disasm_map(elf: Path, objdump_tool: str | None) -> dict[int, str]:
    tool = _choose_objdump(objdump_tool)
    if tool is None or not elf.is_file():
        return {}
    cmd = [tool, "-d", str(elf)]
    try:
        out = subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL, errors="ignore")
    except Exception:
        return {}

    disasm: dict[int, str] = {}
    for ln in out.splitlines():
        m = re.match(r"^\s*([0-9a-fA-F]+):\s*(.*)$", ln)
        if not m:
            continue
        addr = int(m.group(1), 16)
        rest = m.group(2).strip()
        if not rest:
            continue
        toks = rest.split()
        i = 0
        while i < len(toks) and re.fullmatch(r"[0-9a-fA-F]+", toks[i]):
            i += 1
        if i >= len(toks):
            continue
        text = " ".join(toks[i:]).strip()
        if text:
            disasm[addr] = text
    return disasm


def _trace_line(row: dict[str, Any], idx: int, disasm: dict[int, str]) -> str:
    seq = _to_int(row.get("seq", idx))
    cyc = _to_int(row.get("cycle", -1))
    pc = _to_int(row.get("pc", 0))
    raw_insn = _to_int(row.get("insn", 0))
    length = _to_int(row.get("len", 0))
    insn = _mask_insn(raw_insn, length)
    next_pc = _to_int(row.get("next_pc", 0))
    op_name = str(row.get("op_name", "")).strip()
    op = _to_int(row.get("op", 0))
    uid = _to_int(row.get("uop_uid", 0))
    parent_uid = _to_int(row.get("parent_uid", 0))

    src0_valid = _to_int(row.get("src0_valid", 0)) != 0
    src0_reg = _to_int(row.get("src0_reg", 0))
    src0_data = _to_int(row.get("src0_data", 0))
    src1_valid = _to_int(row.get("src1_valid", 0)) != 0
    src1_reg = _to_int(row.get("src1_reg", 0))
    src1_data = _to_int(row.get("src1_data", 0))

    dst_valid = _to_int(row.get("dst_valid", row.get("wb_valid", 0))) != 0
    dst_reg = _to_int(row.get("dst_reg", row.get("wb_rd", 0)))
    dst_data = _to_int(row.get("dst_data", row.get("wb_data", 0)))
    wb_valid = _to_int(row.get("wb_valid", 0)) != 0
    wb_rd = _to_int(row.get("wb_rd", 0))
    wb_data = _to_int(row.get("wb_data", 0))

    mem_valid = _to_int(row.get("mem_valid", 0)) != 0
    mem_is_store = _to_int(row.get("mem_is_store", 0)) != 0
    mem_addr = _to_int(row.get("mem_addr", 0))
    mem_wdata = _to_int(row.get("mem_wdata", 0))
    mem_rdata = _to_int(row.get("mem_rdata", 0))
    mem_size = _to_int(row.get("mem_size", 0))

    trap_valid = _to_int(row.get("trap_valid", 0)) != 0
    trap_cause = _to_int(row.get("trap_cause", 0))
    traparg0 = _to_int(row.get("traparg0", 0))

    dis = disasm.get(pc, "")
    if src0_valid:
        src0 = f"r{src0_reg}={_fmt_hex(src0_data, 16)}"
    else:
        src0 = "-"
    if src1_valid:
        src1 = f"r{src1_reg}={_fmt_hex(src1_data, 16)}"
    else:
        src1 = "-"
    if dst_valid:
        dst = f"r{dst_reg}={_fmt_hex(dst_data, 16)}"
    else:
        dst = "-"
    if wb_valid:
        wb = f"r{wb_rd}={_fmt_hex(wb_data, 16)}"
    else:
        wb = "-"
    if mem_valid:
        if mem_is_store:
            mem = f"ST{mem_size}@{_fmt_hex(mem_addr, 16)}={_fmt_hex(mem_wdata, 16)}"
        else:
            mem = f"LD{mem_size}@{_fmt_hex(mem_addr, 16)}=>{_fmt_hex(mem_rdata, 16)}"
    else:
        mem = "-"
    if trap_valid:
        trap = f"cause={_fmt_hex(trap_cause)} arg0={_fmt_hex(traparg0, 16)}"
    else:
        trap = "-"

    op_text = op_name if op_name else f"op{op}"
    cyc_text = str(cyc) if cyc >= 0 else "-"
    parts = [
        f"seq={seq:08d}",
        f"cyc={cyc_text}",
        f"pc={_fmt_hex(pc, 16)}",
        f"insn={_fmt_hex(insn)}",
        f"len={length}",
        f"next={_fmt_hex(next_pc, 16)}",
        f"op={op_text}",
        f"uid={_fmt_hex(uid)}",
        f"parent={_fmt_hex(parent_uid)}",
        f"src0={src0}",
        f"src1={src1}",
        f"dst={dst}",
        f"wb={wb}",
        f"mem={mem}",
        f"trap={trap}",
    ]
    if dis:
        parts.append(f"asm={dis}")
    return " | ".join(parts)


def main() -> int:
    ap = argparse.ArgumentParser(description="Convert LinxCore commit JSONL trace to readable text.")
    ap.add_argument("--input", required=True, help="Input commit trace JSONL")
    ap.add_argument("--output", required=True, help="Output text trace path")
    ap.add_argument("--objdump-elf", default="", help="Optional ELF for disassembly mapping")
    ap.add_argument("--objdump-tool", default="", help="Optional objdump binary path")
    args = ap.parse_args()

    in_path = Path(args.input)
    out_path = Path(args.output)
    if not in_path.is_file():
        raise SystemExit(f"error: missing input trace: {in_path}")

    disasm_map: dict[int, str] = {}
    if args.objdump_elf:
        disasm_map = _build_disasm_map(Path(args.objdump_elf), args.objdump_tool or None)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    rows = 0
    with in_path.open("r", encoding="utf-8", errors="ignore") as fin, out_path.open(
        "w", encoding="utf-8"
    ) as fout:
        fout.write(f"# LinxCore instruction trace\n")
        fout.write(f"# input_jsonl: {in_path}\n")
        if args.objdump_elf:
            fout.write(f"# disasm_elf: {args.objdump_elf}\n")
        fout.write("\n")
        for idx, line in enumerate(fin):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            fout.write(_trace_line(row, rows, disasm_map))
            fout.write("\n")
            rows += 1
        fout.write(f"\n# rows={rows}\n")
    print(f"text_trace: {out_path} rows={rows}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
