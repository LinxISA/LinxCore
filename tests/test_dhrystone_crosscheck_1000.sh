#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_dhrystone_xcheck.XXXXXX)"
DHRY_ELF="${LINX_ROOT}/workloads/generated/elf/dhrystone.elf"
DHRY_MEMH="${TMP_DIR}/dhrystone_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/dhrystone_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/dhrystone_dut_commit.jsonl"
LINXTRACE_OUT="${TMP_DIR}/dhrystone_1000.linxtrace"
XCHECK_PREFIX="${TMP_DIR}/crosscheck"
LLVM_READELF="${LLVM_READELF:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin/llvm-readelf}"

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

bash "${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh" \
  --elf "${DHRY_ELF}" \
  --out "${QEMU_TRACE}" \
  -- \
  -nographic -monitor none -machine virt -kernel "${DHRY_ELF}" >/dev/null || true

if [[ ! -s "${QEMU_TRACE}" ]]; then
  echo "error: QEMU commit trace was not produced: ${QEMU_TRACE}" >&2
  exit 3
fi

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP=0x0000000007fefff0 \
PYC_MAX_COMMITS=1000 \
PYC_MAX_CYCLES=50000000 \
PYC_ACCEPT_EXIT_CODES=1 \
PYC_COMMIT_TRACE="${DUT_TRACE}" \
PYC_LINXTRACE=1 \
PYC_LINXTRACE_PATH="${LINXTRACE_OUT}" \
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE=failfast \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT="${XCHECK_PREFIX}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${DHRY_MEMH}" >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode failfast \
  --max-commits 1000 \
  --report-dir "${TMP_DIR}" >/dev/null

echo "dhrystone xcheck 1000: ok"
