#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import dataclasses
import json
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
TERMINAL_STAGES = {"CMT", "FLS", "XCHK"}


def _fmt_hex(v: int) -> str:
    return f"0x{int(v):X}"


def _resolve_tool(candidates: List[str]) -> Optional[str]:
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate)
        if path.exists() and path.is_file():
            return str(path)
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    return None


def _load_symbol_maps(
    elf_path: Optional[Path],
) -> Tuple[Dict[int, str], List[Tuple[int, str]], Dict[int, str], Dict[int, str]]:
    if elf_path is None or not elf_path.is_file():
        return {}, [], {}, {}

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
    disasm_by_pc: Dict[int, str] = {}
    disasm_alias_by_pc: Dict[int, str] = {}

    if objdump_bin:
        try:
            out = subprocess.check_output(
                [objdump_bin, "-d", str(elf_path)],
                text=True,
                stderr=subprocess.DEVNULL,
            )
            for line in out.splitlines():
                match = re.match(r"^\s*([0-9A-Fa-f]+)\s+<([^>]+)>:\s*$", line)
                if not match:
                    match = re.match(r"^\s*([0-9a-fA-F]+):\s*(.*)$", line)
                    if not match:
                        continue
                    addr = int(match.group(1), 16)
                    rest = match.group(2).strip()
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
                        disasm_by_pc[addr] = text
                        for alias_addr in range(addr + 2, addr + i, 2):
                            disasm_alias_by_pc.setdefault(alias_addr, text)
                    continue
                addr = int(match.group(1), 16)
                name = match.group(2).strip()
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
                match = re.match(r"^\s*([0-9A-Fa-f]+)\s+[A-Za-z]\s+(\S+)\s*$", line)
                if not match:
                    continue
                addr = int(match.group(1), 16)
                name = match.group(2).strip()
                if not name:
                    continue
                sym_exact.setdefault(addr, name)
                sym_sorted.append((addr, name))
        except Exception:
            pass

    if not sym_sorted and sym_exact:
        sym_sorted = sorted((addr, name) for addr, name in sym_exact.items())
    else:
        sym_sorted.sort(key=lambda item: item[0])
    if disasm_by_pc:
        disasm_addrs = sorted(disasm_by_pc)
        for idx, addr in enumerate(disasm_addrs):
            text = disasm_by_pc[addr]
            next_addr = disasm_addrs[idx + 1] if idx + 1 < len(disasm_addrs) else None
            upper = addr + 8
            upper_mn = text.upper()
            if (
                upper_mn.startswith("FENTRY")
                or upper_mn.startswith("FEXIT")
                or upper_mn.startswith("FRET")
                or "BSTART" in upper_mn
                or "BSTOP" in upper_mn
            ):
                upper = addr + 12
            if next_addr is not None:
                upper = min(upper, next_addr)
            for alias_addr in range(addr + 2, upper, 2):
                if alias_addr not in disasm_by_pc:
                    disasm_alias_by_pc.setdefault(alias_addr, text)
    return sym_exact, sym_sorted, disasm_by_pc, disasm_alias_by_pc


def _symbolize_asm(text: str, sym_exact: Dict[int, str]) -> str:
    if not text or not sym_exact:
        return text

    def repl(match: re.Match[str]) -> str:
        token = match.group(0)
        try:
            value = int(token, 16)
        except Exception:
            return token.upper()
        return sym_exact.get(value, _fmt_hex(value))

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


def _stage_group(stage: str) -> str:
    if stage == "IB":
        return "frontend"
    if stage in {"D1", "D2", "D3", "S1", "S2", "IQ", "P1", "I1", "I2", "E1", "W1", "W2"}:
        return "backend"
    if stage in {"CMT", "FLS", "XCHK"}:
        return "retire"
    return "other"


def _stage_color(stage: str) -> str:
    if stage in {"FLS", "XCHK"}:
        return "#EF4444"
    if stage == "CMT":
        return "#22C55E"
    if stage == "IB":
        return "#38BDF8"
    if stage in {"D1", "D2", "D3", "S1", "S2", "IQ"}:
        return "#A78BFA"
    if stage in {"P1", "I1", "I2", "E1", "W1", "W2"}:
        return "#34D399"
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
    for byte in seed.encode("utf-8"):
        h ^= byte
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return f"{LINXTRACE_PIPELINE_SCHEMA_ID}-{h:016X}"


def _extract_opcode(asm: str, fallback: str = "uop") -> str:
    text = (asm or "").strip()
    if not text:
        return fallback
    match = re.match(r"^([A-Za-z0-9_.]+)", text)
    if match:
        return match.group(1)
    return fallback


def _strip_asm_pc_prefix(asm: str) -> str:
    text = (asm or "").strip()
    if not text:
        return text
    return re.sub(r"^\s*(?:0x)?[0-9A-Fa-f]+(?:\s+<[^>]+>)?:\s*", "", text)


def _reg_name(reg: int) -> str:
    return f"r{int(reg)}"


def _mem_width_suffix(size: int) -> str:
    if size <= 0:
        return ""
    if size == 1:
        return "b"
    if size == 2:
        return "h"
    if size == 4:
        return "w"
    if size == 8:
        return "d"
    return str(size)


def _addr_expr(base_valid: bool, base_reg: int, base_data: int, addr: int) -> str:
    if base_valid:
        off = int(addr) - int(base_data)
        if off == 0:
            return f"[{_reg_name(base_reg)}]"
        return f"[{_reg_name(base_reg)}, {off:+d}]"
    return f"[0x{int(addr):x}]"


def _int_field(record: dict, key: str, default: int = 0) -> int:
    try:
        return int(record.get(key, default))
    except Exception:
        return default


def _int_optional(record: dict, key: str) -> Optional[int]:
    if key not in record:
        return None
    try:
        value = int(record.get(key))
    except Exception:
        return None
    if value == -1:
        return None
    return value


def _emit_pseudo_asm(opcode: str, commit_payload: Optional[dict]) -> str:
    op = (opcode or "uop").strip()
    if not commit_payload:
        return op.lower()

    src0_valid = _int_field(commit_payload, "src0_valid", 0) != 0
    src1_valid = _int_field(commit_payload, "src1_valid", 0) != 0
    dst_valid = _int_field(commit_payload, "dst_valid", 0) != 0
    mem_valid = _int_field(commit_payload, "mem_valid", 0) != 0
    mem_is_store = _int_field(commit_payload, "mem_is_store", 0) != 0
    mem_addr = _int_field(commit_payload, "mem_addr", 0)
    mem_wdata = _int_field(commit_payload, "mem_wdata", 0)
    mem_size = _int_field(commit_payload, "mem_size", 0)
    src0_reg = _int_field(commit_payload, "src0_reg", 0)
    src0_data = _int_field(commit_payload, "src0_data", 0)
    src1_reg = _int_field(commit_payload, "src1_reg", 0)
    src1_data = _int_field(commit_payload, "src1_data", 0)
    dst_reg = _int_field(commit_payload, "dst_reg", 0)
    dst_data = _int_field(commit_payload, "dst_data", 0)

    if mem_valid:
        suffix = _mem_width_suffix(mem_size)
        if mem_is_store:
            src_txt = _reg_name(src0_reg) if src0_valid else f"0x{mem_wdata:x}"
            addr_txt = _addr_expr(src1_valid, src1_reg, src1_data, mem_addr)
            return f"st{suffix} {src_txt}, {addr_txt}"
        dst_txt = _reg_name(dst_reg) if dst_valid else "r?"
        addr_txt = _addr_expr(src0_valid, src0_reg, src0_data, mem_addr)
        return f"ld{suffix} {addr_txt}, ->{dst_txt}"

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


def _block_asm_tag(asm: str) -> str:
    upper = (asm or "").upper()
    if "BSTART" in upper:
        return "[BHEAD] "
    if "BSTOP" in upper:
        return "[BTAIL] "
    return ""


def _normalized_asm_text(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip()).lower()


def _compose_left_label(
    row: "UopRow",
    *,
    asm: str,
    pseudo_asm: str,
    kind_text: str,
    sym_sorted: List[Tuple[int, str]],
    sym_addrs: List[int],
) -> Tuple[str, str]:
    pc_text = _pc_label(row.pc, sym_sorted, sym_addrs)
    asm_text = asm.strip()
    micro_text = pseudo_asm.strip()
    asm_norm = _normalized_asm_text(asm_text)
    micro_norm = _normalized_asm_text(micro_text)
    is_generated = _is_generated_uop(kind_text, row.parent_uid, asm_text)

    if is_generated:
        if asm_text:
            block_text = f"{_block_asm_tag(asm_text)}{asm_text}"
            if micro_text and micro_norm and micro_norm != asm_norm:
                return f"{pc_text}: {block_text} | [uop] {micro_text}", micro_text
            return f"{pc_text}: [uop] {block_text}", micro_text or asm_text
        return f"{pc_text}: [uop] {micro_text or row.op_name.lower()}", micro_text

    if asm_text:
        return f"{pc_text}: {_block_asm_tag(asm_text)}{asm_text}", micro_text
    return f"{pc_text}: {micro_text or row.op_name.lower()}", micro_text


def _is_generated_uop(kind_text: str, parent_uid: int, asm: str) -> bool:
    if kind_text in {"template_child", "replay"}:
        return True
    upper = (asm or "").upper()
    if parent_uid != 0 and (upper.startswith("FENTRY") or upper.startswith("FEXIT") or upper.startswith("FRET")):
        return True
    return False


@dataclasses.dataclass
class OccSample:
    cycle: int
    lane_id: str
    stage_id: str
    stall: int = 0
    cause: str = "0"


@dataclasses.dataclass
class UopRow:
    uid: int
    parent_uid: int = 0
    core_id: int = 0
    block_uid: int = 0
    block_bid: int = 0
    first_cycle: Optional[int] = None
    commit_seq: Optional[int] = None
    commit_cycle: Optional[int] = None
    commit_slot: int = 0
    pc: int = 0
    op_name: str = "uop"
    trap_valid: bool = False
    kind_codes: set[int] = dataclasses.field(default_factory=set)
    commit_payload: Optional[dict] = None
    occ_by_cycle: Dict[int, OccSample] = dataclasses.field(default_factory=dict)

    def kind_text(self, asm: str) -> str:
        if 4 in self.kind_codes or _is_generated_uop("template_child", self.parent_uid, asm):
            return "template_child"
        if 3 in self.kind_codes:
            return "replay"
        if self.trap_valid or 2 in self.kind_codes:
            return "trap"
        if 1 in self.kind_codes:
            return "flush"
        return "normal"

    def occ_sorted(self) -> List[OccSample]:
        return [self.occ_by_cycle[cycle] for cycle in sorted(self.occ_by_cycle)]


@dataclasses.dataclass
class BlockState:
    block_uid: int
    core_id: int = 0
    block_bid: int = 0
    open_seq: Optional[int] = None
    open_cycle: Optional[int] = None
    open_pc: int = 0
    close_seq: Optional[int] = None
    close_cycle: Optional[int] = None
    retire_seq: Optional[int] = None
    retire_cycle: Optional[int] = None
    terminal_kind: str = "open"


def _best_block_for_uop(row: UopRow, blocks: Dict[int, BlockState]) -> int:
    seq_key = row.commit_seq if row.commit_seq is not None else (1 << 60)
    cyc_key = row.first_cycle if row.first_cycle is not None else (1 << 60)
    best_uid = 0
    best_seq = -1
    best_cyc = -1
    for block_uid, block in blocks.items():
        if block.core_id != row.core_id:
            continue
        if block.open_seq is not None:
            if block.open_seq > seq_key:
                continue
            if block.close_seq is not None and block.close_seq < seq_key:
                continue
            if block.open_seq > best_seq:
                best_seq = block.open_seq
                best_uid = block_uid
        elif block.open_cycle is not None:
            if block.open_cycle > cyc_key:
                continue
            if block.close_cycle is not None and block.close_cycle < cyc_key:
                continue
            if block.open_cycle > best_cyc:
                best_cyc = block.open_cycle
                best_uid = block_uid
    return best_uid


def _parse_commit_text(
    commit_text_path: Optional[Path],
) -> Tuple[Dict[int, str], Dict[int, str], Dict[int, str]]:
    asm_by_uid: Dict[int, str] = {}
    asm_by_seq: Dict[int, str] = {}
    op_by_seq: Dict[int, str] = {}
    if commit_text_path is None or not commit_text_path.is_file():
        return asm_by_uid, asm_by_seq, op_by_seq

    with commit_text_path.open("r", encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "uid=0x" not in line:
                continue
            fields = {}
            for part in line.split("|"):
                part = part.strip()
                if "=" not in part:
                    continue
                key, value = part.split("=", 1)
                fields[key.strip()] = value.strip()
            uid_text = fields.get("uid", "")
            if not uid_text.lower().startswith("0x"):
                continue
            try:
                uid = int(uid_text, 16)
            except ValueError:
                continue
            asm = fields.get("asm", "")
            if asm:
                asm_by_uid[uid] = _strip_asm_pc_prefix(asm)
            seq_text = fields.get("seq", "")
            if not seq_text:
                continue
            try:
                seq = int(seq_text, 10)
            except ValueError:
                continue
            if asm:
                asm_by_seq[seq] = _strip_asm_pc_prefix(asm)
            op_value = fields.get("op", "")
            if op_value:
                op_by_seq[seq] = op_value
    return asm_by_uid, asm_by_seq, op_by_seq


def _format_timeline(row: UopRow) -> List[str]:
    lines = ["timeline:"]
    for occ in row.occ_sorted():
        text = f"{occ.cycle}: {occ.stage_id} @{occ.lane_id}"
        if occ.stall:
            text += f" stall=1 cause={occ.cause}"
        elif occ.cause not in {"", "0"}:
            text += f" cause={occ.cause}"
        lines.append(text)
    return lines


def _fill_hold_gaps(row: UopRow) -> None:
    occs = row.occ_sorted()
    if len(occs) < 2:
        return

    filled: Dict[int, OccSample] = {}
    exec_stages = {"P1", "I1", "I2", "E1", "W1", "W2"}
    terminal_stages = {"CMT", "FLS", "XCHK"}

    for prev, nxt in zip(occs, occs[1:]):
        if nxt.cycle <= prev.cycle + 1:
            continue
        if prev.stage_id in terminal_stages:
            raise SystemExit(
                f"uop 0x{row.uid:x} has a gap after terminal stage {prev.stage_id} at cycle {prev.cycle}"
            )

        hold_stage = prev.stage_id
        hold_lane = prev.lane_id
        hold_stall = prev.stall if prev.stage_id == nxt.stage_id else 0
        hold_cause = prev.cause if hold_stall else "0"

        if prev.stage_id == "S2" and nxt.stage_id in exec_stages:
            hold_stage = "IQ"
            hold_lane = nxt.lane_id
            hold_stall = 0
            hold_cause = "0"

        for cycle in range(prev.cycle + 1, nxt.cycle):
            if cycle in row.occ_by_cycle:
                continue
            filled[cycle] = OccSample(
                cycle=cycle,
                lane_id=hold_lane,
                stage_id=hold_stage,
                stall=hold_stall,
                cause=hold_cause,
            )

    row.occ_by_cycle.update(filled)


def _format_uop_detail(row: UopRow, opcode: str, asm_text: str = "", micro_text: str = "") -> str:
    lines: List[str] = [f"uid=0x{row.uid:x}"]
    if row.parent_uid:
        lines.append(f"parent=0x{row.parent_uid:x}")
    if row.block_uid:
        lines.append(f"block=0x{row.block_uid:x}")
    if row.block_bid:
        lines.append(f"bid=0x{row.block_bid:x}")
    if row.commit_seq is not None:
        lines.append(f"seq={row.commit_seq}")
    lines.append(f"op={opcode}")
    if asm_text:
        lines.append(f"asm={asm_text}")
    if micro_text and _normalized_asm_text(micro_text) != _normalized_asm_text(asm_text):
        lines.append(f"uop={micro_text}")

    commit_payload = row.commit_payload
    if commit_payload:
        src0_valid = _int_optional(commit_payload, "src0_valid") not in (None, 0)
        src1_valid = _int_optional(commit_payload, "src1_valid") not in (None, 0)
        dst_valid = _int_optional(commit_payload, "dst_valid") not in (None, 0)
        wb_valid = _int_optional(commit_payload, "wb_valid") not in (None, 0)
        mem_valid = _int_optional(commit_payload, "mem_valid") not in (None, 0)
        trap_valid = _int_optional(commit_payload, "trap_valid") not in (None, 0)

        if src0_valid:
            src0_reg = _int_optional(commit_payload, "src0_reg")
            src0_data = _int_optional(commit_payload, "src0_data")
            if src0_reg is not None and src0_data is not None:
                lines.append(f"src0={_reg_name(src0_reg)} 0x{src0_data:x}")
        if src1_valid:
            src1_reg = _int_optional(commit_payload, "src1_reg")
            src1_data = _int_optional(commit_payload, "src1_data")
            if src1_reg is not None and src1_data is not None:
                lines.append(f"src1={_reg_name(src1_reg)} 0x{src1_data:x}")
        if dst_valid:
            dst_reg = _int_optional(commit_payload, "dst_reg")
            dst_data = _int_optional(commit_payload, "dst_data")
            if dst_reg is not None and dst_data is not None:
                lines.append(f"dst={_reg_name(dst_reg)} 0x{dst_data:x}")
        if wb_valid:
            wb_reg = _int_optional(commit_payload, "wb_rd")
            wb_data = _int_optional(commit_payload, "wb_data")
            if wb_reg is not None and wb_data is not None:
                lines.append(f"wb={_reg_name(wb_reg)} 0x{wb_data:x}")
        if mem_valid:
            mem_is_store = _int_optional(commit_payload, "mem_is_store") not in (None, 0)
            mem_addr = _int_optional(commit_payload, "mem_addr")
            mem_size = _int_optional(commit_payload, "mem_size")
            mem_wdata = _int_optional(commit_payload, "mem_wdata")
            mem_rdata = _int_optional(commit_payload, "mem_rdata")
            suffix = _mem_width_suffix(mem_size if mem_size is not None else 0)
            if mem_is_store:
                text = f"mem=st{suffix}"
                if mem_addr is not None:
                    text += f" @0x{mem_addr:x}"
                if mem_wdata is not None:
                    text += f" data=0x{mem_wdata:x}"
            else:
                text = f"mem=ld{suffix}"
                if mem_addr is not None:
                    text += f" @0x{mem_addr:x}"
                if mem_rdata is not None:
                    text += f" data=0x{mem_rdata:x}"
            lines.append(text)
        if trap_valid:
            trap_cause = _int_optional(commit_payload, "trap_cause")
            if trap_cause is None:
                lines.append("trap=1")
            else:
                lines.append(f"trap=0x{trap_cause:x}")

    lines.extend(_format_timeline(row))
    return "\n".join(lines)


def _normalize_block_event(record: dict) -> dict:
    return {
        "type": "BLOCK_EVT",
        "cycle": int(record.get("cycle", 0)),
        "kind": str(record.get("kind", "open")),
        "block_uid": f"0x{int(record.get('block_uid', 0)):x}",
        "bid": f"0x{int(record.get('block_bid', record.get('bid', 0))):x}",
        "seq": int(record.get("seq", 0)),
        "pc": int(record.get("pc", 0)),
        "core_id": int(record.get("core_id", 0)),
    }


def _format_block_detail(block: BlockState, asm_text: str = "") -> str:
    parts = [
        f"block_uid=0x{block.block_uid:x}",
        f"bid=0x{block.block_bid:x}",
    ]
    if block.open_pc:
        parts.append(f"open_pc=0x{block.open_pc:x}")
    if block.open_cycle is not None:
        parts.append(f"open_cycle={block.open_cycle}")
    if block.open_seq is not None:
        parts.append(f"open_seq={block.open_seq}")
    if block.close_cycle is not None:
        parts.append(f"close_cycle={block.close_cycle}")
    if block.retire_cycle is not None:
        parts.append(f"retire_cycle={block.retire_cycle}")
    if asm_text:
        parts.append(f"asm={asm_text}")
    parts.append(f"terminal={block.terminal_kind}")
    return "\n".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build canonical LinxTrace v1 (uop-only rows) from LinxCore raw event trace."
    )
    parser.add_argument("--raw", required=True, help="Raw event JSONL from tb_linxcore_top.cpp (PYC_RAW_TRACE).")
    parser.add_argument("--out", required=True, help="Output .linxtrace path.")
    parser.add_argument("--map-report", default="", help="Optional mapping report JSON path.")
    parser.add_argument("--commit-text", default="", help="Optional commit text trace for asm labels.")
    parser.add_argument("--elf", default="", help="Optional ELF path used for symbolized labels.")
    args = parser.parse_args()

    raw_path = Path(args.raw)
    out_path = Path(args.out)
    if out_path.suffix != ".linxtrace":
        raise SystemExit(f"output must be *.linxtrace, got: {out_path}")
    if not raw_path.is_file():
        raise SystemExit(f"missing raw trace: {raw_path}")

    map_path = Path(args.map_report) if args.map_report else out_path.with_suffix(".map.json")
    commit_text_path = Path(args.commit_text) if args.commit_text else None
    elf_path = Path(args.elf) if args.elf else None
    sym_exact, sym_sorted, disasm_by_pc, disasm_alias_by_pc = _load_symbol_maps(elf_path)
    sym_addrs = [addr for addr, _ in sym_sorted]
    asm_by_uid, asm_by_seq, op_by_seq = _parse_commit_text(commit_text_path)

    uops: Dict[int, UopRow] = {}
    blocks: Dict[int, BlockState] = {}
    block_events: List[dict] = []
    lane_ids: set[str] = set()
    op_by_pc: Dict[int, str] = {}
    op_alias_by_pc: Dict[int, str] = {}
    span_by_seq: Dict[int, Tuple[int, int]] = {}

    with raw_path.open("r", encoding="utf-8", errors="ignore") as handle:
        for lineno, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"line {lineno}: invalid JSON ({exc})") from exc

            rtype = str(record.get("type", ""))
            if rtype == "probe_occ":
                continue

            if rtype == "occ":
                uid = int(record.get("uop_uid", 0))
                if uid == 0:
                    continue
                kind_code = int(record.get("kind", 0))
                if kind_code == 5:
                    continue
                stage = str(record.get("stage", "")).upper()
                if stage not in STAGE_RANK:
                    raise SystemExit(f"line {lineno}: non-canonical occ stage {stage!r}")
                row = uops.setdefault(uid, UopRow(uid=uid))
                row.kind_codes.add(kind_code)
                row.parent_uid = int(record.get("parent_uid", row.parent_uid))
                row.core_id = int(record.get("core_id", row.core_id))
                row.block_uid = int(record.get("block_uid", row.block_uid))
                row.block_bid = int(record.get("block_bid", row.block_bid))
                row.pc = int(record.get("pc", row.pc))
                cycle = int(record.get("cycle", 0))
                if row.first_cycle is None or cycle < row.first_cycle:
                    row.first_cycle = cycle
                lane_id = f"c{row.core_id}.l{int(record.get('lane', 0))}"
                if cycle in row.occ_by_cycle:
                    prev = row.occ_by_cycle[cycle]
                    raise SystemExit(
                        "duplicate canonical OCC for one uop-cycle: "
                        f"uid=0x{uid:x} cycle={cycle} prev={prev.stage_id}@{prev.lane_id} new={stage}@{lane_id}"
                    )
                occ = OccSample(
                    cycle=cycle,
                    lane_id=lane_id,
                    stage_id=stage,
                    stall=int(record.get("stall", 0)),
                    cause=str(record.get("stall_cause", "0")),
                )
                row.occ_by_cycle[cycle] = occ
                lane_ids.add(lane_id)
                continue

            if rtype == "commit":
                uid = int(record.get("uop_uid", 0))
                if uid != 0:
                    row = uops.setdefault(uid, UopRow(uid=uid))
                    row.parent_uid = int(record.get("parent_uid", row.parent_uid))
                    row.core_id = int(record.get("core_id", row.core_id))
                    row.block_uid = int(record.get("block_uid", row.block_uid))
                    row.block_bid = int(record.get("block_bid", row.block_bid))
                    row.commit_seq = int(record.get("seq", 0))
                    row.commit_cycle = int(record.get("cycle", 0))
                    row.commit_slot = int(record.get("slot", 0))
                    row.pc = int(record.get("pc", row.pc))
                    insn_len = int(record.get("len", 0))
                    if row.commit_seq and row.pc != 0 and insn_len > 0:
                        span_by_seq[row.commit_seq] = (row.pc, insn_len)
                    row.op_name = str(record.get("op_name", row.op_name))
                    if row.pc != 0 and row.op_name and row.op_name != "uop":
                        op_by_pc[row.pc] = row.op_name
                        for addr in range(row.pc + 2, row.pc + max(insn_len, 2) + 1, 2):
                            op_alias_by_pc.setdefault(addr, row.op_name)
                    row.trap_valid = bool(int(record.get("trap_valid", 0)))
                    row.commit_payload = record
                    if row.first_cycle is None:
                        row.first_cycle = row.commit_cycle
                block_uid = int(record.get("block_uid", 0))
                if block_uid != 0:
                    block = blocks.setdefault(block_uid, BlockState(block_uid=block_uid))
                    block.core_id = int(record.get("core_id", block.core_id))
                    block.block_bid = int(record.get("block_bid", block.block_bid))
                    seq = int(record.get("seq", 0))
                    cycle = int(record.get("cycle", 0))
                    if int(record.get("is_bstart", 0)) != 0:
                        if block.open_seq is None or seq < block.open_seq:
                            block.open_seq = seq
                            block.open_cycle = cycle
                            block.open_pc = int(record.get("pc", block.open_pc))
                    if int(record.get("is_bstop", 0)) != 0:
                        if block.close_seq is None or seq < block.close_seq:
                            block.close_seq = seq
                            block.close_cycle = cycle
                continue

            if rtype in {"blk_evt", "block_evt"}:
                normalized = _normalize_block_event(record)
                block_events.append(normalized)
                block_uid = int(record.get("block_uid", 0))
                if block_uid != 0:
                    block = blocks.setdefault(block_uid, BlockState(block_uid=block_uid))
                    block.core_id = int(record.get("core_id", block.core_id))
                    bid = int(record.get("block_bid", record.get("bid", block.block_bid)))
                    if bid != 0:
                        block.block_bid = bid
                    kind = str(record.get("kind", "open"))
                    seq = int(record.get("seq", 0))
                    cycle = int(record.get("cycle", 0))
                    if kind == "open":
                        if block.open_seq is None or seq < block.open_seq:
                            block.open_seq = seq
                            block.open_cycle = cycle
                            block.open_pc = int(record.get("pc", block.open_pc))
                    elif kind == "retired":
                        if block.retire_seq is None or seq < block.retire_seq:
                            block.retire_seq = seq
                            block.retire_cycle = cycle
                            block.terminal_kind = kind
                    else:
                        if block.close_seq is None or seq < block.close_seq:
                            block.close_seq = seq
                            block.close_cycle = cycle
                        block.terminal_kind = kind
                continue

    if not uops:
        raise SystemExit("raw trace produced zero uops")

    asm_by_pc_commit: Dict[int, str] = {}
    asm_alias_by_pc_commit: Dict[int, str] = {}
    for row in uops.values():
        if row.commit_seq is None:
            continue
        asm_text = asm_by_seq.get(row.commit_seq, "")
        if not asm_text:
            continue
        base_pc, insn_len = span_by_seq.get(row.commit_seq, (row.pc, 0))
        if base_pc == 0:
            continue
        asm_by_pc_commit.setdefault(base_pc, asm_text)
        for addr in range(base_pc + 2, base_pc + max(insn_len, 2) + 1, 2):
            asm_alias_by_pc_commit.setdefault(addr, asm_text)

    for row in uops.values():
        if not row.occ_by_cycle:
            raise SystemExit(f"uop 0x{row.uid:x} has no canonical OCC records")
        _fill_hold_gaps(row)
        if row.block_uid == 0:
            row.block_uid = _best_block_for_uop(row, blocks)
        if row.block_bid == 0 and row.block_uid != 0:
            block = blocks.get(row.block_uid)
            if block is not None and block.block_bid:
                row.block_bid = block.block_bid

        occs = row.occ_sorted()
        if row.commit_cycle is not None:
            commit_occ = row.occ_by_cycle.get(row.commit_cycle)
            if commit_occ is None or commit_occ.stage_id != "CMT":
                raise SystemExit(
                    f"uop 0x{row.uid:x} committed at cycle {row.commit_cycle} without canonical CMT occupancy"
                )
            if occs[-1].cycle > row.commit_cycle:
                raise SystemExit(f"uop 0x{row.uid:x} has occupancy after commit cycle {row.commit_cycle}")
        else:
            terminal_occs = [occ for occ in occs if occ.stage_id in {"FLS", "XCHK"}]
            if terminal_occs:
                terminal = terminal_occs[-1]
                if occs[-1].cycle != terminal.cycle or occs[-1].stage_id != terminal.stage_id:
                    raise SystemExit(
                        f"uop 0x{row.uid:x} has occupancy after terminal stage {terminal.stage_id} at cycle {terminal.cycle}"
                    )
        asm_hint = asm_by_uid.get(row.uid, asm_by_seq.get(row.commit_seq or -1, ""))
        if not asm_hint:
            asm_hint = disasm_by_pc.get(row.pc, "")
        if not asm_hint:
            asm_hint = asm_alias_by_pc_commit.get(row.pc, "")
        if not asm_hint:
            asm_hint = disasm_alias_by_pc.get(row.pc, "")
        if row.kind_text(asm_hint) in {"flush", "replay"} and occs[-1].stage_id != "FLS":
            raise SystemExit(f"uop 0x{row.uid:x} is flush/replay but does not end at FLS")

    uop_rows = sorted(
        uops.values(),
        key=lambda row: (
            row.core_id,
            row.first_cycle if row.first_cycle is not None else (1 << 60),
            row.commit_seq if row.commit_seq is not None else (1 << 60),
            row.uid,
        ),
    )
    row_to_id = {row.uid: idx + 1 for idx, row in enumerate(uop_rows)}
    lane_list = sorted(lane_ids)

    bootstrap_cycle = min(
        [row.first_cycle for row in uop_rows if row.first_cycle is not None] + [0]
    )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    map_path.parent.mkdir(parents=True, exist_ok=True)

    row_catalog: List[dict] = []
    header_records: List[dict] = []
    timeline_records: List[Tuple[int, int, int, dict]] = []
    uid_to_kid = {str(row.uid): row_to_id[row.uid] for row in uop_rows}

    for row in uop_rows:
        row_id = row_to_id[row.uid]
        uid_hex = f"0x{row.uid:x}"
        parent_hex = f"0x{row.parent_uid:x}"
        block_hex = f"0x{row.block_uid:x}"
        asm_raw = asm_by_uid.get(row.uid, "")
        if not asm_raw:
            asm_raw = asm_by_seq.get(row.commit_seq or -1, "")
        if not asm_raw:
            asm_raw = asm_by_pc_commit.get(row.pc, "")
        if not asm_raw:
            asm_raw = disasm_by_pc.get(row.pc, "")
        if not asm_raw:
            asm_raw = asm_alias_by_pc_commit.get(row.pc, "")
        if not asm_raw:
            asm_raw = disasm_alias_by_pc.get(row.pc, "")
        asm = _symbolize_asm(asm_raw, sym_exact)
        op_name = row.op_name.strip() if row.op_name else ""
        if not op_name or op_name == "uop":
            op_name = op_by_seq.get(row.commit_seq or -1, "")
        if not op_name or op_name == "uop":
            op_name = op_by_pc.get(row.pc, "")
        if not op_name or op_name == "uop":
            op_name = op_alias_by_pc.get(row.pc, "")
        if not op_name or op_name == "uop":
            op_name = _extract_opcode(asm_raw or asm, "uop")
        opcode = _extract_opcode(asm, op_name)
        pseudo_asm = _emit_pseudo_asm(opcode, row.commit_payload)
        kind_text = row.kind_text(asm_raw)
        entity_kind = "gen_uop" if _is_generated_uop(kind_text, row.parent_uid, asm_raw) else "uop"
        left_label, micro_text = _compose_left_label(
            row,
            asm=asm,
            pseudo_asm=pseudo_asm,
            kind_text=kind_text,
            sym_sorted=sym_sorted,
            sym_addrs=sym_addrs,
        )
        detail = _format_uop_detail(row, opcode, asm_text=asm, micro_text=micro_text)
        row_sid = f"c{row.core_id}.uop.0x{row.uid:x}"

        header_records.append(
            {
                "type": "OP_DEF",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_kind": "uop",
                "core_id": row.core_id,
                "row_sid": row_sid,
                "uop_uid": uid_hex,
                "parent_uid": parent_hex,
                "block_uid": block_hex,
                "kind": kind_text,
            }
        )
        header_records.append(
            {
                "type": "LABEL",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_sid": row_sid,
                "label_type": "left",
                "text": left_label,
            }
        )
        header_records.append(
            {
                "type": "LABEL",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_sid": row_sid,
                "label_type": "detail",
                "text": detail,
            }
        )
        row_catalog.append(
            {
                "row_id": row_id,
                "row_kind": "uop",
                "core_id": row.core_id,
                "row_sid": row_sid,
                "uop_uid": uid_hex,
                "parent_uid": parent_hex,
                "block_uid": block_hex,
                "entity_kind": entity_kind,
                "left_label": left_label,
                "detail_defaults": detail,
            }
        )

        occs = row.occ_sorted()
        for occ in occs:
            timeline_records.append(
                (
                    occ.cycle,
                    0,
                    row_id,
                    {
                        "type": "OCC",
                        "cycle": occ.cycle,
                        "row_id": row_id,
                        "lane_id": occ.lane_id,
                        "stage_id": occ.stage_id,
                        "stall": occ.stall,
                        "cause": occ.cause,
                    },
                )
            )
            if occ.stage_id == "XCHK":
                detail_text = occ.cause if occ.cause not in {"", "0"} else "xcheck"
                timeline_records.append(
                    (
                        occ.cycle,
                        1,
                        row_id,
                        {
                            "type": "XCHECK",
                            "cycle": occ.cycle,
                            "row_id": row_id,
                            "status": "mismatch",
                            "detail": detail_text,
                        },
                    )
                )

        last_occ = occs[-1]
        if row.commit_cycle is not None:
            status_code = 1 if row.trap_valid else 0
            status = "terminal" if row.trap_valid else "ok"
            timeline_records.append(
                (
                    row.commit_cycle,
                    2,
                    row_id,
                    {
                        "type": "RETIRE",
                        "cycle": row.commit_cycle,
                        "row_id": row_id,
                        "status": status,
                        "status_code": status_code,
                    },
                )
            )
        elif last_occ.stage_id in {"FLS", "XCHK"}:
            timeline_records.append(
                (
                    last_occ.cycle,
                    2,
                    row_id,
                    {
                        "type": "RETIRE",
                        "cycle": last_occ.cycle,
                        "row_id": row_id,
                        "status": "terminal",
                        "status_code": 1,
                    },
                )
            )

    next_row_id = len(uop_rows) + 1
    block_rows = sorted(
        [block for block in blocks.values() if block.block_uid != 0],
        key=lambda block: (
            block.core_id,
            block.open_cycle if block.open_cycle is not None else (1 << 60),
            block.block_uid,
        ),
    )
    block_row_to_id = {block.block_uid: next_row_id + idx for idx, block in enumerate(block_rows)}
    for block in block_rows:
        row_id = block_row_to_id[block.block_uid]
        block_uid_hex = f"0x{block.block_uid:x}"
        block_bid_hex = f"0x{block.block_bid:x}"
        row_sid = f"c{block.core_id}.block.{block_uid_hex}"
        block_asm_raw = asm_by_seq.get(block.open_seq or -1, "")
        if not block_asm_raw:
            block_asm_raw = disasm_by_pc.get(block.open_pc, "")
        block_asm = _symbolize_asm(block_asm_raw, sym_exact)
        left_label = f"BLK {block_uid_hex} bid={block_bid_hex}"
        if block_asm:
            left_label += f": {_block_asm_tag(block_asm)}{block_asm}"
        detail = _format_block_detail(block, asm_text=block_asm)
        header_records.append(
            {
                "type": "OP_DEF",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_kind": "block",
                "core_id": block.core_id,
                "row_sid": row_sid,
                "block_uid": block_uid_hex,
                "kind": "block",
            }
        )
        header_records.append(
            {
                "type": "LABEL",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_sid": row_sid,
                "label_type": "left",
                "text": left_label,
            }
        )
        header_records.append(
            {
                "type": "LABEL",
                "cycle": bootstrap_cycle,
                "row_id": row_id,
                "row_sid": row_sid,
                "label_type": "detail",
                "text": detail,
            }
        )
        row_catalog.append(
            {
                "row_id": row_id,
                "row_kind": "block",
                "core_id": block.core_id,
                "row_sid": row_sid,
                "uop_uid": block_uid_hex,
                "block_uid": block_uid_hex,
                "entity_kind": "block",
                "left_label": left_label,
                "detail_defaults": detail,
                "id_refs": {
                    "block_bid": block_bid_hex,
                    "seq": block.open_seq if block.open_seq is not None else block.retire_seq,
                },
            }
        )
        open_cycle = block.open_cycle
        terminal_cycle = block.retire_cycle if block.retire_cycle is not None else block.close_cycle
        if open_cycle is not None:
            timeline_records.append(
                (
                    open_cycle,
                    2,
                    row_id,
                    {
                        "type": "OCC",
                        "cycle": open_cycle,
                        "row_id": row_id,
                        "lane_id": f"c{block.core_id}.l0",
                        "stage_id": "IB",
                        "stall": 0,
                        "cause": "block_open",
                    },
                )
            )
            if terminal_cycle is not None and terminal_cycle > open_cycle:
                active_stage = "IQ"
                for cycle in range(open_cycle + 1, terminal_cycle):
                    timeline_records.append(
                        (
                            cycle,
                            2,
                            row_id,
                            {
                                "type": "OCC",
                                "cycle": cycle,
                                "row_id": row_id,
                                "lane_id": f"c{block.core_id}.l0",
                                "stage_id": active_stage,
                                "stall": 0,
                                "cause": "block_live",
                            },
                        )
                    )
        if terminal_cycle is not None:
            terminal_stage = "CMT" if block.retire_cycle is not None else "FLS"
            terminal_cause = "block_retired" if block.retire_cycle is not None else f"block_{block.terminal_kind}"
            timeline_records.append(
                (
                    terminal_cycle,
                    2,
                    row_id,
                    {
                        "type": "OCC",
                        "cycle": terminal_cycle,
                        "row_id": row_id,
                        "lane_id": f"c{block.core_id}.l0",
                        "stage_id": terminal_stage,
                        "stall": 0,
                        "cause": terminal_cause,
                    },
                )
            )
            timeline_records.append(
                (
                    terminal_cycle,
                    3,
                    row_id,
                    {
                        "type": "RETIRE",
                        "cycle": terminal_cycle,
                        "row_id": row_id,
                        "status": "ok" if block.retire_cycle is not None else "terminal",
                        "status_code": 0 if block.retire_cycle is not None else 1,
                    },
                )
            )

    for block_event in block_events:
        row_id = block_row_to_id.get(int(block_event["block_uid"], 16))
        if row_id is not None:
            block_event = dict(block_event)
            block_event["row_id"] = row_id
        timeline_records.append((int(block_event["cycle"]), 4, row_id or 0, block_event))

    row_schema = [(row_to_id[row.uid], "uop") for row in uop_rows]
    row_schema.extend((block_row_to_id[block.block_uid], "block") for block in block_rows)
    contract_id = _contract_id(STAGE_ORDER, lane_list, row_schema)

    stage_catalog = [
        {"stage_id": stage, "label": stage, "color": _stage_color(stage), "group": _stage_group(stage)}
        for stage in STAGE_ORDER
    ]
    lane_catalog = [{"lane_id": lane, "label": lane} for lane in lane_list]
    meta = {
        "type": "META",
        "format": "linxtrace.v1",
        "contract_id": contract_id,
        "pipeline_schema_id": LINXTRACE_PIPELINE_SCHEMA_ID,
        "stage_order_csv": LINXTRACE_STAGE_ORDER_CSV,
        "stage_catalog": stage_catalog,
        "lane_catalog": lane_catalog,
        "row_catalog": row_catalog,
        "render_prefs": {"theme": "high-contrast", "show_symbols": True},
    }

    with out_path.open("w", encoding="utf-8") as out:
        out.write(json.dumps(meta, sort_keys=False) + "\n")
        for record in header_records:
            out.write(json.dumps(record, sort_keys=True) + "\n")
        timeline_records.sort(key=lambda item: (item[0], item[1], item[2], STAGE_RANK.get(item[3].get("stage_id", ""), 999)))
        for _, _, _, record in timeline_records:
            out.write(json.dumps(record, sort_keys=True) + "\n")

    block_to_bid = {str(block_uid): block.block_bid for block_uid, block in blocks.items() if block.block_bid}
    with map_path.open("w", encoding="utf-8") as map_file:
        json.dump(
            {
                "raw": str(raw_path),
                "linxtrace": str(out_path),
                "uid_to_kid": uid_to_kid,
                "block_uid_to_bid": block_to_bid,
                "uop_rows": len(uop_rows),
                "block_rows": len(block_rows),
                "block_events": len(block_events),
            },
            map_file,
            indent=2,
            sort_keys=True,
        )
        map_file.write("\n")

    print(
        f"linxtrace-built {out_path} "
        f"uop_rows={len(uop_rows)} block_rows={len(block_rows)} block_events={len(block_events)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
