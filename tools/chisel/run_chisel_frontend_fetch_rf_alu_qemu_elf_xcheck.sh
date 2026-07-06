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
FIXTURE=""
QEMU_BIN=""
EXPECTED_ROWS="${EXPECTED_ROWS:-3}"
CAPTURE_ROWS="${CAPTURE_ROWS:-}"
MAX_SECONDS="${MAX_SECONDS:-30}"
PC_LO=""
PC_HI=""
ALLOW_BLOCK_MARKERS=0
ALLOW_BLOCK_LOOP_REENTRY=0
MARKER_ROWS=0
REDUCED_STORE_DISPATCH_STQ=0
REDUCED_STORE_REPLAY_LIQ=0
DISABLE_STORE_MEMORY_MUTATION=0
ALLOW_RESIDUAL_REPLAY_LIQ_WAIT=0
QEMU_ONLY=0
EXPECT_LOAD_PCS=""
EXPECT_STORE_PCS=""

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --elf <program.elf> [options] -- [qemu args]
  $(basename "$0") --fixture replay-ldi-sdi-ldi [options] -- [qemu args]
  $(basename "$0") --fixture replay-ldi-sdi-ldi-loop [options] -- [qemu args]
  $(basename "$0") --fixture replay-ldi-sdi-ldi-ldi-loop [options] -- [qemu args]

Options:
  --build-dir <dir>       Output root (default: ${BUILD_DIR})
  --fixture <name>        Build a named fixture ELF before capture.
                          Supported: replay-ldi-sdi-ldi,
                                     replay-ldi-sdi-ldi-loop,
                                     replay-ldi-sdi-ldi-ldi-loop
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
  --reduced-store-dispatch-stq
                          Build the opt-in reduced-store top with store
                          dispatch routed through the reduced STQ/SCB path
  --reduced-store-replay-liq
                          Build the opt-in reduced-store replay-LIQ top where
                          replay queue heads allocate into diagnostic LIQ state
  --disable-store-memory-mutation
                          Do not mutate the harness sparse memory image after
                          matched store commits; reduced-store mode must supply
                          store-visible load data from RTL overlay state
  --allow-residual-replay-liq-wait
                          Allow a residual replay-LIQ wait row after the
                          compared rows and emit sideband evidence instead of
                          requiring the final idle-drain check
  --qemu-only             Stop after QEMU capture and reduced-row preview; this
                          validates fixture/reducer shape only and does not
                          build or run generated RTL
  --expect-load-pcs <csv> Require the reduced preview's load PC sequence to
                          equal a comma-separated hex/decimal list
  --expect-store-pcs <csv>
                          Require the reduced preview's store PC sequence to
                          equal a comma-separated hex/decimal list

This wrapper captures a bounded QEMU commit JSONL prefix from a direct-boot ELF,
validates that the selected rows are inside the current reduced scalar
ADD/ADDI/ADDTPC/C.MOVI/C.MOVR envelope, extracts the same ELF into sparse fetch
memory, and then runs LinxCoreFrontendFetchRfAluTraceTop through the neutral comparator.
With --allow-block-markers, legal BSTART/BSTOP rows are consumed by the reduced
frontend/ROB path as skip rows and are not written to the comparator trace.
With --marker-rows, the same reduced expected stream still marks legal markers
as skip rows for comparator filtering, but the marker-row top must admit and
retire those rows before the following scalar rows compare.
With --reduced-store-dispatch-stq, the harness uses the same comparator stream
but emits the reduced-store top so store rows exercise the opt-in STQ lifecycle.
With --reduced-store-replay-liq, the harness emits the reduced-store replay-LIQ
top so queued replay candidates are consumed only by LIQ allocation acceptance.
With --disable-store-memory-mutation, later loads can observe committed stores
only through the reduced-store RTL memory overlay.
With --fixture replay-ldi-sdi-ldi, the wrapper builds the memory-order probe
inside the build directory and defaults to the bounded prefix
C.BSTART.STD/LDI/SDI/LDI. The C.BSTOP tail is excluded by default because the
current dense fetch checker cannot consume that trailing two-byte stop marker
as part of the same fixture window.
With --fixture replay-ldi-sdi-ldi-loop, the wrapper captures the first memory
probe, the direct loop boundary, and the second dynamic memory probe so MDB
lookup evidence can be collected after the first pass records the dependency.
With --fixture replay-ldi-sdi-ldi-ldi-loop, the loop probe includes one
additional younger load before the direct loop boundary, increasing replay
return density for W1/W2 same-cycle replacement evidence.
With --qemu-only, the wrapper still builds any requested fixture, captures the
bounded QEMU prefix, and runs the reduced-row extractor, then exits before the
Verilator harness. Do not use that mode as QEMU/DUT equivalence evidence.
With --expect-load-pcs or --expect-store-pcs, the wrapper asserts the exact
memory PC sequence in the reduced preview before any optional RTL run.
USAGE
}

QEMU_ARGS=()
EXPECTED_ROWS_SET=0
CAPTURE_ROWS_SET=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --fixture) FIXTURE="$2"; shift 2 ;;
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --expected-rows) EXPECTED_ROWS="$2"; EXPECTED_ROWS_SET=1; shift 2 ;;
    --capture-rows) CAPTURE_ROWS="$2"; CAPTURE_ROWS_SET=1; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --pc-lo) PC_LO="$2"; shift 2 ;;
    --pc-hi) PC_HI="$2"; shift 2 ;;
    --allow-block-markers) ALLOW_BLOCK_MARKERS=1; shift ;;
    --allow-block-loop-reentry) ALLOW_BLOCK_LOOP_REENTRY=1; shift ;;
    --marker-rows) MARKER_ROWS=1; shift ;;
    --reduced-store-dispatch-stq) REDUCED_STORE_DISPATCH_STQ=1; shift ;;
    --reduced-store-replay-liq) REDUCED_STORE_REPLAY_LIQ=1; shift ;;
    --disable-store-memory-mutation) DISABLE_STORE_MEMORY_MUTATION=1; shift ;;
    --allow-residual-replay-liq-wait) ALLOW_RESIDUAL_REPLAY_LIQ_WAIT=1; shift ;;
    --qemu-only) QEMU_ONLY=1; shift ;;
    --expect-load-pcs) EXPECT_LOAD_PCS="$2"; shift 2 ;;
    --expect-store-pcs) EXPECT_STORE_PCS="$2"; shift 2 ;;
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
FIXTURE_BUILDER="${ROOT_DIR}/tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh"
qemu_pid=""
reader_pid=""
watchdog_pid=""
producer_watch_pid=""

if [[ -n "${ELF}" && -n "${FIXTURE}" ]]; then
  echo "error: --elf and --fixture are mutually exclusive" >&2
  usage
  exit 2
fi
if [[ -n "${FIXTURE}" ]]; then
  case "${FIXTURE}" in
    replay-ldi-sdi-ldi)
      FIXTURE_DIR="${BUILD_DIR}/fixture-replay-ldi-sdi-ldi"
      bash "${FIXTURE_BUILDER}" \
        --out-dir "${FIXTURE_DIR}" \
        --replay-ldi-sdi-ldi
      ELF="${FIXTURE_DIR}/frontend_fetch_rf_alu_qemu_fixture.elf"
      if [[ "${EXPECTED_ROWS_SET}" == "0" ]]; then
        EXPECTED_ROWS=0
      fi
      if [[ "${CAPTURE_ROWS_SET}" == "0" ]]; then
        CAPTURE_ROWS=4
      fi
      ALLOW_BLOCK_MARKERS=1
      if [[ -z "${PC_LO}" ]]; then
        PC_LO=0x10000
      fi
      if [[ -z "${PC_HI}" ]]; then
        PC_HI=0x1000b
      fi
      ;;
    replay-ldi-sdi-ldi-loop)
      FIXTURE_DIR="${BUILD_DIR}/fixture-replay-ldi-sdi-ldi-loop"
      bash "${FIXTURE_BUILDER}" \
        --out-dir "${FIXTURE_DIR}" \
        --replay-ldi-sdi-ldi-loop
      ELF="${FIXTURE_DIR}/frontend_fetch_rf_alu_qemu_fixture.elf"
      if [[ "${EXPECTED_ROWS_SET}" == "0" ]]; then
        EXPECTED_ROWS=0
      fi
      if [[ "${CAPTURE_ROWS_SET}" == "0" ]]; then
        CAPTURE_ROWS=12
      fi
      ALLOW_BLOCK_MARKERS=1
      ALLOW_BLOCK_LOOP_REENTRY=1
      if [[ -z "${PC_LO}" ]]; then
        PC_LO=0x10000
      fi
      if [[ -z "${PC_HI}" ]]; then
        PC_HI=0x10015
      fi
      ;;
    replay-ldi-sdi-ldi-ldi-loop)
      FIXTURE_DIR="${BUILD_DIR}/fixture-replay-ldi-sdi-ldi-ldi-loop"
      bash "${FIXTURE_BUILDER}" \
        --out-dir "${FIXTURE_DIR}" \
        --replay-ldi-sdi-ldi-ldi-loop
      ELF="${FIXTURE_DIR}/frontend_fetch_rf_alu_qemu_fixture.elf"
      if [[ "${EXPECTED_ROWS_SET}" == "0" ]]; then
        EXPECTED_ROWS=0
      fi
      if [[ "${CAPTURE_ROWS_SET}" == "0" ]]; then
        CAPTURE_ROWS=16
      fi
      ALLOW_BLOCK_MARKERS=1
      ALLOW_BLOCK_LOOP_REENTRY=1
      if [[ -z "${PC_LO}" ]]; then
        PC_LO=0x10000
      fi
      if [[ -z "${PC_HI}" ]]; then
        PC_HI=0x10019
      fi
      ;;
    *)
      echo "error: unsupported --fixture: ${FIXTURE}" >&2
      usage
      exit 2
      ;;
  esac
fi
if [[ -z "${ELF}" ]]; then
  echo "error: --elf or --fixture is required" >&2
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
selected_top_count=0
for selected_top in "${MARKER_ROWS}" "${REDUCED_STORE_DISPATCH_STQ}" "${REDUCED_STORE_REPLAY_LIQ}"; do
  if [[ "${selected_top}" == "1" ]]; then
    selected_top_count=$((selected_top_count + 1))
  fi
done
if (( selected_top_count > 1 )); then
  echo "error: --marker-rows, --reduced-store-dispatch-stq, and --reduced-store-replay-liq are mutually exclusive" >&2
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

if [[ -n "${EXPECT_LOAD_PCS}" || -n "${EXPECT_STORE_PCS}" ]]; then
  python3 - "${EXPECTED_PREVIEW}" "${EXPECT_LOAD_PCS}" "${EXPECT_STORE_PCS}" <<'PY'
import json
import sys

preview, expected_loads_s, expected_stores_s = sys.argv[1:4]


def parse_pc_list(text):
    if not text:
        return []
    out = []
    for item in text.split(","):
        item = item.strip()
        if item:
            out.append(int(item, 0))
    return out


expected_loads = parse_pc_list(expected_loads_s)
expected_stores = parse_pc_list(expected_stores_s)
loads = []
stores = []
with open(preview, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        if row.get("mem_valid") != 1:
            continue
        pc = int(row.get("pc", 0))
        if row.get("mem_is_store") == 1:
            stores.append(pc)
        else:
            loads.append(pc)

if expected_loads_s and loads != expected_loads:
    print(
        "error: reduced preview load PCs {observed} did not match expected {expected}".format(
            observed=",".join(hex(pc) for pc in loads),
            expected=",".join(hex(pc) for pc in expected_loads),
        ),
        file=sys.stderr,
    )
    raise SystemExit(1)
if expected_stores_s and stores != expected_stores:
    print(
        "error: reduced preview store PCs {observed} did not match expected {expected}".format(
            observed=",".join(hex(pc) for pc in stores),
            expected=",".join(hex(pc) for pc in expected_stores),
        ),
        file=sys.stderr,
    )
    raise SystemExit(1)

print("qemu-live-load-pcs=" + ",".join(hex(pc) for pc in loads))
print("qemu-live-store-pcs=" + ",".join(hex(pc) for pc in stores))
PY
fi

if [[ "${QEMU_ONLY}" == "1" ]]; then
  echo "frontend-fetch-rf-alu-qemu-elf-status=qemu-only raw_rows=${raw_rows}"
  echo "qemu-live-trace=${QEMU_TRACE}"
  echo "qemu-live-expected-preview=${EXPECTED_PREVIEW}"
  echo "frontend-fetch-rf-alu-qemu-elf-xcheck-report=${REPORT_DIR}"
  exit 0
fi

BUILD_DIR="${BUILD_DIR}" \
FETCH_ELF="${ELF}" \
FETCH_QEMU_TRACE="${QEMU_TRACE}" \
FETCH_QEMU_MAX_ROWS="${EXPECTED_ROWS}" \
FETCH_QEMU_ALLOW_BLOCK_MARKERS="${ALLOW_BLOCK_MARKERS}" \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY="${ALLOW_BLOCK_LOOP_REENTRY}" \
FETCH_MARKER_ROWS_TRACE_TOP="${MARKER_ROWS}" \
FETCH_REDUCED_STORE_DISPATCH_STQ="${REDUCED_STORE_DISPATCH_STQ}" \
FETCH_REDUCED_STORE_REPLAY_LIQ="${REDUCED_STORE_REPLAY_LIQ}" \
FETCH_DISABLE_STORE_MEMORY_MUTATION="${DISABLE_STORE_MEMORY_MUTATION}" \
FETCH_ALLOW_RESIDUAL_REPLAY_LIQ_WAIT="${ALLOW_RESIDUAL_REPLAY_LIQ_WAIT}" \
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
