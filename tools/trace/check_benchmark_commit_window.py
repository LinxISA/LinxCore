#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import sys
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


def _load_crosscheck_module(repo_root: Path):
    mod_path = repo_root / "tools" / "trace" / "crosscheck_qemu_linxcore.py"
    mod_name = "crosscheck_qemu_linxcore"
    spec = importlib.util.spec_from_file_location(mod_name, mod_path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"error: failed to load crosscheck helper: {mod_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[mod_name] = module
    spec.loader.exec_module(module)
    return module


def _row_to_commit(crosscheck: Any, obj: dict[str, Any]):
    return crosscheck.Commit(
        seq=_to_int(obj.get("seq", 0)),
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
        template_kind=_to_int(obj.get("template_kind", 0)),
    )


def _is_legal_zero_boundary(obj: dict[str, Any]) -> bool:
    return (
        _to_int(obj.get("insn", 0)) == 0
        and _to_int(obj.get("len", 0)) == 2
        and _to_int(obj.get("is_bstop", 0)) == 1
        and _to_int(obj.get("wb_valid", 0)) == 0
        and _to_int(obj.get("dst_valid", obj.get("wb_valid", 0))) == 0
        and _to_int(obj.get("mem_valid", 0)) == 0
        and _to_int(obj.get("trap_valid", 0)) == 0
    )


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Check benchmark DUT commit traces for short windows and malformed zero-insn boundaries."
    )
    ap.add_argument("--trace", required=True, help="Path to DUT commit JSONL trace")
    ap.add_argument("--want-commits", type=int, required=True, help="Required architectural commit window")
    ap.add_argument("--label", default="", help="Optional benchmark label for diagnostics")
    args = ap.parse_args()

    trace_path = Path(args.trace)
    if not trace_path.is_file():
        raise SystemExit(f"error: missing DUT trace: {trace_path}")
    if args.want_commits < 1:
        raise SystemExit(f"error: invalid --want-commits: {args.want_commits}")

    repo_root = Path(__file__).resolve().parents[2]
    crosscheck = _load_crosscheck_module(repo_root)

    rows: list[dict[str, Any]] = []
    with trace_path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))

    unexpected_zero_rows = []
    nonmeta_rows = 0
    legal_zero_rows = 0
    for obj in rows:
        insn = _to_int(obj.get("insn", 0))
        if insn == 0:
            if _is_legal_zero_boundary(obj):
                legal_zero_rows += 1
            else:
                unexpected_zero_rows.append(
                    {
                        "seq": _to_int(obj.get("seq", 0)),
                        "pc": _to_int(obj.get("pc", 0)),
                        "len": _to_int(obj.get("len", 0)),
                        "is_bstart": _to_int(obj.get("is_bstart", 0)),
                        "is_bstop": _to_int(obj.get("is_bstop", 0)),
                        "wb_valid": _to_int(obj.get("wb_valid", 0)),
                        "dst_valid": _to_int(obj.get("dst_valid", 0)),
                        "mem_valid": _to_int(obj.get("mem_valid", 0)),
                        "trap_valid": _to_int(obj.get("trap_valid", 0)),
                        "next_pc": _to_int(obj.get("next_pc", 0)),
                    }
                )
        commit = _row_to_commit(crosscheck, obj)
        if not crosscheck._is_metadata_commit(commit):
            nonmeta_rows += 1

    label = args.label or trace_path.stem
    if unexpected_zero_rows:
        first = unexpected_zero_rows[0]
        raise SystemExit(
            "error: unexpected zero-insn boundary row in "
            f"{label}: seq={first['seq']} pc=0x{first['pc']:x} len={first['len']} "
            f"is_bstart={first['is_bstart']} is_bstop={first['is_bstop']} "
            f"wb_valid={first['wb_valid']} dst_valid={first['dst_valid']} "
            f"mem_valid={first['mem_valid']} trap_valid={first['trap_valid']} "
            f"next_pc=0x{first['next_pc']:x}"
        )
    if nonmeta_rows < args.want_commits:
        raise SystemExit(
            f"error: truncated architectural commit window for {label}: "
            f"nonmeta_rows={nonmeta_rows} want={args.want_commits} trace={trace_path}"
        )

    print(
        f"benchmark-commit-window ok: label={label} "
        f"nonmeta_rows={nonmeta_rows} legal_zero_boundaries={legal_zero_rows}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
