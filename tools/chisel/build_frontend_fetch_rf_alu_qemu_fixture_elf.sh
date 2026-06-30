#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
LLVM_BIN="${LLVM_BIN:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/generated/chisel-frontend-fetch-rf-alu-qemu-fixture}"
OUTPUT=""
TEXT_BASE="${TEXT_BASE:-0x10000}"
LONG_BODY=0

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [--output <fixture.elf>] [--out-dir <dir>] [--text-base <hex>] [--long-body]

Builds a tiny legal-entry Linx ELF for the reduced live QEMU fetch RF/ALU gate:
  C.BSTART.STD; ADD; ADDI; C.MOVR; C.BSTOP

With --long-body, the scalar body is extended with additional ADDI, ADD,
C.MOVI, and C.MOVR rows that are already supported by the reduced Chisel ALU.

The scalar prefix starts two bytes after the entry block header. Use the printed
pc filter values with run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh until
the live frontend gate supports block headers.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --text-base) TEXT_BASE="$2"; shift 2 ;;
    --long-body) LONG_BODY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ "${OUT_DIR}" != /* ]]; then
  OUT_DIR="${ROOT_DIR}/${OUT_DIR}"
fi
mkdir -p "${OUT_DIR}"

if [[ -z "${OUTPUT}" ]]; then
  OUTPUT="${OUT_DIR}/frontend_fetch_rf_alu_qemu_fixture.elf"
elif [[ "${OUTPUT}" != /* ]]; then
  OUTPUT="${ROOT_DIR}/${OUTPUT}"
fi
mkdir -p "$(dirname -- "${OUTPUT}")"

LLVM_MC="${LLVM_BIN}/llvm-mc"
LD_LLD="${LLVM_BIN}/ld.lld"
LLVM_OBJDUMP="${LLVM_BIN}/llvm-objdump"
if [[ ! -x "${LLVM_MC}" || ! -x "${LD_LLD}" || ! -x "${LLVM_OBJDUMP}" ]]; then
  echo "error: missing llvm-mc, ld.lld, or llvm-objdump under ${LLVM_BIN}" >&2
  exit 2
fi

ASM="${OUT_DIR}/frontend_fetch_rf_alu_qemu_fixture.s"
OBJ="${OUT_DIR}/frontend_fetch_rf_alu_qemu_fixture.o"
DISASM="${OUT_DIR}/frontend_fetch_rf_alu_qemu_fixture.objdump"

if [[ "${LONG_BODY}" -eq 0 ]]; then
  SCALAR_PC_HI_OFFSET=0xb
  cat > "${ASM}" <<'ASM'
.section .text,"ax",@progbits
.globl _start
_start:
  .short 0x0800       # C.BSTART.STD
  .word  0x00520185   # ADD  r3, r4, r5
  .word  0x7ff18315   # ADDI r6, r3, 0x7ff
  .short 0x2986       # C.MOVR r5, r6
  .short 0x0000       # C.BSTOP
ASM
else
  SCALAR_PC_HI_OFFSET=0x17
  cat > "${ASM}" <<'ASM'
.section .text,"ax",@progbits
.globl _start
_start:
  .short 0x0800       # C.BSTART.STD
  .word  0x00520185   # ADD  r3, r4, r5
  .word  0x7ff18315   # ADDI r6, r3, 0x7ff
  .short 0x2986       # C.MOVR r5, r6
  .word  0x00130395   # ADDI r7, r6, 1
  .word  0x06338405   # ADD  r8, r7, r3
  .short 0x4956       # C.MOVI r9, 5
  .short 0x3a06       # C.MOVR r7, r8
  .short 0x0000       # C.BSTOP
ASM
fi

"${LLVM_MC}" -triple=linx64 -filetype=obj -o "${OBJ}" "${ASM}"
"${LD_LLD}" -Ttext="${TEXT_BASE}" -e _start -o "${OUTPUT}" "${OBJ}"
"${LLVM_OBJDUMP}" -d "${OUTPUT}" > "${DISASM}"

python3 - "${TEXT_BASE}" "${SCALAR_PC_HI_OFFSET}" <<'PY'
import sys
base = int(sys.argv[1], 0)
hi_offset = int(sys.argv[2], 0)
print(f"fixture-elf-text-base=0x{base:x}")
print(f"fixture-elf-scalar-pc-lo=0x{base + 2:x}")
print(f"fixture-elf-scalar-pc-hi=0x{base + hi_offset:x}")
PY
echo "fixture-elf=${OUTPUT}"
echo "fixture-elf-objdump=${DISASM}"
