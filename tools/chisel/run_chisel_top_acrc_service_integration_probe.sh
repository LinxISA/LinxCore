#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r935-top-acrc-service-integration-probe"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"
VERILATOR_BUILD_JOBS="${VERILATOR_BUILD_JOBS:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      BUILD_DIR="${2:-}"
      shift 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
OBJ_DIR="${BUILD_DIR}/obj_dir"
mkdir -p "${BUILD_DIR}"
rm -rf "${OBJ_DIR}"

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for the top ACRC service integration probe" >&2
  exit 2
fi

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

(
  cd "${ROOT_DIR}/chisel"
  sbt --batch --no-colors "runMain linxcore.top.EmitLinxCoreFrontendFetchRfAluTraceTop"
)

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)
if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no SystemVerilog files emitted under ${SV_DIR}" >&2
  exit 2
fi

verilator \
  --cc "${SV_FILES[@]}" \
  --top-module LinxCoreFrontendFetchRfAluTraceTop \
  --exe "${ROOT_DIR}/tools/chisel/top_acrc_service_integration_probe_tb.cpp" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o top_acrc_service_integration_probe_tb \
  -CFLAGS "-std=c++17 -O2"

"${OBJ_DIR}/top_acrc_service_integration_probe_tb"
