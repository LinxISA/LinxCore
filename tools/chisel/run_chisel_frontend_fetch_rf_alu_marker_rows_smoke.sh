#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-marker-rows-trace-top"
TOP_SV="${SV_DIR}/LinxCoreFrontendFetchRfAluMarkerRowsTraceTop.sv"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-frontend-fetch-rf-alu-marker-rows-smoke}"
if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
OBJ_DIR="${BUILD_DIR}/obj_dir"

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for Chisel marker-row smoke" >&2
  exit 2
fi

mkdir -p "${BUILD_DIR}"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.top.EmitLinxCoreFrontendFetchRfAluMarkerRowsTraceTop"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted marker-row top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no marker-row top SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module LinxCoreFrontendFetchRfAluMarkerRowsTraceTop \
  --exe "${ROOT_DIR}/tools/chisel/frontend_fetch_rf_alu_marker_rows_trace_top_tb.cpp" \
  --build \
  -Mdir "${OBJ_DIR}" \
  -o linxcore_frontend_fetch_rf_alu_marker_rows_trace_top_tb \
  -CFLAGS "-std=c++17 -O2"

"${OBJ_DIR}/linxcore_frontend_fetch_rf_alu_marker_rows_trace_top_tb"
