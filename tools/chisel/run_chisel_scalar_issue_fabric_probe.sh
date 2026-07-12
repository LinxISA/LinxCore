#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

TARGET_DIR="${ROOT_DIR}/generated/chisel-verilog/scalar-issue-fabric-probe"
BUILD_DIR="${ROOT_DIR}/generated/chisel-verilator/scalar-issue-fabric-probe"
rm -rf "${TARGET_DIR}" "${BUILD_DIR}"
mkdir -p "${TARGET_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "runMain linxcore.execute.ElaborateScalarIssueFabricProbe --target-dir ${TARGET_DIR}"
)

verilator --cc --exe --build --top-module ScalarIssueFabricProbe \
  -Mdir "${BUILD_DIR}" \
  -CFLAGS "-std=c++17 -O2" \
  "${TARGET_DIR}"/*.sv \
  "${ROOT_DIR}/tools/chisel/scalar_issue_fabric_probe_tb.cpp"

"${BUILD_DIR}/VScalarIssueFabricProbe"
