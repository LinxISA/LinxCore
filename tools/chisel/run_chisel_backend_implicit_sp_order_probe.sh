#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r875-karnaugh-implicit-sp-order-probe"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      if [[ $# -lt 2 ]]; then
        echo "error: --build-dir requires a value" >&2
        exit 2
      fi
      BUILD_DIR="$2"
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
  --exe "${ROOT_DIR}/tools/chisel/backend_implicit_sp_order_probe_tb.cpp" \
  --build \
  --build-jobs 0 \
  -Mdir "${OBJ_DIR}" \
  -o backend_implicit_sp_order_probe_tb \
  -CFLAGS "-std=c++17 -O2"

"${OBJ_DIR}/backend_implicit_sp_order_probe_tb"
