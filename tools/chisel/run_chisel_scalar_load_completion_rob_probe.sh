#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

TARGET_DIR="${ROOT_DIR}/generated/chisel-verilog/scalar-load-completion-rob-probe"
BUILD_DIR="${ROOT_DIR}/generated/chisel-verilator/scalar-load-completion-rob-probe"
rm -rf "${TARGET_DIR}" "${BUILD_DIR}"
mkdir -p "${TARGET_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "runMain linxcore.top.EmitScalarLoadCompletionROBProbe --target-dir ${TARGET_DIR}"
)

verilator --cc --exe --build --top-module ScalarLoadCompletionROBProbe \
  -Mdir "${BUILD_DIR}" \
  -CFLAGS "-std=c++17 -O2" \
  "${TARGET_DIR}"/*.sv \
  "${ROOT_DIR}/tools/chisel/scalar_load_completion_rob_probe_tb.cpp"

"${BUILD_DIR}/VScalarLoadCompletionROBProbe"
