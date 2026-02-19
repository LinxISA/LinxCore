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
    if blk.resolved_seq is not None:
        lines.append(f"resolved_seq={blk.resolved_seq}")
    if blk.retired_seq is not None:
        lines.append(f"retired_seq={blk.retired_seq}")
    if blk.open_cycle is not None:
        lines.append(f"open_cycle={blk.open_cycle}")
    if blk.resolved_cycle is not None:
        lines.append(f"resolved_cycle={blk.resolved_cycle}")
    if blk.retired_cycle is not None:
        lines.append(f"retired_cycle={blk.retired_cycle}")
    if blk.retired_kind and blk.retired_kind != "retired":
        lines.append(f"retired_kind={blk.retired_kind}")
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
    resolved_seq: Optional[int] = None
    resolved_cycle: Optional[int] = None
    resolved_pc: int = 0
    retired_seq: Optional[int] = None
    retired_cycle: Optional[int] = None
    retired_pc: int = 0
    retired_kind: str = "retired"
    close_diagnostic: str = ""


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
        terminal_seq = blk.retired_seq if blk.retired_seq is not None else blk.resolved_seq
        terminal_cycle = blk.retired_cycle if blk.retired_cycle is not None else blk.resolved_cycle
        if blk.open_seq is not None:
            if blk.open_seq > seq_key:
                continue
            if terminal_seq is not None and terminal_seq < seq_key:
                continue
            if blk.open_seq > best_seq:
                best_seq = blk.open_seq
                best_uid = buid
        elif blk.open_cycle is not None:
            if blk.open_cycle > cyc_key:
                continue
            if terminal_cycle is not None and terminal_cycle < cyc_key:
                continue
            if blk.open_cycle > best_cyc:
                best_cyc = blk.open_cycle
                best_uid = buid
    return best_uid


def _row_sid_block(core_id: int, block_uid: int) -> str:
    return f"c{int(core_id)}.blk.0x{int(block_uid):x}"


def _row_sid_uop(core_id: int, uid: int) -> str:
    return f"c{int(core_id)}.uop.0x{int(uid):x}"


def _row_sid_gen_uop(core_id: int, parent_uid: int, uid: int) -> str:
    return f"c{int(core_id)}.gen.0x{int(parent_uid):x}.0x{int(uid):x}"


def _row_sid_packet(core_id: int, uid: int) -> str:
    return f"c{int(core_id)}.pkt.0x{int(uid):x}"


def _is_packet_row(u: "UopRow") -> bool:
    if u.commit_cycle is not None:
        return False
    if not u.occ:
        return False
    saw_f1 = False
    for ev in u.occ:
        stage = str(ev.get("stage", "")).upper()
        if stage not in {"F0", "F1"}:
            return False
        if stage == "F1":
            saw_f1 = True
    return saw_f1


def _uop_entity_kind(u: UopRow, generated: bool) -> str:
    if _is_packet_row(u):
        return "fetch_packet"
    k = (u.kind or "normal").lower()
    if generated:
        if "template" in k:
            return "template_child"
        if "replay" in k:
            return "replay"
        return "gen_uop"
    if "template_parent" in k:
        return "template_parent"
    if "replay" in k:
        return "replay"
    return "uop"


def _uop_lifecycle_flags(u: UopRow) -> List[str]:
    flags: List[str] = []
    if u.commit_cycle is not None:
        flags.append("retired")
    if u.flush_cycle is not None:
        flags.append("flushed")
    if u.trap_valid:
        flags.append("trapped")
    if not flags:
        flags.append("inflight")
    return flags


def main() -> int:
    ap = argparse.ArgumentParser(description="Build LinxTrace v1 (block+uop rows) from LinxCore raw event trace.")
    ap.add_argument("--raw", required=True, help="Raw event JSONL from tb_linxcore_top.cpp (PYC_RAW_TRACE).")
    ap.add_argument("--out", required=True, help="Output .linxtrace path.")
    ap.add_argument("--meta-out", default="", help="Optional debug sidecar meta output path (non-canonical).")
    ap.add_argument("--map-report", default="", help="Optional mapping report JSON path.")
    ap.add_argument("--commit-text", default="", help="Optional commit text trace for asm labels.")
    ap.add_argument("--elf", default="", help="Optional ELF path used for symbolized labels.")
    args = ap.parse_args()

    raw_path = Path(args.raw)
    out_path = Path(args.out)
    meta_path = Path(args.meta_out) if args.meta_out else None
    map_path = Path(args.map_report) if args.map_report else out_path.with_name(out_path.name + ".map.json")
    commit_text_path = Path(args.commit_text) if args.commit_text else None
    elf_path = Path(args.elf) if args.elf else None
    sym_exact, sym_sorted = _load_symbol_maps(elf_path)
    sym_addrs = [a for a, _ in sym_sorted]

    uops: Dict[int, UopRow] = {}
    blocks: Dict[int, BlockRow] = {}
    block_last_commit: Dict[int, Tuple[int, int, int]] = {}
    block_uid_by_bid: Dict[int, int] = {}
    # Recover block identity for late/partial blk_evt rows (for example
    # retired events that may carry only seq in some runs).
    block_by_commit_seq: Dict[int, Tuple[int, int, int, int]] = {}
    orphan_block_retire_evts: List[Tuple[int, int, int, int]] = []
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
                        block_uid_by_bid[bid] = buid
                    block_by_commit_seq[seq] = (buid, bid, core, int(rec.get("pc", 0)))
                    block_last_commit[buid] = (seq, cyc, int(rec.get("pc", 0)))
                    if int(rec.get("is_bstart", 0)) != 0:
                        if blk.open_seq is None or seq < blk.open_seq:
                            blk.open_seq = seq
                            blk.open_cycle = cyc
                            blk.open_pc = int(rec.get("pc", 0))
                    if int(rec.get("is_bstop", 0)) != 0:
                        if blk.resolved_seq is None or seq <= blk.resolved_seq:
                            blk.resolved_seq = seq
                            blk.resolved_cycle = cyc
                            blk.resolved_pc = int(rec.get("pc", 0))
            elif rtype == "blk_evt":
                buid = int(rec.get("block_uid", 0))
                bid = int(rec.get("block_bid", 0))
                seq = int(rec.get("seq", 0))
                # Prefer explicit row identity from commit-seq map when present.
                # This fixes long-running block rows when retired blk_evt arrives
                # with zero uid/bid but valid seq.
                seq_map = block_by_commit_seq.get(seq)
                if seq_map is not None:
                    seq_buid, seq_bid, _seq_core, _seq_pc = seq_map
                    if buid == 0 and seq_buid != 0:
                        buid = seq_buid
                    if bid == 0 and seq_bid != 0:
                        bid = seq_bid
                if buid == 0 and bid != 0:
                    buid = block_uid_by_bid.get(bid, 0)
                kind = str(rec.get("kind", "open"))
                cyc = int(rec.get("cycle", 0))
                pc = int(rec.get("pc", 0))
                core_id = int(rec.get("core_id", 0))
                if buid == 0:
                    if kind == "retired":
                        orphan_block_retire_evts.append((cyc, seq, pc, core_id))
                    continue
                blk = blocks.get(buid)
                if blk is None:
                    blk = BlockRow(block_uid=buid)
                    blocks[buid] = blk
                blk.core_id = int(rec.get("core_id", blk.core_id))
                if bid:
                    blk.block_bid = bid
                    block_uid_by_bid[bid] = buid
                if kind == "open":
                    if blk.open_seq is None or seq < blk.open_seq:
                        blk.open_seq = seq
                        blk.open_cycle = cyc
                        blk.open_pc = pc
                elif kind in ("resolved", "close"):
                    if blk.resolved_seq is None or seq <= blk.resolved_seq:
                        blk.resolved_seq = seq
                        blk.resolved_cycle = cyc
                        blk.resolved_pc = pc
                elif kind == "retired":
                    if blk.retired_seq is None or seq <= blk.retired_seq:
                        blk.retired_seq = seq
                        blk.retired_cycle = cyc
                        blk.retired_pc = pc
                        blk.retired_kind = "retired"
                elif kind == "fault":
                    if blk.resolved_seq is None or seq <= blk.resolved_seq:
                        blk.resolved_seq = seq
                        blk.resolved_cycle = cyc
                        blk.resolved_pc = pc
                    if blk.retired_seq is None or seq <= blk.retired_seq:
                        blk.retired_seq = seq
                        blk.retired_cycle = cyc
                        blk.retired_pc = pc
                        blk.retired_kind = "fault"

    # Recover orphan retired block events (missing block_uid/bid in raw stream).
    # BROB retirement is in-order per core, so greedily map orphan retire events
    # to earliest unresolved blocks for that core.
    if orphan_block_retire_evts:
        events_by_core: Dict[int, List[Tuple[int, int, int]]] = {}
        for cyc, seq, pc, core_id in orphan_block_retire_evts:
            events_by_core.setdefault(core_id, []).append((cyc, seq, pc))
        for core_id, core_events in events_by_core.items():
            core_events.sort(key=lambda e: (e[1] if e[1] > 0 else (1 << 60), e[0], e[2]))
            candidates = [
                b
                for b in blocks.values()
                if b.core_id == core_id and b.retired_seq is None and (b.open_seq is not None or b.resolved_seq is not None)
            ]
            candidates.sort(
                key=lambda b: (
                    b.resolved_seq if b.resolved_seq is not None else (b.open_seq if b.open_seq is not None else (1 << 60)),
                    b.resolved_cycle if b.resolved_cycle is not None else (b.open_cycle if b.open_cycle is not None else (1 << 60)),
                    b.block_uid,
                )
            )
            evt_idx = 0
            for blk in candidates:
                min_seq = blk.resolved_seq if blk.resolved_seq is not None else (blk.open_seq if blk.open_seq is not None else 0)
                min_cyc = blk.resolved_cycle if blk.resolved_cycle is not None else (blk.open_cycle if blk.open_cycle is not None else 0)
                while evt_idx < len(core_events):
                    cyc, seq, pc = core_events[evt_idx]
                    evt_idx += 1
                    seq_ok = (seq <= 0) or (min_seq <= 0) or (seq >= min_seq)
                    cyc_ok = cyc >= min_cyc
                    if not (seq_ok and cyc_ok):
                        continue
                    blk.retired_seq = seq if seq > 0 else min_seq
                    blk.retired_cycle = cyc
                    blk.retired_pc = pc if pc != 0 else (blk.resolved_pc if blk.resolved_pc != 0 else blk.open_pc)
                    blk.retired_kind = "retired"
                    break

    for buid, (last_seq, last_cyc, last_pc) in block_last_commit.items():
        blk = blocks.get(buid)
        if blk is None:
            continue
        if blk.resolved_seq is None:
            blk.resolved_seq = last_seq
            blk.resolved_cycle = last_cyc
            blk.resolved_pc = last_pc
            blk.close_diagnostic = "missing_resolved_evt_closed_at_last_commit"
        # Strict close fallback: if retire was not emitted for this block in the
        # raw stream, close at resolved so the block row does not run to EOF.
        if blk.retired_seq is None and blk.resolved_seq is not None:
            blk.retired_seq = blk.resolved_seq
            blk.retired_cycle = blk.resolved_cycle
            blk.retired_pc = blk.resolved_pc
            blk.retired_kind = "resolved_fallback"
            if not blk.close_diagnostic:
                blk.close_diagnostic = "missing_retire_evt_closed_at_resolved"

    for blk in blocks.values():
        if blk.resolved_seq is None and blk.open_seq is not None:
            blk.resolved_seq = blk.open_seq
            blk.resolved_cycle = blk.open_cycle
            blk.resolved_pc = blk.open_pc
            if not blk.close_diagnostic:
                blk.close_diagnostic = "missing_resolved_evt_closed_at_open"
        if blk.retired_seq is None and blk.resolved_seq is not None:
            blk.retired_seq = blk.resolved_seq
            blk.retired_cycle = blk.resolved_cycle
            blk.retired_pc = blk.resolved_pc
            if blk.retired_kind == "retired":
                blk.retired_kind = "resolved_fallback"
            if not blk.close_diagnostic:
                blk.close_diagnostic = "missing_retire_evt_closed_at_resolved"

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
        if blk.open_seq is not None:
            block_seq_open[buid] = blk.open_seq
        elif blk.resolved_seq is not None:
            block_seq_open[buid] = blk.resolved_seq
        elif blk.retired_seq is not None:
            block_seq_open[buid] = blk.retired_seq
        else:
            block_seq_open[buid] = max_key

    block_rows = sorted(
        [b for b in blocks.values() if b.open_cycle is not None or b.open_seq is not None or b.resolved_cycle is not None or b.retired_cycle is not None],
        key=lambda b: (b.core_id, block_seq_open.get(b.block_uid, max_key), 0, b.block_uid, 0),
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
                    block_seq_open.get(blk.block_uid, max_key),
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
        is_packet_row = _is_packet_row(u)
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
        if is_packet_row:
            continue
        if u.commit_cycle is not None:
            lane_tok = f"c{u.core_id}.l{u.commit_slot}"
            add_action(u.commit_cycle, ("P", STAGE_RANK.get("CMT", 999), kid, lane_tok, "CMT", 0, "0"))
            add_action(u.commit_cycle, ("R", 0 if not u.trap_valid else 1, kid))
        elif u.flush_cycle is not None:
            lane_tok = f"c{u.core_id}.l0"
            add_action(u.flush_cycle, ("P", STAGE_RANK.get("FLS", 999), kid, lane_tok, "FLS", 1, "flush"))
            add_action(u.flush_cycle, ("R", 1, kid))
        else:
            # Explicit fallback terminal for malformed/incomplete lifecycle rows.
            eof_cycle = max(int(max_cycle), int(u.first_cycle)) + 1
            lane_tok = f"c{u.core_id}.l0"
            add_action(eof_cycle, ("P", STAGE_RANK.get("FLS", 999), kid, lane_tok, "FLS", 1, "terminal_fallback"))
            add_action(eof_cycle, ("R", 1, kid))

    for blk in block_rows:
        kid = row_to_kid[("block", blk.block_uid)]
        core = blk.core_id
        open_cycle = blk.open_cycle
        resolved_cycle = blk.resolved_cycle
        retired_cycle = blk.retired_cycle

        start_cycle: Optional[int] = None
        if open_cycle is not None:
            start_cycle = open_cycle
        elif resolved_cycle is not None:
            start_cycle = resolved_cycle
        elif retired_cycle is not None:
            start_cycle = retired_cycle

        if start_cycle is None:
            continue

        if retired_cycle is not None and retired_cycle <= start_cycle:
            retired_cycle = start_cycle + 1

        end_cycle = retired_cycle if retired_cycle is not None else int(max_cycle)
        if end_cycle < start_cycle:
            end_cycle = start_cycle
        brob_end_cycle = end_cycle
        if retired_cycle is not None:
            brob_end_cycle = retired_cycle - 1

        for cyc in range(int(start_cycle), int(brob_end_cycle) + 1):
            cause = "active"
            if open_cycle is not None and cyc == open_cycle:
                cause = "open"
            elif resolved_cycle is not None and cyc == resolved_cycle:
                cause = "resolved"
            elif resolved_cycle is not None and cyc > resolved_cycle:
                cause = "waiting_retire"
            add_action(cyc, ("P", STAGE_RANK.get("BROB", 999), kid, f"c{core}.blk", "BROB", 0, cause))

        if retired_cycle is None:
            raise SystemExit(
                f"strict-linxtrace: block row missing retired_cycle after fallback handling "
                f"(block_uid=0x{blk.block_uid:x}, diagnostic={blk.close_diagnostic or 'none'})"
            )
        add_action(retired_cycle, ("P", STAGE_RANK.get("CMT", 999), kid, f"c{core}.blk", "CMT", 0, blk.retired_kind))
        add_action(retired_cycle, ("R", 1 if blk.retired_kind == "fault" else 0, kid))

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
    if meta_path is not None:
        meta_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_out_path = out_path.with_suffix(out_path.suffix + ".events.tmp")

    row_catalog: List[dict] = []
    row_sid_by_id: Dict[int, str] = {}
    lane_ids: set[str] = set()
    stage_ids: set[str] = set()
    row_schema: List[Tuple[int, str]] = []

    with tmp_out_path.open("w", encoding="utf-8") as out:
        def emit(rec: dict) -> None:
            out.write(json.dumps(rec, sort_keys=True) + "\n")

        for _, row_kind, row_obj in ordered_rows:
            if row_kind == "block":
                blk = row_obj
                row_id = row_to_kid[("block", blk.block_uid)]
                row_sid = _row_sid_block(blk.core_id, blk.block_uid)
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
                open_seq = blk.open_seq if blk.open_seq is not None else max_key
                seq_or_cycle = (
                    blk.retired_seq
                    if blk.retired_seq is not None
                    else (blk.resolved_seq if blk.resolved_seq is not None else int(blk.open_cycle or 0))
                )
                order_key = f"{int(blk.core_id)}:{int(open_seq)}:block:{int(seq_or_cycle)}:{int(blk.block_uid)}"
                lifecycle_flags = ["resolved", "retired"]
                if blk.retired_kind == "fault":
                    lifecycle_flags.append("trapped")
                if blk.close_diagnostic:
                    lifecycle_flags.append("fallback_closed")
                emit(
                    {
                        "type": "OP_DEF",
                        "cycle": int(min_cycle),
                        "row_id": row_id,
                        "row_sid": row_sid,
                        "row_kind": "block",
                        "core_id": blk.core_id,
                        "uop_uid": "0x0",
                        "parent_uid": "0x0",
                        "block_uid": f"0x{blk.block_uid:x}",
                        "kind": "block",
                    }
                )
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "row_sid": row_sid, "label_type": "left", "text": left_label})
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "row_sid": row_sid, "label_type": "detail", "text": detail})
                row_catalog.append(
                    {
                        "row_id": row_id,
                        "row_sid": row_sid,
                        "row_kind": "block",
                        "entity_kind": "block",
                        "core_id": blk.core_id,
                        "block_uid": f"0x{blk.block_uid:x}",
                        "uop_uid": "0x0",
                        "lifecycle_flags": lifecycle_flags,
                        "order_key": order_key,
                        "id_refs": {
                            "seq": int(blk.retired_seq if blk.retired_seq is not None else (blk.resolved_seq or 0)),
                            "uop_uid": "0x0",
                            "block_uid": f"0x{blk.block_uid:x}",
                            "block_bid": f"0x{blk.block_bid:x}",
                        },
                        "left_label": left_label,
                        "detail_defaults": detail,
                    }
                )
                row_sid_by_id[row_id] = row_sid
                row_schema.append((row_id, "block"))
            else:
                u = row_obj
                row_id = row_to_kid[("uop", u.uid)]
                is_packet_row = _is_packet_row(u)
                generated = _is_generated_uop(u.kind, u.parent_uid, asm_by_uid.get(u.uid, "") or asm_by_seq.get(u.commit_seq or -1, ""))
                if is_packet_row:
                    row_sid = _row_sid_packet(u.core_id, u.uid)
                elif generated:
                    row_sid = _row_sid_gen_uop(u.core_id, u.parent_uid, u.uid)
                else:
                    row_sid = _row_sid_uop(u.core_id, u.uid)
                uid_hex = f"0x{u.uid:x}"
                parent_hex = f"0x{u.parent_uid:x}"
                kind = "packet" if is_packet_row else u.kind
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
                if is_packet_row:
                    left_label = f"PKT uid={uid_hex} pc={_pc_label(u.pc, sym_sorted, sym_addrs)}"
                elif generated:
                    seq_s = str(u.commit_seq) if u.commit_seq is not None else "-"
                    left_label = f"GEN seq={seq_s} op={pseudo_asm}"
                else:
                    asm_txt = asm if asm else pseudo_asm
                    left_label = f"{_pc_label(u.pc, sym_sorted, sym_addrs)}: {asm_txt}"
                detail = _format_uop_detail(u, opcode)
                open_seq = block_seq_open.get(u.block_uid, max_key)
                seq_or_cycle = u.commit_seq if u.commit_seq is not None else int(u.first_cycle)
                order_key = f"{int(u.core_id)}:{int(open_seq)}:uop:{int(seq_or_cycle)}:{int(u.uid)}"
                lifecycle_flags = _uop_lifecycle_flags(u)
                emit(
                    {
                        "type": "OP_DEF",
                        "cycle": int(min_cycle),
                        "row_id": row_id,
                        "row_sid": row_sid,
                        "row_kind": "packet" if is_packet_row else "uop",
                        "core_id": u.core_id,
                        "uop_uid": uid_hex,
                        "parent_uid": parent_hex,
                        "block_uid": f"0x{u.block_uid:x}",
                        "kind": kind,
                    }
                )
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "row_sid": row_sid, "label_type": "left", "text": left_label})
                emit({"type": "LABEL", "cycle": int(min_cycle), "row_id": row_id, "row_sid": row_sid, "label_type": "detail", "text": detail})
                row_catalog.append(
                    {
                        "row_id": row_id,
                        "row_sid": row_sid,
                        "row_kind": "packet" if is_packet_row else "uop",
                        "entity_kind": _uop_entity_kind(u, generated),
                        "core_id": u.core_id,
                        "block_uid": f"0x{u.block_uid:x}",
                        "uop_uid": uid_hex,
                        "lifecycle_flags": lifecycle_flags,
                        "order_key": order_key,
                        "id_refs": {
                            "seq": int(u.commit_seq) if u.commit_seq is not None else None,
                            "uop_uid": uid_hex,
                            "block_uid": f"0x{u.block_uid:x}",
                            "block_bid": f"0x{u.block_bid:x}",
                        },
                        "left_label": left_label,
                        "detail_defaults": detail,
                    }
                )
                row_sid_by_id[row_id] = row_sid
                row_schema.append((row_id, "packet" if is_packet_row else "uop"))

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
                        "row_sid": row_sid_by_id.get(int(row_id), ""),
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
                            "row_sid": row_sid_by_id.get(int(row_id), ""),
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
                        "row_sid": row_sid_by_id.get(int(row_id), ""),
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
                        "block_bid": f"0x{blk.block_bid:x}",
                        "bid": f"0x{blk.block_bid:x}",
                        "seq": int(blk.open_seq or 0),
                        "pc": int(blk.open_pc),
                        "core_id": int(blk.core_id),
                    }
                )
            if blk.resolved_cycle is not None:
                emit(
                    {
                        "type": "BLOCK_EVT",
                        "cycle": int(blk.resolved_cycle),
                        "kind": "resolved",
                        "block_uid": f"0x{blk.block_uid:x}",
                        "block_bid": f"0x{blk.block_bid:x}",
                        "bid": f"0x{blk.block_bid:x}",
                        "seq": int(blk.resolved_seq or 0),
                        "pc": int(blk.resolved_pc),
                        "core_id": int(blk.core_id),
                    }
                )
            if blk.retired_cycle is not None:
                emit(
                    {
                        "type": "BLOCK_EVT",
                        "cycle": int(blk.retired_cycle),
                        "kind": str(blk.retired_kind),
                        "block_uid": f"0x{blk.block_uid:x}",
                        "block_bid": f"0x{blk.block_bid:x}",
                        "bid": f"0x{blk.block_bid:x}",
                        "seq": int(blk.retired_seq or 0),
                        "pc": int(blk.retired_pc),
                        "core_id": int(blk.core_id),
                    }
                )
            if blk.close_diagnostic:
                block_row_id = row_to_kid[("block", blk.block_uid)]
                emit(
                    {
                        "type": "XCHECK",
                        "cycle": int(blk.retired_cycle if blk.retired_cycle is not None else (blk.resolved_cycle or 0)),
                        "row_id": int(block_row_id),
                        "row_sid": row_sid_by_id.get(int(block_row_id), ""),
                        "status": "fallback_close",
                        "detail": str(blk.close_diagnostic),
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
    # Canonical output is a single-file .linxtrace with in-band META record.
    with out_path.open("w", encoding="utf-8") as out:
        out.write(json.dumps({"type": "META", **meta_obj}, sort_keys=True) + "\n")
        with tmp_out_path.open("r", encoding="utf-8") as tmp_in:
            for line in tmp_in:
                out.write(line)
    try:
        tmp_out_path.unlink(missing_ok=True)
    except Exception:
        pass

    # Optional sidecar for diagnostics only (non-canonical).
    if meta_path is not None:
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
                "meta": str(meta_path) if meta_path is not None else "",
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

    print(
        f"linxtrace-built {out_path} "
        f"meta={'(embedded)' if meta_path is None else meta_path} "
        f"uop_rows={len(uop_rows)} block_rows={len(block_rows)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
