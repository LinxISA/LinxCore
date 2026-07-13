#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

WORK_DIR="${ROOT_DIR}/build/chisel-scalar-l1d-scb-probe"
RTL_DIR="${WORK_DIR}/rtl"
OBJ_DIR="${WORK_DIR}/obj"
rm -rf "${WORK_DIR}"
mkdir -p "${RTL_DIR}" "${OBJ_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors \
    "runMain linxcore.lsu.EmitScalarL1DScbProbe --target-dir ${RTL_DIR}"
)

verilator --cc --exe --build --Mdir "${OBJ_DIR}" \
  --top-module ScalarL1DScbProbe \
  "${RTL_DIR}"/*.sv \
  "${ROOT_DIR}/tools/chisel/scalar_l1d_scb_probe_tb.cpp"
"${OBJ_DIR}/VScalarL1DScbProbe"
