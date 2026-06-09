#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
LINXCORE_ROOT = SCRIPT_DIR.parents[1]
REPO_ROOT = LINXCORE_ROOT.parents[1]

ELF_TO_MEMH = LINXCORE_ROOT / "tools" / "image" / "elf_to_memh.sh"
QEMU_TRACE_RUNNER = LINXCORE_ROOT / "tools" / "qemu" / "run_qemu_commit_trace.sh"
TB_RUNNER = LINXCORE_ROOT / "tools" / "generate" / "run_linxcore_top_cpp.sh"
CROSSCHECK = LINXCORE_ROOT / "tools" / "trace" / "crosscheck_qemu_linxcore.py"

DEFAULT_BOOT_SP = 0x0000000007FEFFF0


@dataclass
class CommandResult:
    returncode: int
    log_path: Path


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def _run_and_log(cmd: list[str], log_path: Path, *, env: dict[str, str] | None = None) -> CommandResult:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        env=env,
        text=True,
    )
    log_path.write_text(proc.stdout, encoding="utf-8")
    return CommandResult(returncode=proc.returncode, log_path=log_path)


def _find_llvm_readelf() -> Path:
    env_path = os.environ.get("LLVM_READELF", "").strip()
    if env_path:
        p = Path(env_path)
        if p.is_file() and os.access(p, os.X_OK):
            return p

    cands = [
        REPO_ROOT / "compiler" / "llvm" / "build-linxisa-clang" / "bin" / "llvm-readelf",
        Path.home() / "llvm-project" / "build-linxisa-clang" / "bin" / "llvm-readelf",
    ]
    for cand in cands:
        if cand.is_file() and os.access(cand, os.X_OK):
            return cand

    host = shutil.which("llvm-readelf")
    if host:
        return Path(host)

    raise SystemExit("error: llvm-readelf not found; set LLVM_READELF=/path/to/llvm-readelf")


def _entry_from_elf(readelf: Path, elf: Path) -> int:
    proc = subprocess.run(
        [str(readelf), "-h", str(elf)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        text=True,
    )
    if proc.returncode != 0:
        raise SystemExit(f"error: failed to read ELF header: {elf}")
    for line in proc.stdout.splitlines():
        s = line.strip()
        if s.startswith("Entry point address:"):
            value = s.split(":", 1)[1].strip()
            return int(value, 0)
    raise SystemExit(f"error: entry point not found in ELF header: {elf}")


def _parse_u64(name: str, value: Any) -> int:
    if isinstance(value, int):
        if value < 0:
            raise SystemExit(f"error: {name} must be >= 0")
        return value
    if isinstance(value, str):
        try:
            parsed = int(value, 0)
        except ValueError as exc:
            raise SystemExit(f"error: invalid {name}: {value!r}") from exc
        if parsed < 0:
            raise SystemExit(f"error: {name} must be >= 0")
        return parsed
    raise SystemExit(f"error: invalid {name}: {value!r}")


def _parse_qemu_args(raw: Any, elf: Path) -> list[str]:
    if raw is None:
        return ["-nographic", "-monitor", "none", "-machine", "virt", "-kernel", str(elf)]
    if isinstance(raw, str):
        items = shlex.split(raw)
    elif isinstance(raw, list):
        items = [str(x) for x in raw]
    else:
        raise SystemExit(f"error: qemu_args must be list|string (got {type(raw).__name__})")

    out: list[str] = []
    for item in items:
        out.append(str(elf) if item == "{elf}" else item)
    return out


def _load_suite(path: Path) -> dict[str, Any]:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"error: invalid suite json: {path}: {exc}") from exc
    if not isinstance(obj, dict):
        raise SystemExit(f"error: suite root must be object: {path}")
    if "cases" not in obj or not isinstance(obj["cases"], list):
        raise SystemExit("error: suite must contain cases[]")
    return obj


def _write_summary_md(path: Path, summary: dict[str, Any]) -> None:
    lines: list[str] = []
    lines.append("# Benchmark Suite XCheck Summary")
    lines.append("")
    lines.append("## Run")
    lines.append("")
    lines.append(f"- suite: `{summary['suite_name']}`")
    lines.append(f"- schema: `{summary.get('suite_schema_version', '')}`")
    lines.append(f"- mode: `{summary['mode']}`")
    lines.append(f"- report_only: `{str(summary['report_only']).lower()}`")
    lines.append(f"- continue_on_fail: `{str(summary['continue_on_fail']).lower()}`")
    lines.append(f"- ok: `{str(summary['ok']).lower()}`")
    lines.append(f"- infra_ok: `{str(summary['infra_ok']).lower()}`")
    lines.append(f"- elapsed_sec: `{summary['elapsed_sec']}`")
    lines.append("")
    lines.append("## Counts")
    lines.append("")
    counts = summary["counts"]
    lines.append(f"- total: `{counts['total']}`")
    lines.append(f"- passed: `{counts['passed']}`")
    lines.append(f"- parity_failed: `{counts['parity_failed']}`")
    lines.append(f"- infra_failed: `{counts['infra_failed']}`")
    lines.append("")
    lines.append("## Cases")
    lines.append("")
    lines.append("| Case | Bench | Status | Compared | Mismatches | QEMU Trace | DUT Trace | Report |")
    lines.append("|---|---|---|---:|---:|---|---|---|")

    for row in summary.get("cases", []):
        report_json = row.get("artifacts", {}).get("crosscheck_report_json", "")
        lines.append(
            "| "
            f"`{row.get('id', '')}` | "
            f"`{row.get('bench', '')}` | "
            f"`{row.get('status', '')}` | "
            f"`{row.get('compared_rows', 0)}` | "
            f"`{row.get('mismatch_count', 0)}` | "
            f"`{row.get('artifacts', {}).get('qemu_trace', '')}` | "
            f"`{row.get('artifacts', {}).get('dut_trace', '')}` | "
            f"`{report_json}` |"
        )

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Run generic benchmark suite QEMU<->LinxCore xcheck.")
    ap.add_argument("--suite", required=True, help="Suite JSON path")
    ap.add_argument("--out-dir", required=True, help="Output directory")
    ap.add_argument("--max-commits", type=int, default=1000)
    ap.add_argument("--tb-max-cycles", type=int, default=50000000)
    ap.add_argument("--qemu-max-seconds", type=int, default=0)
    ap.add_argument("--mode", choices=("failfast", "diagnostic"), default="failfast")
    ap.add_argument("--report-only", action="store_true")
    ap.add_argument("--continue-on-fail", action="store_true")
    args = ap.parse_args(argv)

    if args.max_commits <= 0:
        raise SystemExit("error: --max-commits must be > 0")
    if args.tb_max_cycles <= 0:
        raise SystemExit("error: --tb-max-cycles must be > 0")
    if args.qemu_max_seconds < 0:
        raise SystemExit("error: --qemu-max-seconds must be >= 0")

    for required in (ELF_TO_MEMH, QEMU_TRACE_RUNNER, TB_RUNNER, CROSSCHECK):
        if not required.is_file():
            raise SystemExit(f"error: missing required tool: {required}")

    suite_path = Path(os.path.expanduser(args.suite)).resolve()
    if not suite_path.is_file():
        raise SystemExit(f"error: suite not found: {suite_path}")
    suite = _load_suite(suite_path)

    cases = suite.get("cases", [])
    if not cases:
        raise SystemExit("error: suite has no cases")

    out_dir = Path(os.path.expanduser(args.out_dir)).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    readelf = _find_llvm_readelf()

    started = _utc_now()
    t0 = time.monotonic()

    case_rows: list[dict[str, Any]] = []
    parity_failed = 0
    infra_failed = 0
    passed = 0

    for idx, raw_case in enumerate(cases, start=1):
        if not isinstance(raw_case, dict):
            raise SystemExit(f"error: suite case[{idx - 1}] must be object")

        case_id = str(raw_case.get("id", "")).strip() or f"case_{idx:03d}"
        bench = str(raw_case.get("bench", "")).strip() or case_id
        case_dir = out_dir / case_id
        report_dir = case_dir / "report"
        logs_dir = case_dir / "logs"
        case_dir.mkdir(parents=True, exist_ok=True)
        report_dir.mkdir(parents=True, exist_ok=True)
        logs_dir.mkdir(parents=True, exist_ok=True)

        elf_raw = raw_case.get("elf")
        if not isinstance(elf_raw, str) or not elf_raw:
            raise SystemExit(f"error: case {case_id}: missing elf path")
        elf = Path(os.path.expanduser(elf_raw)).resolve()
        if not elf.is_file():
            raise SystemExit(f"error: case {case_id}: elf not found: {elf}")

        boot_pc_raw = raw_case.get("boot_pc", "auto")
        if isinstance(boot_pc_raw, str) and boot_pc_raw.lower() == "auto":
            boot_pc = _entry_from_elf(readelf, elf)
        else:
            boot_pc = _parse_u64(f"{case_id}.boot_pc", boot_pc_raw)

        boot_sp = _parse_u64(f"{case_id}.boot_sp", raw_case.get("boot_sp", DEFAULT_BOOT_SP))
        case_max_commits = _parse_u64(
            f"{case_id}.max_commits",
            raw_case.get("max_commits", args.max_commits),
        )
        case_tb_max_cycles = _parse_u64(
            f"{case_id}.tb_max_cycles",
            raw_case.get("tb_max_cycles", args.tb_max_cycles),
        )

        qemu_args = _parse_qemu_args(raw_case.get("qemu_args"), elf)

        memh_path = case_dir / "program.memh"
        qemu_trace = case_dir / "qemu_trace.jsonl"
        dut_trace = case_dir / "dut_trace.jsonl"

        row: dict[str, Any] = {
            "id": case_id,
            "bench": bench,
            "elf": str(elf),
            "boot_pc": f"0x{boot_pc:x}",
            "boot_sp": f"0x{boot_sp:x}",
            "mode": args.mode,
            "max_commits": case_max_commits,
            "tb_max_cycles": case_tb_max_cycles,
            "tags": raw_case.get("tags", []),
            "status": "infra_fail",
            "ok": False,
            "parity_ok": False,
            "infra_ok": False,
            "compared_rows": 0,
            "mismatch_count": 0,
            "infra_reasons": [],
            "artifacts": {
                "case_dir": str(case_dir),
                "memh": str(memh_path),
                "qemu_trace": str(qemu_trace),
                "dut_trace": str(dut_trace),
                "crosscheck_report_json": str(report_dir / "crosscheck_report.json"),
                "crosscheck_report_md": str(report_dir / "crosscheck_report.md"),
                "crosscheck_mismatches_json": str(report_dir / "crosscheck_mismatches.json"),
            },
            "logs": {
                "elf_to_memh": str(logs_dir / "elf_to_memh.log"),
                "qemu_trace": str(logs_dir / "qemu_trace.log"),
                "tb": str(logs_dir / "tb.log"),
                "crosscheck": str(logs_dir / "crosscheck.log"),
            },
        }

        infra_reasons: list[str] = []

        memh_cmd = ["bash", str(ELF_TO_MEMH), str(elf), str(memh_path)]
        memh_res = _run_and_log(memh_cmd, Path(row["logs"]["elf_to_memh"]))
        row["elf_to_memh_rc"] = memh_res.returncode
        if memh_res.returncode != 0 or not memh_path.is_file():
            infra_reasons.append("elf_to_memh_failed")

        if not infra_reasons:
            qemu_cmd = [
                "bash",
                str(QEMU_TRACE_RUNNER),
                "--elf",
                str(elf),
                "--out",
                str(qemu_trace),
            ]
            if args.qemu_max_seconds > 0:
                qemu_cmd.extend(["--max-seconds", str(args.qemu_max_seconds)])
            qemu_cmd.append("--")
            qemu_cmd.extend(qemu_args)
            qemu_res = _run_and_log(qemu_cmd, Path(row["logs"]["qemu_trace"]))
            row["qemu_rc"] = qemu_res.returncode
            row["qemu_args"] = qemu_args
            if qemu_res.returncode != 0:
                infra_reasons.append("qemu_trace_failed")
            if not qemu_trace.is_file() or qemu_trace.stat().st_size == 0:
                infra_reasons.append("qemu_trace_missing")

        tb_env = dict(os.environ)
        tb_env.update(
            {
                "PYC_BOOT_PC": f"0x{boot_pc:x}",
                "PYC_BOOT_SP": f"0x{boot_sp:x}",
                "PYC_MAX_COMMITS": str(case_max_commits),
                "PYC_MAX_CYCLES": str(case_tb_max_cycles),
                "PYC_COMMIT_TRACE": str(dut_trace),
                "PYC_QEMU_TRACE": str(qemu_trace),
                "PYC_XCHECK_MODE": args.mode,
                "PYC_XCHECK_MAX_COMMITS": str(case_max_commits),
                "PYC_XCHECK_REPORT": str(report_dir / "crosscheck"),
            }
        )

        tb_rc: int | None = None
        if not infra_reasons:
            tb_cmd = ["bash", str(TB_RUNNER), str(memh_path)]
            tb_res = _run_and_log(tb_cmd, Path(row["logs"]["tb"]), env=tb_env)
            tb_rc = tb_res.returncode
            row["tb_rc"] = tb_rc
            if not dut_trace.is_file() or dut_trace.stat().st_size == 0:
                infra_reasons.append("dut_trace_missing")

        compare_report_json = Path(row["artifacts"]["crosscheck_report_json"])
        compare_rc = None
        mismatch_count = 0
        compared_rows = 0

        if not infra_reasons:
            compare_cmd = [
                sys.executable,
                str(CROSSCHECK),
                "--qemu-trace",
                str(qemu_trace),
                "--dut-trace",
                str(dut_trace),
                "--mode",
                args.mode,
                "--max-commits",
                str(case_max_commits),
                "--report-dir",
                str(report_dir),
            ]
            compare_res = _run_and_log(compare_cmd, Path(row["logs"]["crosscheck"]))
            compare_rc = compare_res.returncode
            row["crosscheck_rc"] = compare_rc

            if compare_report_json.is_file():
                try:
                    report_obj = json.loads(compare_report_json.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    infra_reasons.append("crosscheck_report_invalid")
                    report_obj = {}

                mismatch_count = int(report_obj.get("mismatch_count", 0)) if report_obj else 0
                compared_rows = int(report_obj.get("compared_rows", report_obj.get("compared", 0))) if report_obj else 0
            else:
                infra_reasons.append("crosscheck_report_missing")

            if compare_rc not in {0, None} and mismatch_count == 0:
                infra_reasons.append("crosscheck_failed")

            if tb_rc not in {0, None} and mismatch_count == 0:
                infra_reasons.append("tb_failed")

        parity_fail = mismatch_count > 0
        infra_fail = len(infra_reasons) > 0

        row["compared_rows"] = compared_rows
        row["mismatch_count"] = mismatch_count
        row["infra_reasons"] = infra_reasons

        if infra_fail:
            row["status"] = "infra_fail"
            row["ok"] = False
            row["infra_ok"] = False
            row["parity_ok"] = False
            infra_failed += 1
        elif parity_fail:
            row["status"] = "parity_fail"
            row["ok"] = False
            row["infra_ok"] = True
            row["parity_ok"] = False
            parity_failed += 1
        else:
            row["status"] = "ok"
            row["ok"] = True
            row["infra_ok"] = True
            row["parity_ok"] = True
            passed += 1

        case_rows.append(row)

        if row["status"] != "ok" and not args.continue_on_fail:
            break

    total = len(case_rows)
    summary_ok = (parity_failed == 0) and (infra_failed == 0)
    infra_ok = infra_failed == 0

    summary = {
        "schema_version": "linxcore-benchmark-suite-xcheck-v1",
        "suite_path": str(suite_path),
        "suite_name": str(suite.get("suite_name", suite_path.stem)),
        "suite_schema_version": str(suite.get("schema_version", "")),
        "mode": args.mode,
        "report_only": bool(args.report_only),
        "continue_on_fail": bool(args.continue_on_fail),
        "started_at_utc": started,
        "finished_at_utc": _utc_now(),
        "elapsed_sec": round(time.monotonic() - t0, 3),
        "ok": summary_ok,
        "infra_ok": infra_ok,
        "counts": {
            "total": total,
            "passed": passed,
            "parity_failed": parity_failed,
            "infra_failed": infra_failed,
        },
        "cases": case_rows,
    }

    summary_json = out_dir / "summary.json"
    summary_md = out_dir / "summary.md"
    summary_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _write_summary_md(summary_md, summary)

    print(f"summary_json={summary_json}")
    print(f"summary_md={summary_md}")
    print(f"ok={str(summary_ok).lower()} infra_ok={str(infra_ok).lower()} report_only={int(args.report_only)}")

    if infra_failed > 0:
        return 2
    if parity_failed > 0 and not args.report_only:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
