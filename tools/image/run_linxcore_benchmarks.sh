#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT_DIR}/tests/benchmarks_linxcore"
LOG_DIR="${OUT_DIR}/logs"
TRACE_DIR="${OUT_DIR}/traces"
LINXTRACE_DIR="${OUT_DIR}/linxtrace"
mkdir -p "${LOG_DIR}" "${TRACE_DIR}" "${LINXTRACE_DIR}"

BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"

MAX_CYCLES="${PYC_MAX_CYCLES:-50000000}"
BOOT_SP="${PYC_BOOT_SP:-0x00000000000ff000}"
BOOT_PC_DEFAULT="0x0000000000010000"
BOOT_PC_OVERRIDE="${PYC_BOOT_PC:-}"
CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
BENCH_TRACE="${PYC_BENCH_TRACE:-0}"
BENCH_LINXTRACE="${PYC_BENCH_LINXTRACE:-0}"
CORE_TARGET_CYCLES="${CORE_TARGET_CYCLES:-5000000}"
DHRY_TARGET_CYCLES="${DHRY_TARGET_CYCLES:-650000}"
REQUIRE_REAL="${LINX_BENCH_REQUIRE_REAL:-0}"
CORE_ACCEPT_EXIT_CODES="${CORE_ACCEPT_EXIT_CODES:-0}"
DHRY_ACCEPT_EXIT_CODES="${DHRY_ACCEPT_EXIT_CODES:-1}"
LLVM_READELF="${LLVM_READELF:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf}"
LINXISA_DIR="${LINXISA_DIR:-${HOME}/linx-isa}"

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

core_real=0
dhry_real=0
if [[ "${CORE_MEMH}" == *"/coremark_real.memh" ]]; then
  core_real=1
fi
if [[ "${DHRY_MEMH}" == *"/dhrystone_real.memh" ]]; then
  dhry_real=1
fi

if [[ "${core_real}" == "0" || "${dhry_real}" == "0" ]]; then
  echo "warn: benchmark builder returned non-real memh(s):" >&2
  echo "  coremark=${CORE_MEMH}" >&2
  echo "  dhrystone=${DHRY_MEMH}" >&2
  if [[ "${REQUIRE_REAL}" == "1" ]]; then
    echo "error: non-real memh disallowed (set LINX_BENCH_REQUIRE_REAL=0 to allow fallback)" >&2
    exit 3
  fi
fi

CORE_TRACE="${TRACE_DIR}/coremark_commit.jsonl"
DHRY_TRACE="${TRACE_DIR}/dhrystone_commit.jsonl"
CORE_TRACE_TXT="${TRACE_DIR}/coremark_commit.txt"
DHRY_TRACE_TXT="${TRACE_DIR}/dhrystone_commit.txt"
rm -f "${CORE_TRACE}" "${DHRY_TRACE}"
rm -f "${CORE_TRACE_TXT}" "${DHRY_TRACE_TXT}"

run_one() {
  local name="$1"
  local memh="$2"
  local trace="$3"
  local trace_txt="$4"
  local boot_pc="$5"
  local accept_exit_codes="$6"
  local log="${LOG_DIR}/${name}_linxcore_cpp.log"
  local linxtrace="${LINXTRACE_DIR}/${name}.linxtrace.jsonl"
  echo "[bench] ${name}"
  if [[ "${BENCH_TRACE}" == "1" ]]; then
    if [[ "${BENCH_LINXTRACE}" == "1" ]]; then
      PYC_BOOT_PC="${boot_pc}" \
      PYC_BOOT_SP="${BOOT_SP}" \
      PYC_MAX_CYCLES="${MAX_CYCLES}" \
      PYC_COMMIT_TRACE="${trace}" \
      PYC_COMMIT_TRACE_TEXT="${trace_txt}" \
      PYC_ACCEPT_EXIT_CODES="${accept_exit_codes}" \
      PYC_LINXTRACE=1 \
      PYC_LINXTRACE_PATH="${linxtrace}" \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
    else
      PYC_BOOT_PC="${boot_pc}" \
      PYC_BOOT_SP="${BOOT_SP}" \
      PYC_MAX_CYCLES="${MAX_CYCLES}" \
      PYC_COMMIT_TRACE="${trace}" \
      PYC_COMMIT_TRACE_TEXT="${trace_txt}" \
      PYC_ACCEPT_EXIT_CODES="${accept_exit_codes}" \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
    fi
  else
    if [[ "${BENCH_LINXTRACE}" == "1" ]]; then
      PYC_BOOT_PC="${boot_pc}" \
      PYC_BOOT_SP="${BOOT_SP}" \
      PYC_MAX_CYCLES="${MAX_CYCLES}" \
      PYC_ACCEPT_EXIT_CODES="${accept_exit_codes}" \
      PYC_LINXTRACE=1 \
      PYC_LINXTRACE_PATH="${linxtrace}" \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
    else
      PYC_BOOT_PC="${boot_pc}" \
      PYC_BOOT_SP="${BOOT_SP}" \
      PYC_MAX_CYCLES="${MAX_CYCLES}" \
      PYC_ACCEPT_EXIT_CODES="${accept_exit_codes}" \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1
    fi
  fi
  tail -n 1 "${log}"
}

elf_entry() {
  local elf="$1"
  if [[ ! -x "${LLVM_READELF}" || ! -f "${elf}" ]]; then
    return 1
  fi
  "${LLVM_READELF}" -h "${elf}" | awk '/Entry point address:/ {print $4; exit}'
}

resolve_boot_pc() {
  local name="$1"
  if [[ -n "${BOOT_PC_OVERRIDE}" ]]; then
    echo "${BOOT_PC_OVERRIDE}"
    return 0
  fi
  if [[ "${name}" == "coremark" && "${core_real}" == "1" ]]; then
    local elf="${LINXISA_DIR}/workloads/generated/elf/coremark.elf"
    local e
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  if [[ "${name}" == "dhrystone" && "${dhry_real}" == "1" ]]; then
    local elf="${LINXISA_DIR}/workloads/generated/elf/dhrystone.elf"
    local e
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  echo "${BOOT_PC_DEFAULT}"
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

CORE_BOOT_PC="$(resolve_boot_pc "coremark")"
DHRY_BOOT_PC="$(resolve_boot_pc "dhrystone")"

run_one "coremark" "${CORE_MEMH}" "${CORE_TRACE}" "${CORE_TRACE_TXT}" "${CORE_BOOT_PC}" "${CORE_ACCEPT_EXIT_CODES}"
run_one "dhrystone" "${DHRY_MEMH}" "${DHRY_TRACE}" "${DHRY_TRACE_TXT}" "${DHRY_BOOT_PC}" "${DHRY_ACCEPT_EXIT_CODES}"

CORE_CYCLES="$(extract_cycles "${LOG_DIR}/coremark_linxcore_cpp.log")"
DHRY_CYCLES="$(extract_cycles "${LOG_DIR}/dhrystone_linxcore_cpp.log")"
CORE_COMMITS="n/a"
DHRY_COMMITS="n/a"
CORE_TEXT_ROWS="n/a"
DHRY_TEXT_ROWS="n/a"
if [[ "${BENCH_TRACE}" == "1" ]]; then
  CORE_COMMITS="$(wc -l < "${CORE_TRACE}" | tr -d ' ')"
  DHRY_COMMITS="$(wc -l < "${DHRY_TRACE}" | tr -d ' ')"
  if [[ -f "${CORE_TRACE_TXT}" ]]; then
    CORE_TEXT_ROWS="$(grep -c '^seq=' "${CORE_TRACE_TXT}" || true)"
  fi
  if [[ -f "${DHRY_TRACE_TXT}" ]]; then
    DHRY_TEXT_ROWS="$(grep -c '^seq=' "${DHRY_TRACE_TXT}" || true)"
  fi
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
- CoreMark Boot PC: ${CORE_BOOT_PC}
- Dhrystone Boot PC: ${DHRY_BOOT_PC}
- Boot SP: ${BOOT_SP}
- Commit trace enabled: ${BENCH_TRACE}
- CoreMark real input: ${core_real}
- Dhrystone real input: ${dhry_real}

## Results

| Workload | LinxCore OOO PYC cycles | Target cycles | Status |
| --- | ---: | ---: | --- |
| CoreMark | ${CORE_CYCLES} | ${CORE_TARGET_CYCLES} | ${core_status} |
| Dhrystone | ${DHRY_CYCLES} | ${DHRY_TARGET_CYCLES} | ${dhry_status} |

## Trace Counts

| Workload | Commit trace rows | Text trace rows |
| --- | ---: | ---: |
| CoreMark | ${CORE_COMMITS} | ${CORE_TEXT_ROWS} |
| Dhrystone | ${DHRY_COMMITS} | ${DHRY_TEXT_ROWS} |

## Trace Paths

- CoreMark JSONL: ${CORE_TRACE}
- CoreMark text: ${CORE_TRACE_TXT}
- Dhrystone JSONL: ${DHRY_TRACE}
- Dhrystone text: ${DHRY_TRACE_TXT}
MD

cat "${REPORT}"

if [[ "${core_status}" != "pass" || "${dhry_status}" != "pass" ]]; then
  echo "error: benchmark target miss (core=${core_status}, dhry=${dhry_status})" >&2
  exit 1
fi
