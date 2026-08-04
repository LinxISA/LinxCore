#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"

bash "${ROOT_DIR}/tools/chisel/build_chisel.sh"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
cd "${CHISEL_DIR}"
LINX_TOP_WIDTH="${LINX_TOP_WIDTH:-4}" \
LINX_TOP_SIMULATION_PROFILE="${LINX_TOP_SIMULATION_PROFILE:-1}" \
sbt --server --batch --no-colors --mem "${LINX_CHISEL_SBT_MEM_MB}" \
  "runMain linxcore.top.EmitTOP --target-dir ${ROOT_DIR}/generated/chisel-verilog"
