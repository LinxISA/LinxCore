#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
FETCH_MARKER_ROWS_TRACE_TOP="${FETCH_MARKER_ROWS_TRACE_TOP:-0}"
FETCH_REDUCED_STORE_DISPATCH_STQ="${FETCH_REDUCED_STORE_DISPATCH_STQ:-0}"
FETCH_REDUCED_STORE_REPLAY_LIQ="${FETCH_REDUCED_STORE_REPLAY_LIQ:-0}"
selected_top_count=0
for selected_top in "${FETCH_MARKER_ROWS_TRACE_TOP}" "${FETCH_REDUCED_STORE_DISPATCH_STQ}" "${FETCH_REDUCED_STORE_REPLAY_LIQ}"; do
  if [[ "${selected_top}" == "1" ]]; then
    selected_top_count=$((selected_top_count + 1))
  fi
done
if (( selected_top_count > 1 )); then
  echo "error: FETCH_MARKER_ROWS_TRACE_TOP, FETCH_REDUCED_STORE_DISPATCH_STQ, and FETCH_REDUCED_STORE_REPLAY_LIQ are mutually exclusive" >&2
  exit 2
fi
if [[ "${FETCH_MARKER_ROWS_TRACE_TOP}" == "1" ]]; then
  SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-marker-rows-trace-top"
  TOP_MODULE="LinxCoreFrontendFetchRfAluMarkerRowsTraceTop"
  EMIT_MAIN="linxcore.top.EmitLinxCoreFrontendFetchRfAluMarkerRowsTraceTop"
elif [[ "${FETCH_REDUCED_STORE_REPLAY_LIQ}" == "1" ]]; then
  SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-reduced-store-replay-liq-trace-top"
  TOP_MODULE="LinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop"
  EMIT_MAIN="linxcore.top.EmitLinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop"
elif [[ "${FETCH_REDUCED_STORE_DISPATCH_STQ}" == "1" ]]; then
  SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-reduced-store-trace-top"
  TOP_MODULE="LinxCoreFrontendFetchRfAluReducedStoreTraceTop"
  EMIT_MAIN="linxcore.top.EmitLinxCoreFrontendFetchRfAluReducedStoreTraceTop"
else
  SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"
  TOP_MODULE="LinxCoreFrontendFetchRfAluTraceTop"
  EMIT_MAIN="linxcore.top.EmitLinxCoreFrontendFetchRfAluTraceTop"
fi
TOP_SV="${SV_DIR}/${TOP_MODULE}.sv"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck}"
if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
OBJ_DIR="${BUILD_DIR}/obj_dir"
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
DUT_TRACE="${TRACE_DIR}/dut.chisel.jsonl"
QEMU_TRACE="${TRACE_DIR}/qemu.reference.jsonl"
SIDEBAND_STATS="${REPORT_DIR}/frontend_fetch_rf_alu_sideband_stats.json"
DEFAULT_FETCH_MEMORY_BIN="${BUILD_DIR}/fixture.fetch.bin"
DEFAULT_FETCH_MEMORY_HEX="${BUILD_DIR}/elf.fetch.mem"
DEFAULT_EXPECTED_ROWS="${BUILD_DIR}/fixture.expected.jsonl"
DEFAULT_QEMU_EXPECTED_ROWS="${BUILD_DIR}/qemu.expected.jsonl"
FETCH_ELF="${FETCH_ELF:-}"
FETCH_MEMORY_BIN="${FETCH_MEMORY_BIN:-}"
FETCH_MEMORY_HEX="${FETCH_MEMORY_HEX:-}"
FETCH_MEMORY_BASE="${FETCH_MEMORY_BASE:-0x1000}"
FETCH_EXPECTED_ROWS="${FETCH_EXPECTED_ROWS:-}"
FETCH_QEMU_TRACE="${FETCH_QEMU_TRACE:-}"
FETCH_QEMU_MAX_ROWS="${FETCH_QEMU_MAX_ROWS:-0}"
FETCH_QEMU_ALLOW_BLOCK_MARKERS="${FETCH_QEMU_ALLOW_BLOCK_MARKERS:-0}"
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY="${FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY:-0}"
FETCH_DISABLE_STORE_MEMORY_MUTATION="${FETCH_DISABLE_STORE_MEMORY_MUTATION:-0}"

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for Chisel frontend fetch RF ALU trace top xcheck" >&2
  exit 2
fi

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

if [[ -n "${FETCH_EXPECTED_ROWS}" && -n "${FETCH_QEMU_TRACE}" ]]; then
  echo "error: set only one of FETCH_EXPECTED_ROWS or FETCH_QEMU_TRACE" >&2
  exit 2
fi

if [[ -n "${FETCH_QEMU_TRACE}" ]]; then
  if [[ "${FETCH_QEMU_TRACE}" != /* ]]; then
    FETCH_QEMU_TRACE="${ROOT_DIR}/${FETCH_QEMU_TRACE}"
  fi
  if [[ ! -f "${FETCH_QEMU_TRACE}" ]]; then
    echo "error: FETCH_QEMU_TRACE does not exist: ${FETCH_QEMU_TRACE}" >&2
    exit 2
  fi
  FETCH_EXPECTED_ROWS="${DEFAULT_QEMU_EXPECTED_ROWS}"
  qemu_row_args=(
    --input "${FETCH_QEMU_TRACE}"
    --output "${FETCH_EXPECTED_ROWS}"
    --max-rows "${FETCH_QEMU_MAX_ROWS}"
  )
  if [[ "${FETCH_QEMU_ALLOW_BLOCK_MARKERS}" == "1" ]]; then
    qemu_row_args+=(--allow-block-markers)
  fi
  if [[ "${FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY}" == "1" ]]; then
    qemu_row_args+=(--allow-block-loop-reentry)
  fi
  python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_qemu_rows.py" "${qemu_row_args[@]}"
elif [[ -z "${FETCH_EXPECTED_ROWS}" ]]; then
  FETCH_EXPECTED_ROWS="${DEFAULT_EXPECTED_ROWS}"
  python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_fixture_rows.py" \
    --output "${FETCH_EXPECTED_ROWS}"
else
  if [[ "${FETCH_EXPECTED_ROWS}" != /* ]]; then
    FETCH_EXPECTED_ROWS="${ROOT_DIR}/${FETCH_EXPECTED_ROWS}"
  fi
  if [[ ! -f "${FETCH_EXPECTED_ROWS}" ]]; then
    echo "error: FETCH_EXPECTED_ROWS does not exist: ${FETCH_EXPECTED_ROWS}" >&2
    exit 2
  fi
fi

EXPECTED_ROW_COUNT="$(python3 - "${FETCH_EXPECTED_ROWS}" <<'PY'
import json
import sys

path = sys.argv[1]
count = 0
with open(path, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        row = json.loads(line)
        if row.get("type") == "META":
            continue
        if row.get("valid", 1) in (0, False, "0", "false", "False"):
            continue
        if row.get("skip", 0) in (1, True, "1", "true", "True"):
            continue
        count += 1
print(count)
PY
)"
if [[ "${EXPECTED_ROW_COUNT}" -le 0 ]]; then
  echo "error: expected row stream is empty: ${FETCH_EXPECTED_ROWS}" >&2
  exit 2
fi

if [[ -n "${FETCH_MEMORY_BIN}" && -n "${FETCH_MEMORY_HEX}" ]]; then
  echo "error: set only one of FETCH_MEMORY_BIN or FETCH_MEMORY_HEX" >&2
  exit 2
fi

if [[ -n "${FETCH_ELF}" && -n "${FETCH_MEMORY_BIN}" ]]; then
  echo "error: FETCH_ELF produces sparse memory and cannot be combined with FETCH_MEMORY_BIN" >&2
  exit 2
fi

MEMORY_ARGS=()
if [[ -n "${FETCH_ELF}" ]]; then
  if [[ "${FETCH_ELF}" != /* ]]; then
    FETCH_ELF="${ROOT_DIR}/${FETCH_ELF}"
  fi
  if [[ ! -f "${FETCH_ELF}" ]]; then
    echo "error: FETCH_ELF does not exist: ${FETCH_ELF}" >&2
    exit 2
  fi
  FETCH_MEMORY_HEX="${FETCH_MEMORY_HEX:-${DEFAULT_FETCH_MEMORY_HEX}}"
  python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_elf_memory.py" \
    --elf "${FETCH_ELF}" \
    --output "${FETCH_MEMORY_HEX}"
fi

if [[ -n "${FETCH_MEMORY_HEX}" ]]; then
  if [[ ! -f "${FETCH_MEMORY_HEX}" ]]; then
    echo "error: FETCH_MEMORY_HEX does not exist: ${FETCH_MEMORY_HEX}" >&2
    exit 2
  fi
  MEMORY_ARGS=(--memory-hex "${FETCH_MEMORY_HEX}")
else
  FETCH_MEMORY_BIN="${FETCH_MEMORY_BIN:-${DEFAULT_FETCH_MEMORY_BIN}}"
  if [[ "${FETCH_MEMORY_BIN}" == "${DEFAULT_FETCH_MEMORY_BIN}" ]]; then
    python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_fixture_memory.py" \
      --output "${FETCH_MEMORY_BIN}"
  elif [[ ! -f "${FETCH_MEMORY_BIN}" ]]; then
    echo "error: FETCH_MEMORY_BIN does not exist: ${FETCH_MEMORY_BIN}" >&2
    exit 2
  fi
  MEMORY_ARGS=(--memory-bin "${FETCH_MEMORY_BIN}" --memory-base "${FETCH_MEMORY_BASE}")
fi

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain ${EMIT_MAIN}"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no frontend fetch RF ALU trace top SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
VERILATOR_CFLAGS="-std=c++17 -O2"
TB_ARGS=(
  --dut-trace "${DUT_TRACE}"
  --qemu-trace "${QEMU_TRACE}"
  --expected-rows "${FETCH_EXPECTED_ROWS}"
  --sideband-stats "${SIDEBAND_STATS}"
  "${MEMORY_ARGS[@]}"
)
if [[ "${FETCH_MARKER_ROWS_TRACE_TOP}" == "1" ]]; then
  VERILATOR_CFLAGS="${VERILATOR_CFLAGS} -DLINXCORE_MARKER_ROWS_TRACE_TOP"
  TB_ARGS+=(--admit-marker-rows)
elif [[ "${FETCH_REDUCED_STORE_REPLAY_LIQ}" == "1" ]]; then
  VERILATOR_CFLAGS="${VERILATOR_CFLAGS} -DLINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP"
elif [[ "${FETCH_REDUCED_STORE_DISPATCH_STQ}" == "1" ]]; then
  VERILATOR_CFLAGS="${VERILATOR_CFLAGS} -DLINXCORE_REDUCED_STORE_TRACE_TOP"
fi
if [[ "${FETCH_DISABLE_STORE_MEMORY_MUTATION}" == "1" ]]; then
  TB_ARGS+=(--disable-store-memory-mutation)
fi
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp" \
  --build \
  -Mdir "${OBJ_DIR}" \
  -o linxcore_frontend_fetch_rf_alu_trace_top_tb \
  -CFLAGS "${VERILATOR_CFLAGS}"

"${OBJ_DIR}/linxcore_frontend_fetch_rf_alu_trace_top_tb" "${TB_ARGS[@]}"

bash "${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --report-dir "${REPORT_DIR}" \
  --max-commits "${EXPECTED_ROW_COUNT}" \
  --mode failfast

echo "frontend-fetch-rf-alu-trace-top-xcheck-report=${REPORT_DIR}"
