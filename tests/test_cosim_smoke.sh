#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LLVM_MC="${LLVM_MC:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-mc}"
SRC="/Users/zhoubot/qemu/tests/linxisa/commit_trace_smoke.s"
TMP_DIR="$(mktemp -d -t linxcore_cosim_smoke.XXXXXX)"
OBJ="${TMP_DIR}/commit_trace_smoke.o"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ ! -x "${LLVM_MC}" ]]; then
  echo "error: llvm-mc not found: ${LLVM_MC}" >&2
  exit 2
fi
if [[ ! -f "${SRC}" ]]; then
  echo "error: smoke source not found: ${SRC}" >&2
  exit 2
fi

"${LLVM_MC}" -triple=linx64 -filetype=obj "${SRC}" -o "${OBJ}"

# Positive case: short lockstep window that includes trigger commit and one more.
bash "${ROOT_DIR}/tools/qemu/run_cosim_lockstep.sh" \
  --elf "${OBJ}" \
  --boot-pc 0x10000 \
  --trigger-pc 0x10000 \
  --terminate-pc 0x10020 \
  --max-commits 64 \
  --max-dut-cycles 500000 \
  -- \
  -nographic -monitor none -machine virt -kernel "${OBJ}" >/dev/null

# Negative case: force mismatch should fail-fast.
if bash "${ROOT_DIR}/tools/qemu/run_cosim_lockstep.sh" \
  --elf "${OBJ}" \
  --boot-pc 0x10000 \
  --trigger-pc 0x10000 \
  --terminate-pc 0x10020 \
  --max-commits 64 \
  --max-dut-cycles 500000 \
  --force-mismatch 1 \
  -- \
  -nographic -monitor none -machine virt -kernel "${OBJ}" >/dev/null 2>&1; then
  echo "error: expected forced-mismatch run to fail" >&2
  exit 1
fi

echo "cosim smoke tests: ok"
