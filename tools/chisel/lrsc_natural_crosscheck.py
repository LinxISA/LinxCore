#!/usr/bin/env python3
"""Build and evaluate the scalar LR.W/SC.W natural cross-check workload."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import textwrap
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT_DIR = Path(__file__).resolve().parents[2]
LINX_ROOT = ROOT_DIR.parents[1]
LLVM_BIN = LINX_ROOT / "compiler/llvm/build-linxisa-clang/bin"
QEMU_TRACE_RUNNER = ROOT_DIR / "tools/qemu/run_qemu_commit_trace.sh"
NATURAL_RUNNER = ROOT_DIR / "tools/chisel/run_chisel_benchmark_autonomous_top_natural.sh"

TEXT_BASE = 0x10000
DATA_BASE = 0x11000
PC_LO = TEXT_BASE
PC_HI = TEXT_BASE + 0x1E
EXPECTED_LR_SIGN_EXTENDED = 0xFFFFFFFF80000000
EXPECTED_QEMU_ZERO_EXTENDED = 0x0000000080000000
EXPECTED_STORE_VALUE = 0x123

LR_W_MASK = 0xF000707F
LR_W_MATCH = 0x2000000B
SC_W_MASK = 0xF000707F
SC_W_MATCH = 0x2000100B
SWI_MASK = 0x707F
SWI_MATCH = 0x2059


WORKLOAD_ASM = """\
.section .text,"ax",@progbits
.globl _start
_start:
  .short 0x0800
  lui 0x11, ->a5
  lr.w [a5], ->a0
  addi r0, 0x123, ->a1
  sc.w a1, [a5], ->a2
  lr.w [a5], ->a3
  swi a1, [a5, 0]
  sc.w a1, [a5], ->a4
  .short 0x0000
  .short 0x0800
  lui 0x10009, ->a5
  lui 0x5, ->a1
  addi a1, 0x555, ->a1
  swi a1, [a5, 0]
  .short 0x0000
  .space 512, 0
.section .data,"aw",@progbits
.balign 8
.globl lock_word
lock_word:
  .word 0x80000000
  .word 0x00000000
"""


LINKER_SCRIPT = f"""\
ENTRY(_start)
PHDRS {{ text PT_LOAD FLAGS(5); data PT_LOAD FLAGS(6); }}
SECTIONS {{
  . = 0x{TEXT_BASE:x};
  .text : {{ *(.text*) }} :text
  . = 0x{DATA_BASE:x};
  .data : {{ *(.data*) }} :data
}}
"""


@dataclass(frozen=True)
class TraceSummary:
    engine: str
    trace: Path | None
    status: str
    row_count: int
    lr_w: int
    sc_success: int
    sc_failure: int
    ordinary_conflict_stores: int
    first_lr_wb_data: int | None
    final_memory: int | None
    errors: tuple[str, ...]

    def to_json(self) -> dict[str, Any]:
        return {
            "engine": self.engine,
            "trace": str(self.trace) if self.trace else None,
            "status": self.status,
            "row_count": self.row_count,
            "lr_w": self.lr_w,
            "sc_success": self.sc_success,
            "sc_failure": self.sc_failure,
            "ordinary_conflict_stores": self.ordinary_conflict_stores,
            "first_lr_wb_data": self.first_lr_wb_data,
            "first_lr_wb_data_hex": hex(self.first_lr_wb_data) if self.first_lr_wb_data is not None else None,
            "final_memory": self.final_memory,
            "final_memory_hex": hex(self.final_memory) if self.final_memory is not None else None,
            "errors": list(self.errors),
        }


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_info(path: Path) -> dict[str, Any]:
    rev = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.run(["git", "-C", str(path), "diff", "--quiet"]).returncode != 0
    untracked = subprocess.check_output(
        ["git", "-C", str(path), "ls-files", "--others", "--exclude-standard"],
        text=True,
    ).splitlines()
    return {"revision": rev, "dirty": bool(dirty or untracked)}


def run(command: list[str], *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=ROOT_DIR,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def build_workload(build_dir: Path) -> dict[str, Any]:
    build_dir.mkdir(parents=True, exist_ok=True)
    asm = build_dir / "scalar_lrsc_lock_window.s"
    lds = build_dir / "scalar_lrsc_lock_window.ld"
    obj = build_dir / "scalar_lrsc_lock_window.o"
    elf = build_dir / "scalar_lrsc_lock_window.elf"
    objdump = build_dir / "scalar_lrsc_lock_window.objdump"

    asm.write_text(WORKLOAD_ASM, encoding="utf-8")
    lds.write_text(LINKER_SCRIPT, encoding="utf-8")

    llvm_mc = LLVM_BIN / "llvm-mc"
    ld_lld = LLVM_BIN / "ld.lld"
    llvm_objdump = LLVM_BIN / "llvm-objdump"
    missing = [str(p) for p in (llvm_mc, ld_lld, llvm_objdump) if not os.access(p, os.X_OK)]
    if missing:
        raise RuntimeError("missing LLVM Linx tool(s): " + ", ".join(missing))

    mc = run([str(llvm_mc), "-triple=linx64", "-filetype=obj", "-o", str(obj), str(asm)])
    if mc.returncode != 0:
        raise RuntimeError("llvm-mc failed:\n" + mc.stdout)
    ld = run([str(ld_lld), "-T", str(lds), "-o", str(elf), str(obj)])
    if ld.returncode != 0:
        raise RuntimeError("ld.lld failed:\n" + ld.stdout)
    dis = run([str(llvm_objdump), "-d", "-s", str(elf)])
    objdump.write_text(dis.stdout, encoding="utf-8")
    if dis.returncode != 0:
        raise RuntimeError("llvm-objdump failed:\n" + dis.stdout)

    return {
        "asm": str(asm),
        "linker_script": str(lds),
        "elf": str(elf),
        "elf_sha256": sha256(elf),
        "objdump": str(objdump),
        "text_base": TEXT_BASE,
        "data_base": DATA_BASE,
        "pc_lo": PC_LO,
        "pc_hi": PC_HI,
    }


def iter_jsonl(path: Path, *, max_rows: int = 100000) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        for index, line in enumerate(f):
            if index >= max_rows:
                raise ValueError(f"trace exceeds bounded parser row limit {max_rows}: {path}")
            if not line.strip():
                continue
            yield json.loads(line)


def is_lr_w(insn: int) -> bool:
    return (insn & LR_W_MASK) == LR_W_MATCH


def is_sc_w(insn: int) -> bool:
    return (insn & SC_W_MASK) == SC_W_MATCH


def is_swi(insn: int) -> bool:
    return (insn & SWI_MASK) == SWI_MATCH


def summarize_trace(path: Path, engine: str) -> TraceSummary:
    errors: list[str] = []
    row_count = 0
    lr_w = 0
    sc_success = 0
    sc_failure = 0
    ordinary_conflict_stores = 0
    first_lr_wb_data: int | None = None
    final_memory = 0x80000000

    if not path.exists() or path.stat().st_size == 0:
        return TraceSummary(engine, path, "fail", 0, 0, 0, 0, 0, None, None, ("missing or empty trace",))

    for row in iter_jsonl(path):
        pc = int(row.get("pc", -1))
        if pc < PC_LO or pc > PC_HI:
            continue
        row_count += 1
        insn = int(row.get("insn", 0))
        wb_valid = int(row.get("wb_valid", row.get("dst_valid", 0))) != 0
        wb_data = int(row.get("wb_data", row.get("dst_data", 0))) & ((1 << 64) - 1)
        if is_lr_w(insn):
            lr_w += 1
            if first_lr_wb_data is None and wb_valid:
                first_lr_wb_data = wb_data
        elif is_sc_w(insn):
            if not wb_valid:
                errors.append(f"SC.W at pc=0x{pc:x} has no status writeback")
            elif wb_data == 0:
                sc_success += 1
                final_memory = EXPECTED_STORE_VALUE
            elif wb_data == 1:
                sc_failure += 1
            else:
                errors.append(f"SC.W at pc=0x{pc:x} wrote unsupported status 0x{wb_data:x}")
        elif is_swi(insn) and int(row.get("mem_valid", 0)) and int(row.get("mem_is_store", 0)):
            ordinary_conflict_stores += 1
            final_memory = int(row.get("mem_wdata", 0)) & 0xFFFFFFFF

    if lr_w < 2:
        errors.append(f"expected at least 2 LR.W rows, saw {lr_w}")
    if sc_success < 1:
        errors.append("expected at least one SC.W success status 0")
    if sc_failure < 1:
        errors.append("expected at least one SC.W failure status 1")
    if ordinary_conflict_stores < 1:
        errors.append("expected at least one ordinary same-line store to invalidate reservation")
    if first_lr_wb_data != EXPECTED_LR_SIGN_EXTENDED:
        errors.append(
            "first LR.W sign-extension mismatch: "
            f"expected 0x{EXPECTED_LR_SIGN_EXTENDED:x}, got "
            f"{'missing' if first_lr_wb_data is None else hex(first_lr_wb_data)}"
        )
    if final_memory != EXPECTED_STORE_VALUE:
        errors.append(f"final memory mismatch: expected 0x{EXPECTED_STORE_VALUE:x}, got 0x{final_memory:x}")

    return TraceSummary(
        engine=engine,
        trace=path,
        status="pass" if not errors else "fail",
        row_count=row_count,
        lr_w=lr_w,
        sc_success=sc_success,
        sc_failure=sc_failure,
        ordinary_conflict_stores=ordinary_conflict_stores,
        first_lr_wb_data=first_lr_wb_data,
        final_memory=final_memory,
        errors=tuple(errors),
    )


def run_qemu(elf: Path, build_dir: Path, qemu_seconds: int) -> tuple[dict[str, Any], TraceSummary]:
    trace = build_dir / "traces/qemu.lrsc.commit.jsonl"
    trace.parent.mkdir(parents=True, exist_ok=True)
    command = [
        str(QEMU_TRACE_RUNNER),
        "--elf",
        str(elf),
        "--out",
        str(trace),
        "--max-seconds",
        str(qemu_seconds),
        "--pc-lo",
        hex(PC_LO),
        "--pc-hi",
        hex(PC_HI),
        "--",
        "-nographic",
        "-monitor",
        "none",
        "-machine",
        "virt",
        "-m",
        "1280M",
        "-kernel",
        str(elf),
    ]
    result = run(command)
    summary = summarize_trace(trace, "qemu")
    meta = {
        "command": command,
        "returncode": result.returncode,
        "stdout_tail": result.stdout[-4000:],
        "timeout_returncode_allowed": result.returncode == 124 and summary.row_count > 0,
    }
    return meta, summary


def run_chisel(elf: Path, build_dir: Path, max_cycles: int) -> tuple[dict[str, Any], TraceSummary]:
    chisel_dir = build_dir / "chisel-natural"
    trace = chisel_dir / "traces/dut.commit.jsonl"
    command = [
        str(NATURAL_RUNNER),
        "--elf",
        str(elf),
        "--build-dir",
        str(chisel_dir),
        "--max-cycles",
        str(max_cycles),
    ]
    result = run(command)
    summary = summarize_trace(trace, "chisel")
    if result.returncode != 0 and summary.row_count == 0:
        summary = TraceSummary(
            engine="chisel",
            trace=trace,
            status="fail",
            row_count=summary.row_count,
            lr_w=summary.lr_w,
            sc_success=summary.sc_success,
            sc_failure=summary.sc_failure,
            ordinary_conflict_stores=summary.ordinary_conflict_stores,
            first_lr_wb_data=summary.first_lr_wb_data,
            final_memory=summary.final_memory,
            errors=summary.errors + (f"natural runner failed with returncode {result.returncode}",),
        )
    meta = {
        "command": command,
        "returncode": result.returncode,
        "stdout_tail": result.stdout[-4000:],
    }
    return meta, summary


def build_manifest(
    build_dir: Path,
    workload: dict[str, Any],
    qemu_meta: dict[str, Any],
    qemu_summary: TraceSummary,
    chisel_meta: dict[str, Any] | None,
    chisel_summary: TraceSummary | None,
    chisel_skipped: bool,
) -> dict[str, Any]:
    summaries = {"qemu": qemu_summary.to_json()}
    if chisel_summary is not None:
        summaries["chisel"] = chisel_summary.to_json()

    errors: list[str] = []
    errors.extend(f"qemu: {err}" for err in qemu_summary.errors)
    if chisel_summary is not None:
        errors.extend(f"chisel: {err}" for err in chisel_summary.errors)
    if chisel_skipped:
        errors.append("chisel natural run skipped by request")

    return {
        "schema": "linxcore.scalar_lrsc_natural_crosscheck.v1",
        "status": "pass" if not errors else "fail",
        "errors": errors,
        "expected": {
            "lr_w": 2,
            "sc_success": 1,
            "sc_failure": 1,
            "ordinary_conflict_stores": 1,
            "first_lr_wb_data": EXPECTED_LR_SIGN_EXTENDED,
            "first_lr_wb_data_hex": hex(EXPECTED_LR_SIGN_EXTENDED),
            "known_qemu_lr_w_zero_extend_value_hex": hex(EXPECTED_QEMU_ZERO_EXTENDED),
            "final_memory": EXPECTED_STORE_VALUE,
            "final_memory_hex": hex(EXPECTED_STORE_VALUE),
        },
        "workload": workload,
        "oracle": {
            "sail_contract": {
                "source": str(LINX_ROOT / "isa/sail/model/execute/execute.sail"),
                "lr_w": "mem_load32_le followed by sext32_from32",
                "sc_w": "status 0 on reservation hit, status 1 on miss, clear reservation",
            },
            "linxcore_model_contract": {
                "source": str(LINX_ROOT / "tools/LinxCoreModel/emulator/engine/AaccelssMemoryEngine.cpp"),
                "note": "Model classifies LR/SC as atomic memory operations; this gate uses the Sail value oracle plus executable QEMU/Chisel traces.",
            },
        },
        "runs": {
            "qemu": qemu_meta,
            "chisel": chisel_meta,
        },
        "summaries": summaries,
        "git": {
            "superproject": git_info(LINX_ROOT),
            "linxcore": git_info(ROOT_DIR),
            "qemu": git_info(LINX_ROOT / "emulator/qemu"),
        },
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


def self_test() -> None:
    import tempfile

    def row(pc: int, insn: int, **extra: int) -> dict[str, int]:
        base = {
            "pc": pc,
            "insn": insn,
            "wb_valid": 0,
            "wb_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_wdata": 0,
        }
        base.update(extra)
        return base

    lr0 = LR_W_MATCH | (2 << 7) | (7 << 15)
    sc0 = SC_W_MATCH | (4 << 7) | (3 << 15) | (7 << 20)
    swi = SWI_MATCH | (3 << 15) | (7 << 20)
    sc1 = SC_W_MATCH | (6 << 7) | (3 << 15) | (7 << 20)
    lr1 = LR_W_MATCH | (5 << 7) | (7 << 15)
    rows = [
        row(PC_LO + 6, lr0, wb_valid=1, wb_data=EXPECTED_LR_SIGN_EXTENDED),
        row(PC_LO + 14, sc0, wb_valid=1, wb_data=0),
        row(PC_LO + 18, lr1, wb_valid=1, wb_data=EXPECTED_STORE_VALUE),
        row(PC_LO + 22, swi, mem_valid=1, mem_is_store=1, mem_wdata=EXPECTED_STORE_VALUE),
        row(PC_LO + 26, sc1, wb_valid=1, wb_data=1),
    ]
    with tempfile.TemporaryDirectory(prefix="linx-lrsc-self-test-") as td:
        trace = Path(td) / "trace.jsonl"
        trace.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
        ok = summarize_trace(trace, "synthetic")
        assert ok.status == "pass", ok
        rows[0]["wb_data"] = EXPECTED_QEMU_ZERO_EXTENDED
        trace.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
        bad = summarize_trace(trace, "synthetic")
        assert bad.status == "fail"
        assert any("sign-extension mismatch" in e for e in bad.errors)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, default=ROOT_DIR / "generated/chisel-lrsc-natural-crosscheck")
    parser.add_argument("--manifest", type=Path, default=None)
    parser.add_argument("--qemu-max-seconds", type=int, default=1)
    parser.add_argument("--chisel-max-cycles", type=int, default=20000)
    parser.add_argument("--skip-chisel", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        self_test()
        print("lrsc-natural-crosscheck self-test: ok")
        return 0

    build_dir = args.build_dir if args.build_dir.is_absolute() else ROOT_DIR / args.build_dir
    manifest_path = args.manifest or build_dir / "report/lrsc_natural_crosscheck_manifest.json"
    if not manifest_path.is_absolute():
        manifest_path = ROOT_DIR / manifest_path

    workload = build_workload(build_dir)
    elf = Path(workload["elf"])
    qemu_meta, qemu_summary = run_qemu(elf, build_dir, args.qemu_max_seconds)
    chisel_meta = None
    chisel_summary = None
    if not args.skip_chisel:
        chisel_meta, chisel_summary = run_chisel(elf, build_dir, args.chisel_max_cycles)

    manifest = build_manifest(
        build_dir,
        workload,
        qemu_meta,
        qemu_summary,
        chisel_meta,
        chisel_summary,
        args.skip_chisel,
    )
    write_json(manifest_path, manifest)
    print(f"lrsc-natural-crosscheck-manifest={manifest_path}")
    print(f"lrsc-natural-crosscheck-status={manifest['status']}")
    if manifest["errors"]:
        print("lrsc-natural-crosscheck-errors:")
        for err in manifest["errors"]:
            print(f"  - {err}")
    return 0 if manifest["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
