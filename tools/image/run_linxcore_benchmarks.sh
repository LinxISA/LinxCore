#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"
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
LLVM_READELF="${LLVM_READELF:-$(linxcore_resolve_llvm_readelf "${ROOT_DIR}" || true)}"
LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"

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
CORE_LOG="${LOG_DIR}/coremark_linxcore_cpp.log"
DHRY_LOG="${LOG_DIR}/dhrystone_linxcore_cpp.log"
CORE_LINXTRACE="${LINXTRACE_DIR}/coremark.linxtrace"
DHRY_LINXTRACE="${LINXTRACE_DIR}/dhrystone.linxtrace"
REPORT="${OUT_DIR}/linxcore_benchmark_report.md"
rm -f "${CORE_TRACE}" "${DHRY_TRACE}" "${CORE_TRACE_TXT}" "${DHRY_TRACE_TXT}"
rm -f "${CORE_LOG}" "${DHRY_LOG}" "${CORE_LINXTRACE}" "${DHRY_LINXTRACE}" "${REPORT}"

LAST_OUTCOME="unknown"
LAST_CYCLES="n/a"

extract_run_outcome() {
  local log="$1"
  local ok_line deadlock_line max_cycles_line
  LAST_OUTCOME="unknown"
  LAST_CYCLES="n/a"

  ok_line="$(grep -E '^ok: (program exited|max commits reached|core halted), cycles=[0-9]+' "${log}" | tail -n 1 || true)"
  if [[ -n "${ok_line}" ]]; then
    LAST_CYCLES="$(printf '%s\n' "${ok_line}" | sed -E 's/.*cycles=([0-9]+).*/\1/')"
    case "${ok_line}" in
      "ok: program exited,"*)
        LAST_OUTCOME="program_exited"
        ;;
      "ok: max commits reached,"*)
        LAST_OUTCOME="max_commits"
        ;;
      "ok: core halted,"*)
        LAST_OUTCOME="core_halted"
        ;;
      *)
        LAST_OUTCOME="ok_unknown"
        ;;
    esac
    return 0
  fi

  if grep -q '^error: deadlock detected after ' "${log}"; then
    LAST_OUTCOME="deadlock"
    deadlock_line="$(grep -E '^[[:space:]]*cycle=[0-9]+' "${log}" | tail -n 1 || true)"
    if [[ -n "${deadlock_line}" ]]; then
      LAST_CYCLES="$(printf '%s\n' "${deadlock_line}" | sed -E 's/.*cycle=([0-9]+).*/\1/')"
    fi
    return 0
  fi

  if grep -q '^error: max cycles reached:' "${log}"; then
    LAST_OUTCOME="max_cycles"
    max_cycles_line="$(grep -E '^error: max cycles reached: [0-9]+' "${log}" | tail -n 1 || true)"
    if [[ -n "${max_cycles_line}" ]]; then
      LAST_CYCLES="$(printf '%s\n' "${max_cycles_line}" | sed -E 's/.*: ([0-9]+).*/\1/')"
    fi
    return 0
  fi

  if grep -q '^error:' "${log}"; then
    LAST_OUTCOME="error"
  fi
}

is_success_outcome() {
  local outcome="$1"
  case "${outcome}" in
    program_exited|max_commits|core_halted)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

run_one() {
  local name="$1"
  local memh="$2"
  local trace="$3"
  local trace_txt="$4"
  local boot_pc="$5"
  local accept_exit_codes="$6"
  local log="${LOG_DIR}/${name}_linxcore_cpp.log"
  local linxtrace="${LINXTRACE_DIR}/${name}.linxtrace"
  local raw_trace="${LINXTRACE_DIR}/${name}.raw_events.jsonl"
  local map_report="${LINXTRACE_DIR}/${name}.linxtrace.map.json"
  local run_rc=0
  local -a run_env=()
  echo "[bench] ${name}"
  run_env+=(
    "PYC_BOOT_PC=${boot_pc}"
    "PYC_BOOT_SP=${BOOT_SP}"
    "PYC_MAX_CYCLES=${MAX_CYCLES}"
    "PYC_ACCEPT_EXIT_CODES=${accept_exit_codes}"
  )
  if [[ "${BENCH_TRACE}" == "1" ]]; then
    run_env+=(
      "PYC_COMMIT_TRACE=${trace}"
      "PYC_COMMIT_TRACE_TEXT=${trace_txt}"
    )
  fi
  if [[ "${BENCH_LINXTRACE}" == "1" ]]; then
    run_env+=(
      "PYC_RAW_TRACE=${raw_trace}"
    )
  fi

  if env "${run_env[@]}" bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${memh}" > "${log}" 2>&1; then
    run_rc=0
  else
    run_rc=$?
  fi

  extract_run_outcome "${log}"
  echo "[bench] ${name} rc=${run_rc} outcome=${LAST_OUTCOME} cycles=${LAST_CYCLES}"
  tail -n 1 "${log}" || true
  if [[ "${run_rc}" -eq 0 && "${BENCH_LINXTRACE}" == "1" && -s "${raw_trace}" ]]; then
    python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" \
      --raw "${raw_trace}" \
      --out "${linxtrace}" \
      --map-report "${map_report}" \
      --commit-text "${trace_txt}" >/dev/null
    python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
      "${linxtrace}" \
      --require-stages IB,IQ,CMT >/dev/null
  fi
  return "${run_rc}"
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
  local elf=""
  if [[ -n "${BOOT_PC_OVERRIDE}" ]]; then
    echo "${BOOT_PC_OVERRIDE}"
    return 0
  fi
  if [[ "${name}" == "coremark" && "${core_real}" == "1" ]]; then
    if [[ -n "${LINXISA_ROOT}" ]]; then
      elf="${LINXISA_ROOT}/workloads/generated/elf/coremark.elf"
    fi
    if [[ ! -f "${elf}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" ]]; then
      elf="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf"
    fi
    local e=""
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  if [[ "${name}" == "dhrystone" && "${dhry_real}" == "1" ]]; then
    if [[ -n "${LINXISA_ROOT}" ]]; then
      elf="${LINXISA_ROOT}/workloads/generated/elf/dhrystone.elf"
    fi
    if [[ ! -f "${elf}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf" ]]; then
      elf="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf"
    fi
    local e=""
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  echo "${BOOT_PC_DEFAULT}"
}

CORE_BOOT_PC="$(resolve_boot_pc "coremark")"
DHRY_BOOT_PC="$(resolve_boot_pc "dhrystone")"

CORE_RUN_RC=0
DHRY_RUN_RC=0
CORE_OUTCOME="unknown"
DHRY_OUTCOME="unknown"
CORE_CYCLES="n/a"
DHRY_CYCLES="n/a"

if run_one "coremark" "${CORE_MEMH}" "${CORE_TRACE}" "${CORE_TRACE_TXT}" "${CORE_BOOT_PC}" "${CORE_ACCEPT_EXIT_CODES}"; then
  CORE_RUN_RC=0
else
  CORE_RUN_RC=$?
fi
CORE_OUTCOME="${LAST_OUTCOME}"
CORE_CYCLES="${LAST_CYCLES}"

if run_one "dhrystone" "${DHRY_MEMH}" "${DHRY_TRACE}" "${DHRY_TRACE_TXT}" "${DHRY_BOOT_PC}" "${DHRY_ACCEPT_EXIT_CODES}"; then
  DHRY_RUN_RC=0
else
  DHRY_RUN_RC=$?
fi
DHRY_OUTCOME="${LAST_OUTCOME}"
DHRY_CYCLES="${LAST_CYCLES}"

CORE_COMMITS="n/a"
DHRY_COMMITS="n/a"
CORE_TEXT_ROWS="n/a"
DHRY_TEXT_ROWS="n/a"
if [[ "${BENCH_TRACE}" == "1" ]]; then
  if [[ -f "${CORE_TRACE}" ]]; then
    CORE_COMMITS="$(wc -l < "${CORE_TRACE}" | tr -d ' ')"
  fi
  if [[ -f "${DHRY_TRACE}" ]]; then
    DHRY_COMMITS="$(wc -l < "${DHRY_TRACE}" | tr -d ' ')"
  fi
  if [[ -f "${CORE_TRACE_TXT}" ]]; then
    CORE_TEXT_ROWS="$(grep -c '^seq=' "${CORE_TRACE_TXT}" || true)"
  fi
  if [[ -f "${DHRY_TRACE_TXT}" ]]; then
    DHRY_TEXT_ROWS="$(grep -c '^seq=' "${DHRY_TRACE_TXT}" || true)"
  fi
fi

core_status="fail"
dhry_status="fail"
core_reason="target_miss"
dhry_reason="target_miss"
if [[ "${CORE_RUN_RC}" -ne 0 ]]; then
  core_reason="run_rc_${CORE_RUN_RC}"
elif ! is_success_outcome "${CORE_OUTCOME}"; then
  core_reason="${CORE_OUTCOME}"
elif [[ ! "${CORE_CYCLES}" =~ ^[0-9]+$ ]]; then
  core_reason="missing_cycles"
elif [[ "${CORE_CYCLES}" -le "${CORE_TARGET_CYCLES}" ]]; then
  core_status="pass"
  core_reason="ok"
fi
if [[ "${DHRY_RUN_RC}" -ne 0 ]]; then
  dhry_reason="run_rc_${DHRY_RUN_RC}"
elif ! is_success_outcome "${DHRY_OUTCOME}"; then
  dhry_reason="${DHRY_OUTCOME}"
elif [[ ! "${DHRY_CYCLES}" =~ ^[0-9]+$ ]]; then
  dhry_reason="missing_cycles"
elif [[ "${DHRY_CYCLES}" -le "${DHRY_TARGET_CYCLES}" ]]; then
  dhry_status="pass"
  dhry_reason="ok"
fi

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

| Workload | Run RC | Outcome | LinxCore OOO PYC cycles | Target cycles | Status | Reason |
| --- | ---: | --- | ---: | ---: | --- | --- |
| CoreMark | ${CORE_RUN_RC} | ${CORE_OUTCOME} | ${CORE_CYCLES} | ${CORE_TARGET_CYCLES} | ${core_status} | ${core_reason} |
| Dhrystone | ${DHRY_RUN_RC} | ${DHRY_OUTCOME} | ${DHRY_CYCLES} | ${DHRY_TARGET_CYCLES} | ${dhry_status} | ${dhry_reason} |

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
  echo "error: benchmark target miss (core=${core_status}/${core_reason}, dhry=${dhry_status}/${dhry_reason})" >&2
  exit 1
fi
