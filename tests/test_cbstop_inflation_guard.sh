#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_cbstop_guard.XXXXXX)"
CORE_ELF="/Users/zhoubot/linx-isa/workloads/generated/elf/coremark.elf"
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
REPORT_DIR="${TMP_DIR}/report"
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
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE=diagnostic \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT="${REPORT_DIR}/crosscheck" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${CORE_MEMH}" >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode diagnostic \
  --max-commits 1000 \
  --report-dir "${REPORT_DIR}" >/dev/null

python3 - <<'PY' "${REPORT_DIR}/crosscheck_report.json"
import json
import pathlib
import sys

report = pathlib.Path(sys.argv[1])
obj = json.loads(report.read_text())
q = int(obj["cbstop_counts"]["qemu"])
d = int(obj["cbstop_counts"]["dut"])
if q == 0 and d > 0:
    raise SystemExit(f"error: C.BSTOP inflation (qemu={q}, dut={d})")
if q > 0 and d > (q * 8):
    raise SystemExit(f"error: C.BSTOP inflation ratio too high (qemu={q}, dut={d})")
print(f"cbstop guard ok: qemu={q} dut={d}")
PY

echo "cbstop inflation guard: ok"
