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

verilator --lint-only "${TOP_SV}"
