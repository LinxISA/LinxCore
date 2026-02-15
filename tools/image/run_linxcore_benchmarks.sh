#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT_DIR}/tests/benchmarks_linxcore"
LOG_DIR="${OUT_DIR}/logs"
TRACE_DIR="${OUT_DIR}/traces"
mkdir -p "${LOG_DIR}" "${TRACE_DIR}"

BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"

MAX_CYCLES="${PYC_MAX_CYCLES:-50000000}"
BOOT_SP="${PYC_BOOT_SP:-0x00000000000ff000}"
BOOT_PC="${PYC_BOOT_PC:-0x0000000000010000}"
CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
BENCH_TRACE="${PYC_BENCH_TRACE:-0}"
CORE_TARGET_CYCLES="${CORE_TARGET_CYCLES:-5000000}"
DHRY_TARGET_CYCLES="${DHRY_TARGET_CYCLES:-650000}"

build_out="$({
  CORE_ITERATIONS="${CORE_ITERATIONS}" \
  DHRY_RUNS="${DHRY_RUNS}" \
  bash "${BUILD_BENCH_SCRIPT}"
})"
CORE_MEMH="$(printf "%s\n" "${build_out}" | sed -n '1p')"
DHRY_MEMH="$(printf "%s\n" "${build_out}" | sed -n '2p')"

if [[ ! -f "${CORE_MEMH}" || ! -f "${DHRY_MEMH}" ]]; then
  echo "error: benchmark build failed" >&2
  exit 2
fi

CORE_TRACE="${TRACE_DIR}/coremark_commit.jsonl"
DHRY_TRACE="${TRACE_DIR}/dhrystone_commit.jsonl"
rm -f "${CORE_TRACE}" "${DHRY_TRACE}"

run_one() {
  local name="$1"
  local memh="$2"
  local trace="$3"
  local log="${LOG_DIR}/${name}_linxcore_cpp.log"
  echo "[bench] ${name}"
  if [[ "${BENCH_TRACE}" == "1" ]]; then
    PYC_BOOT_PC="${BOOT_PC}" \
    PYC_BOOT_SP="${BOOT_SP}" \
    PYC_MAX_CYCLES="${MAX_CYCLES}" \
    PYC_COMMIT_TRACE="${trace}" \
    bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
  else
    PYC_BOOT_PC="${BOOT_PC}" \
    PYC_BOOT_SP="${BOOT_SP}" \
    PYC_MAX_CYCLES="${MAX_CYCLES}" \
    bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
  fi
  tail -n 1 "${log}"
}

extract_cycles() {
  awk '
    match($0, /cycles=[0-9]+/) {
      s = substr($0, RSTART, RLENGTH);
      gsub("cycles=", "", s);
      c = s;
    }
    END {
      if (c == "") exit 1;
      print c;
    }
  ' "$1"
}

run_one "coremark" "${CORE_MEMH}" "${CORE_TRACE}"
run_one "dhrystone" "${DHRY_MEMH}" "${DHRY_TRACE}"

CORE_CYCLES="$(extract_cycles "${LOG_DIR}/coremark_linxcore_cpp.log")"
DHRY_CYCLES="$(extract_cycles "${LOG_DIR}/dhrystone_linxcore_cpp.log")"
CORE_COMMITS="n/a"
DHRY_COMMITS="n/a"
if [[ "${BENCH_TRACE}" == "1" ]]; then
  CORE_COMMITS="$(wc -l < "${CORE_TRACE}" | tr -d ' ')"
  DHRY_COMMITS="$(wc -l < "${DHRY_TRACE}" | tr -d ' ')"
fi

core_status="fail"
dhry_status="fail"
if [[ "${CORE_CYCLES}" -le "${CORE_TARGET_CYCLES}" ]]; then
  core_status="pass"
fi
if [[ "${DHRY_CYCLES}" -le "${DHRY_TARGET_CYCLES}" ]]; then
  dhry_status="pass"
fi

REPORT="${OUT_DIR}/linxcore_benchmark_report.md"
cat > "${REPORT}" <<MD
# LinxCore OOO PYC Benchmark Report

## Inputs

- CoreMark MEMH: ${CORE_MEMH}
- Dhrystone MEMH: ${DHRY_MEMH}
- CoreMark iterations: ${CORE_ITERATIONS}
- Dhrystone runs: ${DHRY_RUNS}
- Max cycles: ${MAX_CYCLES}
- Boot PC: ${BOOT_PC}
- Boot SP: ${BOOT_SP}
- Commit trace enabled: ${BENCH_TRACE}

## Results

| Workload | LinxCore OOO PYC cycles | Target cycles | Status |
| --- | ---: | ---: | --- |
| CoreMark | ${CORE_CYCLES} | ${CORE_TARGET_CYCLES} | ${core_status} |
| Dhrystone | ${DHRY_CYCLES} | ${DHRY_TARGET_CYCLES} | ${dhry_status} |

## Trace Counts

| Workload | Commit trace rows |
| --- | ---: |
| CoreMark | ${CORE_COMMITS} |
| Dhrystone | ${DHRY_COMMITS} |
MD

cat "${REPORT}"

if [[ "${core_status}" != "pass" || "${dhry_status}" != "pass" ]]; then
  echo "error: benchmark target miss (core=${core_status}, dhry=${dhry_status})" >&2
  exit 1
fi
