#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"

QEMU_TRACE=""
DUT_TRACE=""
REPORT_DIR=""
MAX_COMMITS="1000"
NORMALIZE_ROWS=""
MODE="diagnostic"
DRY_RUN=0
PRINT_QEMU_BIN=0

default_qemu_bin() {
  if [[ -n "${QEMU:-}" ]]; then
    echo "${QEMU}"
  elif [[ -x "${LINX_ROOT}/emulator/qemu/build-linx/qemu-system-linx64" ]]; then
    echo "${LINX_ROOT}/emulator/qemu/build-linx/qemu-system-linx64"
  else
    echo "${LINX_ROOT}/emulator/qemu/build/qemu-system-linx64"
  fi
}

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --qemu-trace <qemu.jsonl> --dut-trace <dut.jsonl> [options]

Options:
  --report-dir <dir>       Output report directory
  --max-commits <int>      Compare window (default: 1000)
  --normalize-rows <int>   Raw rows normalized before comparator metadata filtering
  --mode <diagnostic|failfast>
  --dry-run                Validate tool paths without comparing traces
  --print-qemu-bin         Print selected QEMU binary and exit
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --qemu-trace) QEMU_TRACE="$2"; shift 2 ;;
    --dut-trace) DUT_TRACE="$2"; shift 2 ;;
    --report-dir) REPORT_DIR="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    --normalize-rows) NORMALIZE_ROWS="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --print-qemu-bin) PRINT_QEMU_BIN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

QEMU_BIN="$(default_qemu_bin)"
if [[ "${PRINT_QEMU_BIN}" -eq 1 ]]; then
  echo "${QEMU_BIN}"
  exit 0
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "qemu-bin=${QEMU_BIN}"
  python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" --self-test
  exit 0
fi
if [[ -z "${NORMALIZE_ROWS}" ]]; then
  NORMALIZE_ROWS="${MAX_COMMITS}"
fi

if [[ -z "${QEMU_TRACE}" || -z "${DUT_TRACE}" ]]; then
  echo "error: --qemu-trace and --dut-trace are required" >&2
  usage
  exit 2
fi
if [[ ! -f "${QEMU_TRACE}" ]]; then
  echo "error: missing qemu trace: ${QEMU_TRACE}" >&2
  exit 2
fi
if [[ ! -f "${DUT_TRACE}" ]]; then
  echo "error: missing dut trace: ${DUT_TRACE}" >&2
  exit 2
fi

if [[ -z "${REPORT_DIR}" ]]; then
  REPORT_DIR="$(mktemp -d -t linxcore_chisel_xcheck.XXXXXX)"
else
  mkdir -p "${REPORT_DIR}"
fi

NORM_QEMU="${REPORT_DIR}/qemu.normalized.jsonl"
NORM_DUT="${REPORT_DIR}/dut.normalized.jsonl"
REPORT_JSON="${REPORT_DIR}/crosscheck_report.json"
REPORT_MD="${REPORT_DIR}/crosscheck_report.md"
MISMATCH_JSON="${REPORT_DIR}/crosscheck_mismatches.json"
MANIFEST_JSON="${REPORT_DIR}/crosscheck_manifest.json"

python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${QEMU_TRACE}" --output "${NORM_QEMU}" --max-rows "${NORMALIZE_ROWS}"
python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${DUT_TRACE}" --output "${NORM_DUT}" --max-rows "${NORMALIZE_ROWS}"

COMPARATOR_STATUS=0
python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${NORM_QEMU}" \
  --dut-trace "${NORM_DUT}" \
  --max-commits "${MAX_COMMITS}" \
  --mode "${MODE}" \
  --report-dir "${REPORT_DIR}" || COMPARATOR_STATUS=$?

python3 - \
  "${MANIFEST_JSON}" \
  "${REPORT_JSON}" \
  "${MISMATCH_JSON}" \
  "${REPORT_MD}" \
  "${QEMU_TRACE}" \
  "${DUT_TRACE}" \
  "${NORM_QEMU}" \
  "${NORM_DUT}" \
  "${QEMU_BIN}" \
  "${MAX_COMMITS}" \
  "${NORMALIZE_ROWS}" \
  "${MODE}" \
  "${COMPARATOR_STATUS}" \
  "${ROOT_DIR}" \
  "${LINX_ROOT}" <<'PY'
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

(
    manifest_path,
    report_json,
    mismatch_json,
    report_md,
    qemu_trace,
    dut_trace,
    norm_qemu,
    norm_dut,
    qemu_bin,
    max_commits,
    normalize_rows,
    mode,
    comparator_status,
    root_dir,
    linx_root,
) = sys.argv[1:]


def git_rev(path: str) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", path, "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except subprocess.CalledProcessError:
        return ""


def git_dirty(path: str) -> bool:
    try:
        out = subprocess.check_output(
            ["git", "-C", path, "status", "--short"],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        return bool(out.strip())
    except subprocess.CalledProcessError:
        return False


def read_json(path: str, default):
    p = Path(path)
    if not p.is_file():
        return default
    return json.loads(p.read_text(encoding="utf-8"))


report = read_json(report_json, {})
manifest = {
    "schema": "linxcore.chisel.crosscheck_manifest.v1",
    "generated_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "mode": mode,
    "max_commits": int(max_commits),
    "normalize_rows": int(normalize_rows),
    "status": "pass" if int(comparator_status) == 0 and report.get("mismatch_count", 0) == 0 else "fail",
    "comparator_status": int(comparator_status),
    "qemu_bin": qemu_bin,
    "inputs": {
        "qemu_trace": qemu_trace,
        "dut_trace": dut_trace,
    },
    "normalized": {
        "qemu_trace": norm_qemu,
        "dut_trace": norm_dut,
    },
    "reports": {
        "json": report_json,
        "markdown": report_md,
        "mismatches": mismatch_json,
        "manifest": manifest_path,
    },
    "summary": {
        "qemu_rows": report.get("qemu_rows", 0),
        "dut_rows": report.get("dut_rows", 0),
        "compared_rows": report.get("compared_rows", 0),
        "mismatch_count": report.get("mismatch_count", 0),
        "first_mismatch": report.get("first_mismatch"),
        "cbstop_counts": report.get("cbstop_counts", {}),
    },
    "git": {
        "linxcore": {
            "path": root_dir,
            "head": git_rev(root_dir),
            "dirty": git_dirty(root_dir),
        },
        "superproject": {
            "path": linx_root,
            "head": git_rev(linx_root),
            "dirty": git_dirty(linx_root),
        },
    },
}
Path(manifest_path).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"manifest_json={manifest_path}")
PY

exit "${COMPARATOR_STATUS}"
