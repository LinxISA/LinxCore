#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"
TMP_DIR="$(mktemp -d -t linxcore_coremark_xcheck.XXXXXX)"
LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"
LINXISA_ELF_DIR="${LINXISA_ELF_DIR:-${LINXISA_ROOT:+${LINXISA_ROOT}/workloads/generated/elf}}"
CORE_ELF="${COREMARK_ELF:-${LINXISA_ELF_DIR:+${LINXISA_ELF_DIR}/coremark.elf}}"
if [[ ! -f "${CORE_ELF}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" ]]; then
  CORE_ELF="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf"
fi
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
RAW_TRACE="${TMP_DIR}/coremark_raw_events.jsonl"
LINXTRACE_OUT="${TMP_DIR}/coremark_1000.linxtrace"
XCHECK_PREFIX="${TMP_DIR}/crosscheck"
LLVM_READELF="${LLVM_READELF:-$(linxcore_resolve_llvm_readelf "${ROOT_DIR}" || true)}"
BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-release}"
case "${BUILD_PROFILE}" in
  dev-fast|release) ;;
  *)
    echo "error: unsupported LINXCORE_BUILD_PROFILE=${BUILD_PROFILE} (expected dev-fast|release)" >&2
    exit 2
    ;;
esac
if [[ -n "${PYC_TB_CXXFLAGS:-}" ]]; then
  TB_CXXFLAGS="${PYC_TB_CXXFLAGS}"
elif [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  TB_CXXFLAGS="-O1 -DNDEBUG -g0"
else
  TB_CXXFLAGS="-O2 -DNDEBUG -g0"
fi

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ ! -f "${CORE_ELF}" ]]; then
  echo "error: missing CoreMark ELF: ${CORE_ELF}" >&2
  echo "hint: set COREMARK_ELF=... or LINXISA_ELF_DIR=..." >&2
  exit 2
fi
if [[ ! -x "${LLVM_READELF}" ]]; then
  echo "error: missing llvm-readelf: ${LLVM_READELF}" >&2
  echo "hint: set LLVM_READELF=..." >&2
  exit 2
fi

BOOT_PC="$("${LLVM_READELF}" -h "${CORE_ELF}" | awk '/Entry point address:/ {print $4; exit}')"
if [[ -z "${BOOT_PC}" ]]; then
  echo "error: failed to extract ELF entry from ${CORE_ELF}" >&2
  exit 2
fi

bash "${ROOT_DIR}/tools/image/elf_to_memh.sh" "${CORE_ELF}" "${CORE_MEMH}" >/dev/null

bash "${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh" \
  --elf "${CORE_ELF}" \
  --out "${QEMU_TRACE}" \
  -- \
  -nographic -monitor none -machine virt -kernel "${CORE_ELF}" >/dev/null

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP=0x0000000007fefff0 \
PYC_MAX_COMMITS=1000 \
PYC_MAX_CYCLES=50000000 \
PYC_COMMIT_TRACE="${DUT_TRACE}" \
PYC_RAW_TRACE="${RAW_TRACE}" \
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE=failfast \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT="${XCHECK_PREFIX}" \
PYC_TB_CXXFLAGS="${TB_CXXFLAGS}" \
LINXCORE_BUILD_PROFILE="${BUILD_PROFILE}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${CORE_MEMH}" >/dev/null

python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" \
  --raw "${RAW_TRACE}" \
  --out "${LINXTRACE_OUT}" \
  --commit-text "${DUT_TRACE%.jsonl}.txt" \
  --elf "${CORE_ELF}" >/dev/null

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
  "${LINXTRACE_OUT}" \
  --require-stages IB,IQ,CMT >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode failfast \
  --max-commits 1000 \
  --report-dir "${TMP_DIR}" >/dev/null

echo "coremark xcheck 1000: ok"
