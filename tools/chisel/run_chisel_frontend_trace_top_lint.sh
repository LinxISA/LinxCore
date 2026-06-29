#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-trace-top"
TOP_SV="${SV_DIR}/LinxCoreFrontendTraceTop.sv"

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for Chisel frontend trace top lint" >&2
  exit 2
fi

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.top.EmitLinxCoreFrontendTraceTop"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no frontend trace top SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

verilator --lint-only --top-module LinxCoreFrontendTraceTop "${SV_FILES[@]}"
