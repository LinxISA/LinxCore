#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${1:-${ROOT_DIR}/generated/bench_try_more/matrix_runs_v2}"

BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}"
BENCH_TRACE="${PYC_BENCH_TRACE:-0}"
BENCH_LINXTRACE="${PYC_BENCH_LINXTRACE:-0}"
REQUIRE_REAL="${LINX_BENCH_REQUIRE_REAL:-1}"
TB_CXX="${CXX:-clang++}"

tiers=(
  "smoke:2:200"
  "mid:10:1000"
  "heavy:20:5000"
)

mkdir -p "${OUT_DIR}"

run_tier() {
  local spec="$1"
  IFS=':' read -r name core_it dhry_runs <<<"${spec}"
  local tier_dir="${OUT_DIR}/${name}"
  local report_src="${ROOT_DIR}/tests/benchmarks_linxcore/linxcore_benchmark_report.md"
  local core_log_src="${ROOT_DIR}/tests/benchmarks_linxcore/logs/coremark_linxcore_cpp.log"
  local dhry_log_src="${ROOT_DIR}/tests/benchmarks_linxcore/logs/dhrystone_linxcore_cpp.log"
  local run_log="${tier_dir}/run.log"
  local report_dst="${tier_dir}/report.md"
  local core_log_dst="${tier_dir}/coremark.log"
  local dhry_log_dst="${tier_dir}/dhrystone.log"
  local meta="${tier_dir}/meta.txt"

  mkdir -p "${tier_dir}"
  printf '[matrix] %s core_it=%s dhry_runs=%s\n' "${name}" "${core_it}" "${dhry_runs}" | tee "${meta}"

  /usr/bin/time -l env \
    LINX_BENCH_REQUIRE_REAL="${REQUIRE_REAL}" \
    PYC_BENCH_TRACE="${BENCH_TRACE}" \
    PYC_BENCH_LINXTRACE="${BENCH_LINXTRACE}" \
    LINXCORE_BUILD_PROFILE="${BUILD_PROFILE}" \
    CXX="${TB_CXX}" \
    CORE_ITERATIONS="${core_it}" \
    DHRY_RUNS="${dhry_runs}" \
    bash "${ROOT_DIR}/tools/image/run_linxcore_benchmarks.sh" >"${run_log}" 2>&1 || true

  if [[ -f "${report_src}" ]]; then
    cp "${report_src}" "${report_dst}"
  fi
  if [[ -f "${core_log_src}" ]]; then
    cp "${core_log_src}" "${core_log_dst}"
  fi
  if [[ -f "${dhry_log_src}" ]]; then
    cp "${dhry_log_src}" "${dhry_log_dst}"
  fi
}

for spec in "${tiers[@]}"; do
  run_tier "${spec}"
done

echo "[matrix] done: ${OUT_DIR}"
