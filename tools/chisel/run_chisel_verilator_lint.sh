#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog"
TOP_SV="${SV_DIR}/LinxCoreTop.sv"

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for Chisel RTL lint" >&2
  exit 2
fi

bash "${ROOT_DIR}/tools/chisel/emit_verilog.sh"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no Chisel top SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

verilator --lint-only --top-module LinxCoreTop "${SV_FILES[@]}"
