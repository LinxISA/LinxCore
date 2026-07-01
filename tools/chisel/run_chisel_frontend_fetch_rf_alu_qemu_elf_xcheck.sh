#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-frontend-fetch-rf-alu-qemu-elf-xcheck}"
if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
ELF=""
QEMU_BIN=""
EXPECTED_ROWS="${EXPECTED_ROWS:-3}"
CAPTURE_ROWS="${CAPTURE_ROWS:-}"
MAX_SECONDS="${MAX_SECONDS:-30}"
PC_LO=""
PC_HI=""
ALLOW_BLOCK_MARKERS=0
ALLOW_BLOCK_LOOP_REENTRY=0
MARKER_ROWS=0

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --elf <program.elf> [options] -- [qemu args]

Options:
  --build-dir <dir>       Output root (default: ${BUILD_DIR})
  --qemu-bin <path>       QEMU binary. Defaults to the Chisel cross-check QEMU.
  --expected-rows <int>   Reduced scalar rows to extract/compare (default: ${EXPECTED_ROWS}; 0 means all)
  --capture-rows <int>    Filtered QEMU rows to capture before stopping QEMU
  --max-seconds <int>     Watchdog timeout for QEMU capture (default: ${MAX_SECONDS})
  --pc-lo <hex>           Optional QEMU commit-trace PC filter low bound
  --pc-hi <hex>           Optional QEMU commit-trace PC filter high bound
  --allow-block-markers   Preserve legal BSTART/BSTOP rows as DUT-only skip rows
  --allow-block-loop-reentry
                          Allow dynamic FALL-block re-entry rows in the QEMU reducer
  --marker-rows           Build the non-default marker-row top and admit legal
                          marker rows into ROB, then filter validated marker
                          commits from the scalar comparator stream

This wrapper captures a bounded QEMU commit JSONL prefix from a direct-boot ELF,
validates that the selected rows are inside the current reduced scalar
ADD/ADDI/ADDTPC/C.MOVI/C.MOVR envelope, extracts the same ELF into sparse fetch
memory, and then runs LinxCoreFrontendFetchRfAluTraceTop through the neutral comparator.
With --allow-block-markers, legal BSTART/BSTOP rows are consumed by the reduced
frontend/ROB path as skip rows and are not written to the comparator trace.
With --marker-rows, the same reduced expected stream still marks legal markers
as skip rows for comparator filtering, but the marker-row top must admit and
retire those rows before the following scalar rows compare.
USAGE
}

QEMU_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --expected-rows) EXPECTED_ROWS="$2"; shift 2 ;;
    --capture-rows) CAPTURE_ROWS="$2"; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --pc-lo) PC_LO="$2"; shift 2 ;;
    --pc-hi) PC_HI="$2"; shift 2 ;;
    --allow-block-markers) ALLOW_BLOCK_MARKERS=1; shift ;;
    --allow-block-loop-reentry) ALLOW_BLOCK_LOOP_REENTRY=1; shift ;;
    --marker-rows) MARKER_ROWS=1; shift ;;
    --)
      shift
      QEMU_ARGS=("$@")
      break
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
QEMU_TRACE="${TRACE_DIR}/qemu.live.raw.jsonl"
QEMU_FIFO="${TRACE_DIR}/qemu.live.raw.fifo"
QEMU_TRACE_RUNNER="${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh"
FETCH_RUNNER="${ROOT_DIR}/tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh"
QEMU_CROSSCHECK_RUNNER="${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh"
qemu_pid=""
reader_pid=""
watchdog_pid=""
producer_watch_pid=""

if [[ -z "${ELF}" ]]; then
  echo "error: --elf is required" >&2
  usage
  exit 2
fi
if [[ "${ELF}" != /* ]]; then
  ELF="${ROOT_DIR}/${ELF}"
fi
if [[ ! -f "${ELF}" ]]; then
  echo "error: ELF not found: ${ELF}" >&2
  exit 2
fi
if [[ -z "${QEMU_BIN}" ]]; then
  QEMU_BIN="$(bash "${QEMU_CROSSCHECK_RUNNER}" --print-qemu-bin)"
fi
if [[ ! -x "${QEMU_BIN}" ]]; then
  echo "error: qemu binary not found: ${QEMU_BIN}" >&2
  exit 2
fi
if [[ ! "${EXPECTED_ROWS}" =~ ^[0-9]+$ ]]; then
  echo "error: --expected-rows must be a non-negative integer" >&2
  exit 2
fi
if [[ "${MARKER_ROWS}" == "1" && "${ALLOW_BLOCK_MARKERS}" != "1" ]]; then
  echo "error: --marker-rows requires --allow-block-markers" >&2
  exit 2
fi
if [[ -z "${CAPTURE_ROWS}" ]]; then
  if [[ "${EXPECTED_ROWS}" -gt 0 ]]; then
    CAPTURE_ROWS="${EXPECTED_ROWS}"
  else
    CAPTURE_ROWS=128
  fi
fi
if [[ ! "${CAPTURE_ROWS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "error: --capture-rows must be a positive integer" >&2
  exit 2
fi

qemu_pids_for_elf() {
  ps -Ao pid=,command= | awk -v q="${QEMU_BIN}" -v e="-kernel ${ELF}" '
    index($0, q) && index($0, e) { print $1 }
  '
}

stop_qemu_for_elf() {
  local pids=""
  pids="$(qemu_pids_for_elf || true)"
  if [[ -n "${pids}" ]]; then
    while IFS= read -r pid; do
      [[ -z "${pid}" ]] && continue
      kill "${pid}" >/dev/null 2>&1 || true
    done <<< "${pids}"
    sleep 0.2
    pids="$(qemu_pids_for_elf || true)"
    if [[ -n "${pids}" ]]; then
      while IFS= read -r pid; do
        [[ -z "${pid}" ]] && continue
        kill -9 "${pid}" >/dev/null 2>&1 || true
      done <<< "${pids}"
    fi
  fi
}

cleanup() {
  if [[ -n "${watchdog_pid}" ]]; then kill "${watchdog_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${producer_watch_pid}" ]]; then kill "${producer_watch_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${qemu_pid}" ]]; then kill "${qemu_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${reader_pid}" ]]; then kill "${reader_pid}" >/dev/null 2>&1 || true; fi
  stop_qemu_for_elf
  rm -f "${QEMU_FIFO}"
}
trap cleanup EXIT INT TERM

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"
rm -f "${QEMU_TRACE}" "${QEMU_FIFO}"
mkfifo "${QEMU_FIFO}"

qemu_cmd=(
  bash "${QEMU_TRACE_RUNNER}"
  --elf "${ELF}"
  --out "${QEMU_FIFO}"
  --qemu-bin "${QEMU_BIN}"
  --max-seconds "${MAX_SECONDS}"
)
if [[ -n "${PC_LO}" ]]; then
  qemu_cmd+=(--pc-lo "${PC_LO}")
fi
if [[ -n "${PC_HI}" ]]; then
  qemu_cmd+=(--pc-hi "${PC_HI}")
fi
if [[ "${#QEMU_ARGS[@]}" -ne 0 ]]; then
  qemu_cmd+=(-- "${QEMU_ARGS[@]}")
fi

python3 - "${QEMU_FIFO}" "${QEMU_TRACE}" "${CAPTURE_ROWS}" <<'PY' &
import sys

fifo_path, out_path, rows_s = sys.argv[1:4]
max_rows = int(rows_s)

try:
    with open(fifo_path, "r", encoding="utf-8", errors="replace") as src:
        with open(out_path, "w", encoding="utf-8") as dst:
            for index, line in enumerate(src):
                if index >= max_rows:
                    break
                dst.write(line)
except KeyboardInterrupt:
    raise SystemExit(130)
except BrokenPipeError:
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
PY
reader_pid=$!
"${qemu_cmd[@]}" >/dev/null &
qemu_pid=$!

(
  while kill -0 "${qemu_pid}" >/dev/null 2>&1; do
    sleep 0.1
  done
  if [[ -n "${reader_pid}" ]] && kill -0 "${reader_pid}" >/dev/null 2>&1; then
    kill "${reader_pid}" >/dev/null 2>&1 || true
  fi
) &
producer_watch_pid=$!

if [[ "${MAX_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  (
    sleep "${MAX_SECONDS}"
    if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
      stop_qemu_for_elf
      kill "${qemu_pid}" >/dev/null 2>&1 || true
    fi
  ) &
  watchdog_pid=$!
fi

set +e
wait "${reader_pid}"
reader_rc=$?
reader_pid=""
if [[ -n "${producer_watch_pid}" ]]; then
  kill "${producer_watch_pid}" >/dev/null 2>&1 || true
  producer_watch_pid=""
fi
if [[ -n "${watchdog_pid}" ]]; then
  kill "${watchdog_pid}" >/dev/null 2>&1 || true
  watchdog_pid=""
fi
if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
  stop_qemu_for_elf
  kill "${qemu_pid}" >/dev/null 2>&1 || true
fi
wait "${qemu_pid}"
qemu_rc=$?
qemu_pid=""
set -e

rm -f "${QEMU_FIFO}"
QEMU_FIFO=""
raw_rows="$(awk 'END { print NR + 0 }' "${QEMU_TRACE}" 2>/dev/null || echo 0)"
if [[ "${reader_rc}" -ne 0 && "${raw_rows}" -gt 0 ]]; then
  echo "warn: QEMU prefix reader exited with status ${reader_rc}" >&2
fi
if [[ "${qemu_rc}" -ne 0 && "${raw_rows}" -gt 0 ]]; then
  if [[ "${raw_rows}" -ge "${CAPTURE_ROWS}" ]]; then
    echo "info: QEMU producer exited with status ${qemu_rc} after bounded prefix capture" >&2
  else
    echo "warn: QEMU producer exited with status ${qemu_rc}; continuing with captured prefix" >&2
  fi
fi
echo "qemu-live-capture-raw-rows=${raw_rows}"

if [[ ! -s "${QEMU_TRACE}" ]]; then
  echo "error: QEMU trace not found or empty: ${QEMU_TRACE}" >&2
  exit 2
fi

EXPECTED_PREVIEW="${TRACE_DIR}/qemu.live.expected.preview.jsonl"
row_args=(
  --input "${QEMU_TRACE}"
  --output "${EXPECTED_PREVIEW}"
  --max-rows "${EXPECTED_ROWS}"
)
if [[ "${ALLOW_BLOCK_MARKERS}" == "1" ]]; then
  row_args+=(--allow-block-markers)
fi
if [[ "${ALLOW_BLOCK_LOOP_REENTRY}" == "1" ]]; then
  row_args+=(--allow-block-loop-reentry)
fi
python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_qemu_rows.py" "${row_args[@]}"

BUILD_DIR="${BUILD_DIR}" \
FETCH_ELF="${ELF}" \
FETCH_QEMU_TRACE="${QEMU_TRACE}" \
FETCH_QEMU_MAX_ROWS="${EXPECTED_ROWS}" \
FETCH_QEMU_ALLOW_BLOCK_MARKERS="${ALLOW_BLOCK_MARKERS}" \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY="${ALLOW_BLOCK_LOOP_REENTRY}" \
FETCH_MARKER_ROWS_TRACE_TOP="${MARKER_ROWS}" \
bash "${FETCH_RUNNER}"

MANIFEST="${REPORT_DIR}/crosscheck_manifest.json"
if [[ ! -s "${MANIFEST}" ]]; then
  echo "error: expected cross-check manifest was not produced: ${MANIFEST}" >&2
  exit 3
fi

python3 - "${MANIFEST}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    manifest = json.load(f)
summary = manifest.get("summary", {})
print(
    "frontend-fetch-rf-alu-qemu-elf-status={status} compared={compared} mismatches={mismatches}".format(
        status=manifest.get("status"),
        compared=summary.get("compared_rows"),
        mismatches=summary.get("mismatch_count"),
    )
)
PY

echo "qemu-live-trace=${QEMU_TRACE}"
echo "qemu-live-expected-preview=${EXPECTED_PREVIEW}"
echo "frontend-fetch-rf-alu-qemu-elf-xcheck-report=${REPORT_DIR}"
