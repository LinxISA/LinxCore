#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"

QEMU_TRACE=""
DUT_TRACE=""
REPORT_DIR=""
MAX_COMMITS="1000"
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

python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${QEMU_TRACE}" --output "${NORM_QEMU}" --max-rows "${MAX_COMMITS}"
python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${DUT_TRACE}" --output "${NORM_DUT}" --max-rows "${MAX_COMMITS}"

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${NORM_QEMU}" \
  --dut-trace "${NORM_DUT}" \
  --max-commits "${MAX_COMMITS}" \
  --mode "${MODE}" \
  --report-dir "${REPORT_DIR}"
