#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_coremark_xcheck.XXXXXX)"
CORE_ELF="/Users/zhoubot/linx-isa/workloads/generated/elf/coremark.elf"
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
LINXTRACE_OUT="${TMP_DIR}/coremark_1000.linxtrace.jsonl"
XCHECK_PREFIX="${TMP_DIR}/crosscheck"
LLVM_READELF="${LLVM_READELF:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf}"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ ! -f "${CORE_ELF}" ]]; then
  echo "error: missing CoreMark ELF: ${CORE_ELF}" >&2
  exit 2
fi
if [[ ! -x "${LLVM_READELF}" ]]; then
  echo "error: missing llvm-readelf: ${LLVM_READELF}" >&2
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
PYC_LINXTRACE=1 \
PYC_LINXTRACE_PATH="${LINXTRACE_OUT}" \
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE=failfast \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT="${XCHECK_PREFIX}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${CORE_MEMH}" >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode failfast \
  --max-commits 1000 \
  --report-dir "${TMP_DIR}" >/dev/null

echo "coremark xcheck 1000: ok"
