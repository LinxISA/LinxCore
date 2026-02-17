#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${PYC_PGO_OUT_DIR:-${ROOT_DIR}/tests/perf/pgo}"
RUN_SIM_SCRIPT="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"

PROFDATA="${PYC_PGO_PROFDATA:-${OUT_DIR}/linxcore.profdata}"
USE_MAX_COMMITS="${PYC_PGO_USE_MAX_COMMITS:-100000}"

if [[ ! -f "${PROFDATA}" ]]; then
  echo "error: missing PGO profile data: ${PROFDATA}" >&2
  exit 2
fi

build_out="$({
  CORE_ITERATIONS="${CORE_ITERATIONS:-10}" \
  DHRY_RUNS="${DHRY_RUNS:-1000}" \
  bash "${BUILD_BENCH_SCRIPT}"
})"
CORE_MEMH="$(printf "%s\n" "${build_out}" | sed -n '1p')"
if [[ ! -f "${CORE_MEMH}" ]]; then
  echo "error: missing coremark memh for PGO use build" >&2
  exit 3
fi

export PYC_TB_CXXFLAGS="-O3 -DNDEBUG -march=native -flto -fprofile-instr-use=${PROFDATA}"
export PYC_MAX_COMMITS="${USE_MAX_COMMITS}"
export PYC_SIM_STATS=0

echo "[pgo-use] building/running with profile ${PROFDATA}"
PYC_ACCEPT_EXIT_CODES="${CORE_ACCEPT_EXIT_CODES:-0}" \
  bash "${RUN_SIM_SCRIPT}" "${CORE_MEMH}" > "${OUT_DIR}/coremark_pgo_use.log" 2>&1
echo "[pgo-use] done: ${OUT_DIR}/coremark_pgo_use.log"
