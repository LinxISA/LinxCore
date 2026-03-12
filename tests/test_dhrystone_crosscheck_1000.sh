#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_dhrystone_xcheck.XXXXXX)"
DHRY_MEMH="${TMP_DIR}/dhrystone_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/dhrystone_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/dhrystone_dut_commit.jsonl"
LINXTRACE_OUT="${TMP_DIR}/dhrystone_1000.linxtrace"
RAW_TRACE="${TMP_DIR}/dhrystone_raw_events.jsonl"
COMMIT_TEXT="${TMP_DIR}/dhrystone_dut_commit.txt"
LLVM_READELF="${LLVM_READELF:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin/llvm-readelf}"

resolve_dhrystone_elf() {
  local cand
  for cand in \
    "${LINX_ROOT}/workloads/generated/elf/dhrystone.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/dhrystone/dhrystone.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl/elf/dhrystone/dhrystone.elf"
  do
    if [[ -f "${cand}" ]]; then
      echo "${cand}"
      return 0
    fi
  done
  return 1
}

DHRY_ELF="$(resolve_dhrystone_elf)" || {
  echo "error: missing Dhrystone ELF in canonical or fixture locations" >&2
  exit 2
}

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ ! -f "${DHRY_ELF}" ]]; then
  echo "error: missing Dhrystone ELF: ${DHRY_ELF}" >&2
  exit 2
fi
if [[ ! -x "${LLVM_READELF}" ]]; then
  echo "error: missing llvm-readelf: ${LLVM_READELF}" >&2
  exit 2
fi

BOOT_PC="$("${LLVM_READELF}" -h "${DHRY_ELF}" | awk '/Entry point address:/ {print $4; exit}')"
if [[ -z "${BOOT_PC}" ]]; then
  echo "error: failed to extract ELF entry from ${DHRY_ELF}" >&2
  exit 2
fi

bash "${ROOT_DIR}/tools/image/elf_to_memh.sh" "${DHRY_ELF}" "${DHRY_MEMH}" >/dev/null

bash "${ROOT_DIR}/tools/trace/run_crosscheck_fifo.sh" \
  --elf "${DHRY_ELF}" \
  --memh "${DHRY_MEMH}" \
  --out-dir "${TMP_DIR}/fifo" \
  --mode failfast \
  --max-commits 1000 \
  --tb-max-cycles 50000000 \
  --boot-pc "${BOOT_PC}" \
  --boot-sp 0x0000000007fefff0 \
  --qemu-max-seconds "${QEMU_MAX_SECONDS:-30}" >/dev/null

cp -f "${TMP_DIR}/fifo/qemu_trace.jsonl" "${QEMU_TRACE}"
cp -f "${TMP_DIR}/fifo/dut_trace.jsonl" "${DUT_TRACE}"

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP=0x0000000007fefff0 \
PYC_MAX_COMMITS=1000 \
PYC_MAX_CYCLES=50000000 \
PYC_ACCEPT_EXIT_CODES=1 \
PYC_LINXTRACE=0 \
PYC_RAW_TRACE="${RAW_TRACE}" \
PYC_COMMIT_TRACE="${DUT_TRACE}" \
PYC_COMMIT_TRACE_TEXT="${COMMIT_TEXT}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${DHRY_MEMH}" >/dev/null

python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" \
  --raw "${RAW_TRACE}" \
  --out "${LINXTRACE_OUT}" \
  --commit-text "${COMMIT_TEXT}" \
  --elf "${DHRY_ELF}" >/dev/null

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
  "${LINXTRACE_OUT}" \
  --require-stages "F0,F3,D1,D3,IQ,BROB,CMT" \
  --single-stage-per-cycle >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode failfast \
  --max-commits 1000 \
  --report-dir "${TMP_DIR}" >/dev/null

echo "dhrystone xcheck 1000: ok"
