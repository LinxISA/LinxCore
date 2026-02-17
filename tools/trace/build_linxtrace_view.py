#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import collections
import dataclasses
import json
import math
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

ROOT_DIR = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT_DIR / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from common.stage_tokens import (
    LINXTRACE_PIPELINE_SCHEMA_ID,
    LINXTRACE_STAGE_ID_ORDER,
    LINXTRACE_STAGE_ORDER_CSV,
)

STAGE_ORDER = list(LINXTRACE_STAGE_ID_ORDER)
STAGE_RANK = {name: i for i, name in enumerate(STAGE_ORDER)}


def _fmt_hex(v: int) -> str:
    return f"0x{int(v):X}"


def _resolve_tool(candidates: List[str]) -> Optional[str]:
    for c in candidates:
        if not c:
            continue
        p = Path(c)
        if p.exists() and p.is_file():
            return str(p)
        q = shutil.which(c)
        if q:
            return q
    return None


def _load_symbol_maps(elf_path: Optional[Path]) -> Tuple[Dict[int, str], List[Tuple[int, str]]]:
    if elf_path is None or not elf_path.is_file():
        return {}, []

    objdump_bin = _resolve_tool(
        [
            str(Path.home() / "llvm-project/build-linxisa-clang/bin/llvm-objdump"),
            "llvm-objdump",
            "objdump",
        ]
    )
    nm_bin = _resolve_tool(
        [
            str(Path.home() / "llvm-project/build-linxisa-clang/bin/llvm-nm"),
            "llvm-nm",
            "nm",
        ]
    )

    sym_exact: Dict[int, str] = {}
    sym_sorted: List[Tuple[int, str]] = []

    if objdump_bin:
        try:
            out = subprocess.check_output(
                [objdump_bin, "-d", str(elf_path)],
                text=True,
                stderr=subprocess.DEVNULL,
            )
            for line in out.splitlines():
                m = re.match(r"^\s*([0-9A-Fa-f]+)\s+<([^>]+)>:\s*$", line)
                if not m:
                    continue
                addr = int(m.group(1), 16)
                name = m.group(2).strip()
                if name:
                    sym_exact[addr] = name
        except Exception:
            pass

    if nm_bin:
        try:
            out = subprocess.check_output(
                [nm_bin, "-n", "--defined-only", str(elf_path)],
                text=True,
                stderr=subprocess.DEVNULL,
            )
            for line in out.splitlines():
                m = re.match(r"^\s*([0-9A-Fa-f]+)\s+[A-Za-z]\s+(\S+)\s*$", line)
                if not m:
                    continue
                addr = int(m.group(1), 16)
                name = m.group(2).strip()
                if not name:
                    continue
                sym_exact.setdefault(addr, name)
                sym_sorted.append((addr, name))
        except Exception:
            pass

    if not sym_sorted and sym_exact:
        sym_sorted = sorted((addr, name) for addr, name in sym_exact.items())
    else:
        sym_sorted.sort(key=lambda x: x[0])
    return sym_exact, sym_sorted


def _symbolize_asm(text: str, sym_exact: Dict[int, str]) -> str:
    if not text or not sym_exact:
        return text

    def repl(m: re.Match[str]) -> str:
        tok = m.group(0)
        try:
            val = int(tok, 16)
        except Exception:
            return tok.upper()
        name = sym_exact.get(val)
        if name:
            return name
        return _fmt_hex(val)

    return re.sub(r"0x[0-9A-Fa-f]+", repl, text)


def _pc_label(pc: int, sym_sorted: List[Tuple[int, str]], sym_addrs: List[int]) -> str:
    if pc < 0:
        return "?"
    pc_txt = _fmt_hex(pc)
    if not sym_sorted:
        return pc_txt
    idx = bisect.bisect_right(sym_addrs, int(pc)) - 1
    if idx < 0:
        return pc_txt
    base, name = sym_sorted[idx]
    off = int(pc) - base
    if off == 0:
        return f"{pc_txt} <{name}>"
    return f"{pc_txt} <{name}+0x{off:X}>"


def _kind_text(kind: int) -> str:
    if kind == 1:
        return "flush"
    if kind == 2:
        return "trap"
    if kind == 3:
        return "replay"
    if kind == 4:
        return "template_child"
    return "normal"


def _esc(text: str) -> str:
    return text.replace("\t", " ").replace("\n", " ").replace("\r", " ")


def _esc_detail(text: str) -> str:
    # Keep detail fields single-line in JSONL labels for stable viewer rendering.
    return text.replace("\t", " ").replace("\r", " ").replace("\n", "\\n")


def _stage_group(stage: str) -> str:
    s = stage.upper()
    if s.startswith("F") or s == "IB":
        return "frontend"
    if s in {"D1", "D2", "D3", "IQ", "S1", "S2", "P1", "I1", "I2", "E1", "E2", "E3", "E4", "W1", "W2"}:
        return "backend"
    if s in {"LIQ", "LHQ", "STQ", "SCB", "MDB", "L1D"}:
        return "lsu"
    if s in {"BISQ", "BCTRL", "TMU", "TMA", "CUBE", "VEC", "TAU", "BROB"}:
        return "block"
    if s in {"ROB", "CMT", "FLS", "XCHK"}:
        return "retire"
    return "other"


def _stage_color(stage: str) -> str:
    s = stage.upper()
    if s in {"FLS", "XCHK"}:
        return "#EF4444"
    if s == "CMT":
        return "#22C55E"
    if s in {"ROB", "BROB"}:
        return "#F97316"
    if s.startswith("F") or s == "IB":
        return "#38BDF8"
    if s in {"D1", "D2", "D3", "IQ", "S1", "S2"}:
        return "#A78BFA"
    if s in {"P1", "I1", "I2", "E1", "E2", "E3", "E4", "W1", "W2"}:
        return "#34D399"
    if s in {"LIQ", "LHQ", "STQ", "SCB", "MDB", "L1D"}:
        return "#FBBF24"
    return "#9CA3AF"


def _contract_id(stage_ids: List[str], lane_ids: List[str], row_schema: List[Tuple[int, str]]) -> str:
    seed = (
        f"{LINXTRACE_PIPELINE_SCHEMA_ID}|"
        f"{','.join(stage_ids)}|"
        f"{','.join(lane_ids)}|"
        f"{';'.join(f'{rid}:{kind}' for rid, kind in row_schema)}|"
        "linxtrace.v1"
    )
    h = 1469598103934665603
    for b in seed.encode("utf-8"):
        h ^= b
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return f"{LINXTRACE_PIPELINE_SCHEMA_ID}-{h:016X}"


def _extract_opcode(asm: str, fallback: str = "uop") -> str:
    txt = (asm or "").strip()
    if not txt:
        return fallback
    m = re.match(r"^([A-Za-z0-9_.]+)", txt)
    if m:
        return m.group(1)
    return fallback


def _strip_asm_pc_prefix(asm: str) -> str:
    txt = (asm or "").strip()
    if not txt:
        return txt
    return re.sub(r"^\s*(?:0x)?[0-9A-Fa-f]+(?:\s+<[^>]+>)?:\s*", "", txt)


def _reg_name(reg: int) -> str:
    return f"r{int(reg)}"


def _mem_width_suffix(size: int) -> str:
    s = int(size)
    if s <= 0:
        return ""
    if s == 1:
        return "b"
    if s == 2:
        return "h"
    if s == 4:
        return "w"
    if s == 8:
        return "d"
    return str(s)


def _addr_expr(base_valid: bool, base_reg: int, base_data: int, addr: int) -> str:
    if base_valid:
        off = int(addr) - int(base_data)
        if off == 0:
            return f"[{_reg_name(base_reg)}]"
        return f"[{_reg_name(base_reg)}, {off:+d}]"
    return f"[0x{int(addr):x}]"


def _int_field(cmt: dict, key: str, default: int = 0) -> int:
    try:
        return int(cmt.get(key, default))
    except Exception:
        return default


def _int_optional(cmt: dict, key: str) -> Optional[int]:
    if key not in cmt:
        return None
    try:
        v = int(cmt.get(key))
    except Exception:
        return None
    if v == -1:
        return None
    return v


def _emit_pseudo_asm(opcode: str, cmt: Optional[dict]) -> str:
    op = (opcode or "uop").strip()
    if not cmt:
        return op.lower()

    src0_valid = _int_field(cmt, "src0_valid", 0) != 0
    src1_valid = _int_field(cmt, "src1_valid", 0) != 0
    dst_valid = _int_field(cmt, "dst_valid", 0) != 0
    mem_valid = _int_field(cmt, "mem_valid", 0) != 0
    mem_is_store = _int_field(cmt, "mem_is_store", 0) != 0
    mem_addr = _int_field(cmt, "mem_addr", 0)
    mem_wdata = _int_field(cmt, "mem_wdata", 0)
    mem_size = _int_field(cmt, "mem_size", 0)
    src0_reg = _int_field(cmt, "src0_reg", 0)
    src0_data = _int_field(cmt, "src0_data", 0)
    src1_reg = _int_field(cmt, "src1_reg", 0)
    src1_data = _int_field(cmt, "src1_data", 0)
    dst_reg = _int_field(cmt, "dst_reg", 0)
    dst_data = _int_field(cmt, "dst_data", 0)

    if mem_valid:
        suf = _mem_width_suffix(mem_size)
        if mem_is_store:
            src_txt = _reg_name(src0_reg) if src0_valid else f"0x{mem_wdata:x}"
            addr_txt = _addr_expr(src1_valid, src1_reg, src1_data, mem_addr)
            return f"st{suf} {src_txt}, {addr_txt}"
        dst_txt = _reg_name(dst_reg) if dst_valid else "r?"
        addr_txt = _addr_expr(src0_valid, src0_reg, src0_data, mem_addr)
        return f"ld{suf} {addr_txt}, ->{dst_txt}"

    if dst_valid:
        dst_txt = _reg_name(dst_reg)
        if src0_valid and src1_valid:
            return f"{op.lower()} {_reg_name(src0_reg)}, {_reg_name(src1_reg)}, ->{dst_txt}"
        if src0_valid:
            delta = int(dst_data) - int(src0_data)
            if delta > 0:
                return f"addi {_reg_name(src0_reg)}, {delta}, ->{dst_txt}"
            if delta < 0:
                return f"subi {_reg_name(src0_reg)}, {-delta}, ->{dst_txt}"
            return f"mov {_reg_name(src0_reg)}, ->{dst_txt}"
        return f"{op.lower()} ->{dst_txt}"

    return op.lower()


def _format_uop_detail(u: "UopRow", opcode: str) -> str:
    lines: List[str] = []
    lines.append(f"uid=0x{u.uid:x}")
    if u.parent_uid:
        lines.append(f"parent=0x{u.parent_uid:x}")
    if u.block_uid:
        lines.append(f"block={u.block_uid}")
    if u.block_bid:
        lines.append(f"bid=0x{u.block_bid:x}")
    if u.commit_seq is not None:
        lines.append(f"seq={u.commit_seq}")
    lines.append(f"op={opcode}")

    cmt = u.commit_payload
    if not cmt:
        return "\n".join(lines)

    src0_valid = _int_optional(cmt, "src0_valid") not in (None, 0)
    src1_valid = _int_optional(cmt, "src1_valid") not in (None, 0)
    dst_valid = _int_optional(cmt, "dst_valid") not in (None, 0)
    wb_valid = _int_optional(cmt, "wb_valid") not in (None, 0)
    mem_valid = _int_optional(cmt, "mem_valid") not in (None, 0)
    trap_valid = _int_optional(cmt, "trap_valid") not in (None, 0)

    if src0_valid:
        s0r = _int_optional(cmt, "src0_reg")
        s0d = _int_optional(cmt, "src0_data")
        if s0r is not None and s0d is not None:
            lines.append(f"src0={_reg_name(s0r)} 0x{s0d:x}")
    if src1_valid:
        s1r = _int_optional(cmt, "src1_reg")
        s1d = _int_optional(cmt, "src1_data")
        if s1r is not None and s1d is not None:
            lines.append(f"src1={_reg_name(s1r)} 0x{s1d:x}")

    if dst_valid:
        dr = _int_optional(cmt, "dst_reg")
        dd = _int_optional(cmt, "dst_data")
        if dr is not None:
            if dd is not None:
                lines.append(f"dst={_reg_name(dr)} 0x{dd:x}")
            else:
                lines.append(f"dst={_reg_name(dr)}")

    if wb_valid:
        wr = _int_optional(cmt, "wb_rd")
        wd = _int_optional(cmt, "wb_data")
        if wr is not None and wd is not None:
            lines.append(f"wb={_reg_name(wr)} 0x{wd:x}")

    if mem_valid:
        mem_is_store = _int_optional(cmt, "mem_is_store") not in (None, 0)
        maddr = _int_optional(cmt, "mem_addr")
        msz = _int_optional(cmt, "mem_size")
        mw = _int_optional(cmt, "mem_wdata")
        mr = _int_optional(cmt, "mem_rdata")
        suf = _mem_width_suffix(msz if msz is not None else 0)
        if mem_is_store:
            msg = f"mem=st{suf}"
            if maddr is not None:
                msg += f" @0x{maddr:x}"
            if mw is not None:
                msg += f" data=0x{mw:x}"
            lines.append(msg)
        else:
            msg = f"mem=ld{suf}"
            if maddr is not None:
                msg += f" @0x{maddr:x}"
            if mr is not None:
                msg += f" data=0x{mr:x}"
            lines.append(msg)

    if trap_valid:
        cause = _int_optional(cmt, "trap_cause")
        if cause is not None:
            lines.append(f"trap=0x{cause:x}")
        else:
            lines.append("trap=1")

    return "\n".join(lines)


def _format_block_detail(blk: "BlockRow", open_asm: str) -> str:
    lines: List[str] = []
    if blk.block_bid:
        lines.append(f"bid=0x{blk.block_bid:x}")
    if blk.open_seq is not None:
        lines.append(f"open_seq={blk.open_seq}")
    if blk.close_seq is not None:
        lines.append(f"close_seq={blk.close_seq}")
    if blk.open_cycle is not None:
        lines.append(f"open_cycle={blk.open_cycle}")
    if blk.close_cycle is not None:
        lines.append(f"close_cycle={blk.close_cycle}")
    if open_asm:
        lines.append(f"open_asm={open_asm}")
    return "\n".join(lines)


def _classify_block_open(asm: str) -> Tuple[str, str]:
    up = (asm or "").upper()
    if "BSTART" in up:
        if "CALL" in up:
            return ("BSTART", "CALL")
        if "RET" in up:
            return ("BSTART", "RET")
        if "COND" in up or "B.NZ" in up or "B.Z" in up or "B_NZ" in up or "B_Z" in up:
            return ("BSTART", "COND")
        if "JMP" in up or "JUMP" in up or "TGT" in up:
            return ("BSTART", "JUMP")
        return ("BSTART", "UNKNOWN")
    if up.startswith("FENTRY") or up.startswith("FEXIT") or up.startswith("FRET"):
        return ("TEMPLATE", "TEMPLATE")
    return ("BLOCK", "N/A")


def _is_generated_uop(kind: str, parent_uid: int, asm: str) -> bool:
    if kind.lower() in ("template_child", "replay"):
        return True
    up = (asm or "").upper()
    if parent_uid != 0 and (up.startswith("FENTRY") or up.startswith("FEXIT") or up.startswith("FRET")):
        return True
    return False


@dataclasses.dataclass
class UopRow:
    uid: int
    parent_uid: int = 0
    core_id: int = 0
    block_uid: int = 0
    block_bid: int = 0
    first_cycle: int = 0
    first_seen: bool = False
    first_seq: Optional[int] = None
    commit_seq: Optional[int] = None
    commit_cycle: Optional[int] = None
    commit_slot: int = 0
    pc: int = 0
    op_name: str = "uop"
    kind: str = "normal"
    flush_cycle: Optional[int] = None
    trap_valid: bool = False
    commit_payload: Optional[dict] = None
    occ: List[dict] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class BlockRow:
    block_uid: int
    core_id: int = 0
    block_bid: int = 0
    open_seq: Optional[int] = None
    open_cycle: Optional[int] = None
    open_pc: int = 0
    close_seq: Optional[int] = None
    close_cycle: Optional[int] = None
    close_pc: int = 0
    close_kind: str = "close"


def _best_block_for_uop(row: UopRow, blocks: Dict[int, BlockRow]) -> int:
    core = row.core_id
    seq_key = row.commit_seq if row.commit_seq is not None else (1 << 60)
    cyc_key = row.first_cycle
    best_uid = 0
    best_seq = -1
    best_cyc = -1
    for buid, blk in blocks.items():
        if blk.core_id != core:
            continue
        if blk.open_seq is not None:
            if blk.open_seq > seq_key:
                continue
            if blk.close_seq is not None and blk.close_seq < seq_key:
                continue
            if blk.open_seq > best_seq:
                best_seq = blk.open_seq
                best_uid = buid
        elif blk.open_cycle is not None:
            if blk.open_cycle > cyc_key:
                continue
            if blk.close_cycle is not None and blk.close_cycle < cyc_key:
                continue
            if blk.open_cycle > best_cyc:
                best_cyc = blk.open_cycle
                best_uid = buid
    return best_uid


def main() -> int:
    ap = argparse.ArgumentParser(description="Build LinxTrace v1 (block+uop rows) from LinxCore raw event trace.")
    ap.add_argument("--raw", required=True, help="Raw event JSONL from tb_linxcore_top.cpp (PYC_RAW_TRACE).")
    ap.add_argument("--out", required=True, help="Output .linxtrace.jsonl path.")
    ap.add_argument("--meta-out", default="", help="Optional output .linxtrace.meta.json path.")
    ap.add_argument("--map-report", default="", help="Optional mapping report JSON path.")
    ap.add_argument("--commit-text", default="", help="Optional commit text trace for asm labels.")
    ap.add_argument("--elf", default="", help="Optional ELF path used for symbolized labels.")
    args = ap.parse_args()

    raw_path = Path(args.raw)
    out_path = Path(args.out)
    if args.meta_out:
        meta_path = Path(args.meta_out)
    elif out_path.name.endswith(".linxtrace.jsonl"):
        meta_path = out_path.with_name(out_path.name.replace(".linxtrace.jsonl", ".linxtrace.meta.json"))
    else:
        meta_path = out_path.with_suffix(out_path.suffix + ".meta.json")
    map_path = Path(args.map_report) if args.map_report else out_path.with_suffix(".map.json")
    commit_text_path = Path(args.commit_text) if args.commit_text else None
    elf_path = Path(args.elf) if args.elf else None
    sym_exact, sym_sorted = _load_symbol_maps(elf_path)
    sym_addrs = [a for a, _ in sym_sorted]

    uops: Dict[int, UopRow] = {}
    blocks: Dict[int, BlockRow] = {}
    asm_by_uid: Dict[int, str] = {}
    asm_by_seq: Dict[int, str] = {}
    op_by_seq: Dict[int, str] = {}

    if commit_text_path is not None and commit_text_path.is_file():
        with commit_text_path.open("r", encoding="utf-8", errors="ignore") as cf:
            for line in cf:
                line = line.strip()
                if not line or line.startswith("#") or "uid=0x" not in line:
                    continue
                fields = {}
                for part in line.split("|"):
                    part = part.strip()
                    if "=" not in part:
                        continue
                    k, v = part.split("=", 1)
                    fields[k.strip()] = v.strip()
                uid_s = fields.get("uid", "")
                if not uid_s.lower().startswith("0x"):
                    continue
                try:
                    uid = int(uid_s, 16)
                except ValueError:
                    continue
                asm = fields.get("asm", "")
                if asm:
                    asm_by_uid[uid] = _strip_asm_pc_prefix(asm)
                seq_s = fields.get("seq", "")
                if seq_s:
                    try:
                        seq = int(seq_s, 10)
                        if asm:
                            asm_by_seq[seq] = _strip_asm_pc_prefix(asm)
                        op_v = fields.get("op", "")
                        if op_v:
                            op_by_seq[seq] = op_v
                    except ValueError:
                        pass

    with raw_path.open("r", encoding="utf-8", errors="ignore") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            rtype = str(rec.get("type", ""))
            if rtype == "occ":
                uid = int(rec.get("uop_uid", 0))
                if uid == 0:
                    continue
                row = uops.get(uid)
                if row is None:
                    row = UopRow(uid=uid)
                    uops[uid] = row
                row.parent_uid = int(rec.get("parent_uid", row.parent_uid))
                row.core_id = int(rec.get("core_id", row.core_id))
                b = int(rec.get("block_uid", 0))
                if b:
                    row.block_uid = b
                bid = int(rec.get("block_bid", 0))
                if bid:
                    row.block_bid = bid
                cyc = int(rec.get("cycle", 0))
                if not row.first_seen:
                    row.first_seen = True
                    row.first_cycle = cyc
                row.occ.append(rec)
                stage = str(rec.get("stage", "")).upper()
                kind = int(rec.get("kind", 0))
                if stage == "FLS" or kind in (1, 3):
                    if row.flush_cycle is None:
                        row.flush_cycle = cyc
                    row.kind = "flush"
            elif rtype == "commit":
                uid = int(rec.get("uop_uid", 0))
                cyc = int(rec.get("cycle", 0))
                seq = int(rec.get("seq", 0))
                core = int(rec.get("core_id", 0))
                buid = int(rec.get("block_uid", 0))
                bid = int(rec.get("block_bid", 0))
                if uid != 0:
                    row = uops.get(uid)
                    if row is None:
                        row = UopRow(uid=uid)
                        uops[uid] = row
                    row.parent_uid = int(rec.get("parent_uid", row.parent_uid))
                    row.core_id = core
                    if buid:
                        row.block_uid = buid
                    if bid:
                        row.block_bid = bid
                    if not row.first_seen:
                        row.first_seen = True
                        row.first_cycle = cyc
                    if row.first_seq is None:
                        row.first_seq = seq
                    row.commit_seq = seq
                    row.commit_cycle = cyc
                    row.commit_slot = int(rec.get("slot", 0))
                    row.pc = int(rec.get("pc", row.pc))
                    row.op_name = str(rec.get("op_name", row.op_name))
                    row.trap_valid = bool(int(rec.get("trap_valid", 0)))
                    row.kind = "trap" if row.trap_valid else row.kind
                    row.commit_payload = rec
                if buid:
                    blk = blocks.get(buid)
                    if blk is None:
                        blk = BlockRow(block_uid=buid)
                        blocks[buid] = blk
                    blk.core_id = core
                    if bid:
                        blk.block_bid = bid
                    if int(rec.get("is_bstart", 0)) != 0:
                        if blk.open_seq is None or seq < blk.open_seq:
                            blk.open_seq = seq
                            blk.open_cycle = cyc
                            blk.open_pc = int(rec.get("pc", 0))
                    if int(rec.get("is_bstop", 0)) != 0:
                        if blk.close_seq is None or seq <= blk.close_seq:
                            blk.close_seq = seq
                            blk.close_cycle = cyc
                            blk.close_pc = int(rec.get("pc", 0))
                            blk.close_kind = "close"
            elif rtype == "blk_evt":
                buid = int(rec.get("block_uid", 0))
                if buid == 0:
                    continue
                blk = blocks.get(buid)
                if blk is None:
                    blk = BlockRow(block_uid=buid)
                    blocks[buid] = blk
                blk.core_id = int(rec.get("core_id", blk.core_id))
                kind = str(rec.get("kind", "open"))
                cyc = int(rec.get("cycle", 0))
                seq = int(rec.get("seq", 0))
                pc = int(rec.get("pc", 0))
                bid = int(rec.get("block_bid", 0))
                if bid:
                    blk.block_bid = bid
                if kind == "open":
                    if blk.open_seq is None or seq < blk.open_seq:
                        blk.open_seq = seq
                        blk.open_cycle = cyc
                        blk.open_pc = pc
                elif kind in ("close", "fault"):
                    if blk.close_seq is None or seq <= blk.close_seq:
                        blk.close_seq = seq
                        blk.close_cycle = cyc
                        blk.close_pc = pc
                        blk.close_kind = kind

    for row in uops.values():
        if row.block_uid == 0:
            row.block_uid = _best_block_for_uop(row, blocks)
        if row.block_bid == 0 and row.block_uid != 0:
            blk = blocks.get(row.block_uid)
            if blk is not None and blk.block_bid:
                row.block_bid = blk.block_bid
        if row.commit_payload is not None and row.pc == 0:
            row.pc = int(row.commit_payload.get("pc", 0))

    for row in uops.values():
        if row.block_uid != 0 and row.block_bid != 0:
            blk = blocks.get(row.block_uid)
            if blk is not None and blk.block_bid == 0:
                blk.block_bid = row.block_bid

    max_key = (1 << 60)
    block_seq_open: Dict[int, int] = {}
    for buid, blk in blocks.items():
        block_seq_open[buid] = blk.open_seq if blk.open_seq is not None else max_key

    block_rows = sorted(
        [b for b in blocks.values() if b.open_cycle is not None or b.open_seq is not None],
        key=lambda b: (b.core_id, b.open_seq if b.open_seq is not None else max_key, 0, b.block_uid, 0),
    )
    uop_rows = sorted(
        uops.values(),
        key=lambda u: (
            u.core_id,
            block_seq_open.get(u.block_uid, max_key),
            1,
            u.commit_seq if u.commit_seq is not None else max_key,
            u.uid,
        ),
    )

    ordered_rows: List[Tuple[Tuple[int, int, int, int, int], str, object]] = []
    for blk in block_rows:
        ordered_rows.append(
            (
                (
                    blk.core_id,
                    blk.open_seq if blk.open_seq is not None else max_key,
                    0,
                    blk.block_uid,
                    0,
                ),
                "block",
                blk,
            )
        )
    for u in uop_rows:
        ordered_rows.append(
            (
                (
                    u.core_id,
                    block_seq_open.get(u.block_uid, max_key),
                    1,
                    u.commit_seq if u.commit_seq is not None else max_key,
                    u.uid,
                ),
                "uop",
                u,
            )
        )
    ordered_rows.sort(key=lambda x: x[0])

    row_to_kid: Dict[Tuple[str, int], int] = {}
    next_kid = 1
    for _, row_kind, row_obj in ordered_rows:
        if row_kind == "block":
            blk = row_obj
            row_to_kid[("block", blk.block_uid)] = next_kid
        else:
            u = row_obj
            row_to_kid[("uop", u.uid)] = next_kid
        next_kid += 1

    actions: Dict[int, List[tuple]] = collections.defaultdict(list)
    min_cycle = math.inf
    max_cycle = 0

    def add_action(cycle: int, action: tuple) -> None:
        nonlocal min_cycle, max_cycle
        actions[cycle].append(action)
        if cycle < min_cycle:
            min_cycle = cycle
        if cycle > max_cycle:
            max_cycle = cycle

    for u in uop_rows:
        kid = row_to_kid[("uop", u.uid)]
        terminal_cycle: Optional[int] = None
        terminal_stage = ""
        if u.commit_cycle is not None:
            terminal_cycle = u.commit_cycle
            terminal_stage = "CMT"
        elif u.flush_cycle is not None:
            terminal_cycle = u.flush_cycle
            terminal_stage = "FLS"
        for ev in u.occ:
            cyc = int(ev.get("cycle", 0))
            if terminal_cycle is not None and cyc > terminal_cycle:
                continue
            core = int(ev.get("core_id", u.core_id))
            lane = int(ev.get("lane", 0))
            lane_tok = f"c{core}.l{lane}"
            stage = str(ev.get("stage", "UNK")).upper()
            if terminal_cycle is not None and cyc == terminal_cycle and terminal_stage and stage not in (
                terminal_stage,
                "XCHK",
            ):
                # Keep terminal cycle focused on final lifecycle markers.
                continue
            stall = int(ev.get("stall", 0))
            cause = str(ev.get("stall_cause", "0"))
            add_action(cyc, ("P", STAGE_RANK.get(stage, 999), kid, lane_tok, stage, stall, cause))
        if u.commit_cycle is not None:
            lane_tok = f"c{u.core_id}.l{u.commit_slot}"
            add_action(u.commit_cycle, ("P", STAGE_RANK.get("CMT", 999), kid, lane_tok, "CMT", 0, "0"))
            add_action(u.commit_cycle, ("R", 0 if not u.trap_valid else 1, kid))
        elif u.flush_cycle is not None:
            lane_tok = f"c{u.core_id}.l0"
            add_action(u.flush_cycle, ("P", STAGE_RANK.get("FLS", 999), kid, lane_tok, "FLS", 1, "flush"))
            add_action(u.flush_cycle, ("R", 1, kid))
        else:
            eof_cycle = int(max_cycle) + 1
            lane_tok = f"c{u.core_id}.l0"
            add_action(eof_cycle, ("P", STAGE_RANK.get("FLS", 999), kid, lane_tok, "FLS", 1, "eof"))
            add_action(eof_cycle, ("R", 1, kid))

    for blk in block_rows:
        kid = row_to_kid[("block", blk.block_uid)]
        core = blk.core_id
        close_cycle_eff: Optional[int] = blk.close_cycle
        if blk.open_cycle is not None and close_cycle_eff is not None and close_cycle_eff <= blk.open_cycle:
            # LinxTrace viewer requires positive residency width.
            # If a block opens/closes in one cycle, push close one cycle later.
            close_cycle_eff = blk.open_cycle + 1
        if blk.open_cycle is not None:
            end_cycle = (close_cycle_eff - 1) if close_cycle_eff is not None else max_cycle
            if end_cycle < blk.open_cycle:
                end_cycle = blk.open_cycle
            for cyc in range(int(blk.open_cycle), int(end_cycle) + 1):
                cause = "active"
                if cyc == blk.open_cycle:
                    cause = "open"
                elif close_cycle_eff is not None and cyc == close_cycle_eff:
                    cause = blk.close_kind
                add_action(cyc, ("P", STAGE_RANK.get("BROB", 999), kid, f"c{core}.blk", "BROB", 0, cause))
        if close_cycle_eff is not None:
            add_action(close_cycle_eff, ("P", STAGE_RANK.get("CMT", 999), kid, f"c{core}.blk", "CMT", 0, blk.close_kind))
            add_action(close_cycle_eff, ("R", 1 if blk.close_kind == "fault" else 0, kid))
        else:
            # Strict LinxTrace contract requires a terminal retire event per row.
            eof_close_cycle = int(max_cycle) + 1
            add_action(eof_close_cycle, ("R", 1, kid))

    p_count_by_kid: Dict[int, int] = collections.defaultdict(int)
    block_stage_set_by_kid: Dict[int, set] = collections.defaultdict(set)
    for cyc_actions in actions.values():
        for action in cyc_actions:
            if action[0] != "P":
                continue
            _, _, kid, _lane_tok, stage, _stall, _cause = action
            p_count_by_kid[int(kid)] += 1
            block_stage_set_by_kid[int(kid)].add(str(stage))

    for _, row_kind, row_obj in ordered_rows:
        if row_kind == "block":
            kid = row_to_kid[("block", row_obj.block_uid)]
            stages = block_stage_set_by_kid.get(kid, set())
            if p_count_by_kid.get(kid, 0) == 0:
                raise SystemExit(f"strict-linxtrace: block row has no occupancy records (block_uid={row_obj.block_uid}, row_id={kid})")
            if "BROB" not in stages:
                raise SystemExit(
                    f"strict-linxtrace: block row missing BROB lifecycle (block_uid={row_obj.block_uid}, row_id={kid}, stages={sorted(stages)})"
                )
        else:
            kid = row_to_kid[("uop", row_obj.uid)]
            if p_count_by_kid.get(kid, 0) == 0:
                raise SystemExit(f"strict-linxtrace: uop row has no occupancy records (uid=0x{row_obj.uid:x}, row_id={kid})")

    if min_cycle is math.inf:
        min_cycle = 0

    out_path.parent.mkdir(parents=True, exist_ok=True)
    meta_path.parent.mkdir(parents=True, exist_ok=True)

    row_catalog: List[dict] = []
    lane_ids: set[str] = set()
    stage_ids: set[str] = set()
    row_schema: List[Tuple[int, str]] = []

    with out_path.open("w", encoding="utf-8") as out:
        def emit(rec: dict) -> None:
            out.write(json.dumps(rec, sort_keys=True) + "\n")

        for _, row_kind, row_obj in ordered_rows:
            if row_kind == "block":
                blk = row_obj
                row_id = row_to_kid[("block", blk.block_uid)]
                block_row_uid = (1 << 63) | (blk.block_uid & ((1 << 63) - 1))
                uid_hex = f"0x{block_row_uid:x}"
                open_pc = _fmt_hex(blk.open_pc) if blk.open_pc >= 0 else ""
                open_asm_raw = asm_by_seq.get(blk.open_seq or -1, "")
                open_asm = _symbolize_asm(open_asm_raw, sym_exact)
                block_type, branch_type = _classify_block_open(open_asm_raw)
                block_type_txt = block_type if branch_type in ("N/A", "UNKNOWN") else f"{block_type}.{branch_type}"
                parts: List[str] = []
                if open_pc:
                    parts.append(open_pc)
                parts.append("BLOCK")
                if block_type_txt:
                    parts.append(block_type_txt)
                if open_asm:
                    parts.append(open_asm)
                left_label = " ".join(parts)
                detail = _format_block_detail(blk, open_asm)
                emit(
                    {
                        "type": "OP_DEF",
                        "cycle": int(min_cycle),
                        "row_id": row_id,
                        "row_kind": "block",
                        "core_id": blk.core_id,
                        "uop_uid": "0x0",
                        "parent_uid": "0x0",
                        "block_uid": f"0x{blk.block_uid:x}",
                        "kind": "block",
                    }
                )
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "label_type": "left", "text": left_label})
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "label_type": "detail", "text": detail})
                row_catalog.append(
                    {
                        "row_id": row_id,
                        "row_kind": "block",
                        "core_id": blk.core_id,
                        "block_uid": f"0x{blk.block_uid:x}",
                        "uop_uid": "0x0",
                        "left_label": left_label,
                        "detail_defaults": detail,
                    }
                )
                row_schema.append((row_id, "block"))
            else:
                u = row_obj
                row_id = row_to_kid[("uop", u.uid)]
                uid_hex = f"0x{u.uid:x}"
                parent_hex = f"0x{u.parent_uid:x}"
                kind = u.kind
                asm_raw = asm_by_uid.get(u.uid, "")
                if not asm_raw:
                    asm_raw = asm_by_seq.get(u.commit_seq or -1, "")
                asm = _symbolize_asm(asm_raw, sym_exact)
                op_name = (
                    u.op_name
                    if u.op_name
                    else (op_by_seq.get(u.commit_seq or -1, "uop") if u.commit_seq is not None else "uop")
                )
                opcode = _extract_opcode(asm, op_name)
                pseudo_asm = _emit_pseudo_asm(opcode, u.commit_payload)
                if _is_generated_uop(kind, u.parent_uid, asm):
                    seq_s = str(u.commit_seq) if u.commit_seq is not None else "-"
                    left_label = f"GEN seq={seq_s} op={pseudo_asm}"
                else:
                    asm_txt = asm if asm else pseudo_asm
                    left_label = f"{_pc_label(u.pc, sym_sorted, sym_addrs)}: {asm_txt}"
                detail = _format_uop_detail(u, opcode)
                emit(
                    {
                        "type": "OP_DEF",
                        "cycle": int(min_cycle),
                        "row_id": row_id,
                        "row_kind": "uop",
                        "core_id": u.core_id,
                        "uop_uid": uid_hex,
                        "parent_uid": parent_hex,
                        "block_uid": f"0x{u.block_uid:x}",
                        "kind": kind,
                    }
                )
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "label_type": "left", "text": left_label})
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "label_type": "detail", "text": detail})
                row_catalog.append(
                    {
                        "row_id": row_id,
                        "row_kind": "uop",
                        "core_id": u.core_id,
                        "block_uid": f"0x{u.block_uid:x}",
                        "uop_uid": uid_hex,
                        "left_label": left_label,
                        "detail_defaults": detail,
                    }
                )
                row_schema.append((row_id, "uop"))

        retired: set[int] = set()
        for cyc in sorted(actions.keys()):
            p_actions = [a for a in actions[cyc] if a[0] == "P"]
            p_actions.sort(key=lambda a: (a[1], a[2], a[3]))
            seen_p = set()
            for _, _, row_id, lane_tok, stage, stall, cause in p_actions:
                p_key = (row_id, lane_tok, stage, stall, cause)
                if p_key in seen_p:
                    continue
                seen_p.add(p_key)
                lane = str(lane_tok)
                stage_name = str(stage)
                lane_ids.add(lane)
                stage_ids.add(stage_name)
                emit(
                    {
                        "type": "OCC",
                        "cycle": int(cyc),
                        "row_id": int(row_id),
                        "lane_id": lane,
                        "stage_id": stage_name,
                        "stall": int(stall),
                        "cause": str(cause),
                    }
                )
                if stage_name == "XCHK":
                    emit(
                        {
                            "type": "XCHECK",
                            "cycle": int(cyc),
                            "row_id": int(row_id),
                            "status": "mismatch",
                            "detail": str(cause),
                        }
                    )
            r_actions = [a for a in actions[cyc] if a[0] == "R"]
            r_actions.sort(key=lambda a: a[2])
            for _, rtype, row_id in r_actions:
                if row_id in retired:
                    continue
                retired.add(row_id)
                emit(
                    {
                        "type": "RETIRE",
                        "cycle": int(cyc),
                        "row_id": int(row_id),
                        "status": "ok" if int(rtype) == 0 else "terminal",
                        "status_code": int(rtype),
                    }
                )

        for blk in block_rows:
            if blk.open_cycle is not None:
                emit(
                    {
                        "type": "BLOCK_EVT",
                        "cycle": int(blk.open_cycle),
                        "kind": "open",
                        "block_uid": f"0x{blk.block_uid:x}",
                        "bid": f"0x{blk.block_bid:x}",
                        "seq": int(blk.open_seq or 0),
                        "pc": int(blk.open_pc),
                        "core_id": int(blk.core_id),
                    }
                )
            if blk.close_cycle is not None:
                emit(
                    {
                        "type": "BLOCK_EVT",
                        "cycle": int(blk.close_cycle),
                        "kind": str(blk.close_kind),
                        "block_uid": f"0x{blk.block_uid:x}",
                        "bid": f"0x{blk.block_bid:x}",
                        "seq": int(blk.close_seq or 0),
                        "pc": int(blk.close_pc),
                        "core_id": int(blk.core_id),
                    }
                )

    stage_list = sorted(stage_ids, key=lambda s: (STAGE_RANK.get(s, 999), s))
    lane_list = sorted(lane_ids)
    contract_id = _contract_id(stage_list, lane_list, sorted(row_schema, key=lambda x: x[0]))

    stage_catalog = [
        {"stage_id": s, "label": s, "color": _stage_color(s), "group": _stage_group(s)} for s in stage_list
    ]
    lane_catalog = [{"lane_id": l, "label": l} for l in lane_list]
    meta_obj = {
        "format": "linxtrace.v1",
        "contract_id": contract_id,
        "pipeline_schema_id": LINXTRACE_PIPELINE_SCHEMA_ID,
        "stage_order_csv": LINXTRACE_STAGE_ORDER_CSV,
        "stage_catalog": stage_catalog,
        "lane_catalog": lane_catalog,
        "row_catalog": row_catalog,
        "render_prefs": {"theme": "high-contrast", "show_symbols": True},
    }
    with meta_path.open("w", encoding="utf-8") as mf:
        json.dump(meta_obj, mf, indent=2, sort_keys=False)
        mf.write("\n")

    map_path.parent.mkdir(parents=True, exist_ok=True)
    uid_to_kid = {str(u.uid): row_to_kid[("uop", u.uid)] for u in uop_rows}
    block_to_kid = {str(b.block_uid): row_to_kid[("block", b.block_uid)] for b in block_rows}
    block_to_bid = {str(b.block_uid): b.block_bid for b in block_rows if b.block_bid}
    with map_path.open("w", encoding="utf-8") as mf:
        json.dump(
            {
                "raw": str(raw_path),
                "linxtrace": str(out_path),
                "meta": str(meta_path),
                "uid_to_kid": uid_to_kid,
                "block_uid_to_kid": block_to_kid,
                "block_uid_to_bid": block_to_bid,
                "uop_rows": len(uop_rows),
                "block_rows": len(block_rows),
            },
            mf,
            indent=2,
            sort_keys=True,
        )
        mf.write("\n")

    print(f"linxtrace-built {out_path} meta={meta_path} uop_rows={len(uop_rows)} block_rows={len(block_rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
