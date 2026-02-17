#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${PYC_PGO_OUT_DIR:-${ROOT_DIR}/tests/perf/pgo}"
mkdir -p "${OUT_DIR}"

BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"
RUN_SIM_SCRIPT="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
LLVM_PROFDATA="${LLVM_PROFDATA:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-profdata}"

TRAIN_MAX_COMMITS="${PYC_PGO_TRAIN_MAX_COMMITS:-200000}"
CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
BOOT_PC="${PYC_BOOT_PC:-0x0000000000010000}"
BOOT_SP="${PYC_BOOT_SP:-0x00000000000ff000}"

if [[ ! -x "${LLVM_PROFDATA}" ]]; then
  echo "error: llvm-profdata not found: ${LLVM_PROFDATA}" >&2
  exit 2
fi

build_out="$({
  CORE_ITERATIONS="${CORE_ITERATIONS}" \
  DHRY_RUNS="${DHRY_RUNS}" \
  bash "${BUILD_BENCH_SCRIPT}"
})"
CORE_MEMH="$(printf "%s\n" "${build_out}" | sed -n '1p')"
DHRY_MEMH="$(printf "%s\n" "${build_out}" | sed -n '2p')"

if [[ ! -f "${CORE_MEMH}" || ! -f "${DHRY_MEMH}" ]]; then
  echo "error: benchmark memh generation failed" >&2
  exit 3
fi

rm -f "${OUT_DIR}"/*.profraw "${OUT_DIR}/linxcore.profdata"

export PYC_TB_CXXFLAGS="-O3 -DNDEBUG -fprofile-instr-generate=${OUT_DIR}/linxcore-%p.profraw"
export PYC_MAX_COMMITS="${TRAIN_MAX_COMMITS}"
export PYC_BOOT_PC="${BOOT_PC}"
export PYC_BOOT_SP="${BOOT_SP}"
export PYC_SIM_STATS=0

echo "[pgo-train] coremark"
PYC_ACCEPT_EXIT_CODES="${CORE_ACCEPT_EXIT_CODES:-0}" \
  bash "${RUN_SIM_SCRIPT}" "${CORE_MEMH}" > "${OUT_DIR}/coremark_train.log" 2>&1

echo "[pgo-train] dhrystone"
PYC_ACCEPT_EXIT_CODES="${DHRY_ACCEPT_EXIT_CODES:-1}" \
  bash "${RUN_SIM_SCRIPT}" "${DHRY_MEMH}" > "${OUT_DIR}/dhrystone_train.log" 2>&1

"${LLVM_PROFDATA}" merge -output="${OUT_DIR}/linxcore.profdata" "${OUT_DIR}"/linxcore-*.profraw
echo "[pgo-train] merged profile: ${OUT_DIR}/linxcore.profdata"
