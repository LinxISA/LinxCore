#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"

PYC_FRONTEND="${PYC_FRONTEND:-/Users/zhoubot/pyCircuit/compiler/frontend}"
PYCC_BIN="${PYCC_BIN:-/Users/zhoubot/pyCircuit/compiler/mlir/build2/bin/pycc}"
PYC_RUNTIME_INC="${PYC_RUNTIME_INC:-/Users/zhoubot/pyCircuit/runtime}"

PYC_MLIR_OUT="${ROOT_DIR}/standalone/OEX/generated/pyc/oex_top.pyc"
CPP_OUT_DIR="${ROOT_DIR}/standalone/OEX/generated/cpp"
TB_SRC="${ROOT_DIR}/standalone/OEX/tb/tb_oex_shadow.cpp"
TB_BIN="${ROOT_DIR}/standalone/OEX/generated/tb_oex_shadow"
OEX_PROFILE="${OEX_PROFILE:-oex_target}"

CXX="${CXX:-c++}"
# Default to a fast runtime (the traces are ~1.2M rows each).
# Override if you want faster compile time, e.g. CXXFLAGS="-std=c++20 -O1".
CXXFLAGS="${CXXFLAGS:--std=c++20 -O3 -DNDEBUG}"

mkdir -p "${ROOT_DIR}/standalone/OEX/generated/pyc"

# pycc does not delete old shards; keep the compile deterministic by removing stale files.
rm -rf "${CPP_OUT_DIR}"
mkdir -p "${CPP_OUT_DIR}"

PYTHONPATH="${PYC_FRONTEND}:${ROOT_DIR}" python3 -m pycircuit.cli emit \
  standalone.oex.design.oex_top \
  --param "profile_name=\"${OEX_PROFILE}\"" \
  --output "${PYC_MLIR_OUT}"

"${PYCC_BIN}" --emit=cpp --out-dir "${CPP_OUT_DIR}" --cpp-split=module "${PYC_MLIR_OUT}"

"${CXX}" ${CXXFLAGS} \
  -I"${CPP_OUT_DIR}" \
  -I"${PYC_RUNTIME_INC}" \
  "${TB_SRC}" \
  "${CPP_OUT_DIR}"/*.cpp \
  -o "${TB_BIN}"

echo "${TB_BIN}"
