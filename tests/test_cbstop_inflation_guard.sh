#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_cbstop_guard.XXXXXX)"
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
REPORT_DIR="${TMP_DIR}/report"
LLVM_READELF="${LLVM_READELF:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin/llvm-readelf}"

resolve_coremark_elf() {
  local cand
  for cand in \
    "${LINX_ROOT}/workloads/generated/elf/coremark.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf"
  do
    if [[ -f "${cand}" ]]; then
      echo "${cand}"
      return 0
    fi
  done
  return 1
}

CORE_ELF="$(resolve_coremark_elf)" || {
  echo "error: missing CoreMark ELF in canonical or fixture locations" >&2
  exit 2
}

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

bash "${ROOT_DIR}/tools/trace/run_crosscheck_fifo.sh" \
  --elf "${CORE_ELF}" \
  --memh "${CORE_MEMH}" \
  --out-dir "${REPORT_DIR}" \
  --mode diagnostic \
  --max-commits 1000 \
  --tb-max-cycles 50000000 \
  --boot-pc "${BOOT_PC}" \
  --boot-sp 0x0000000007fefff0 \
  --qemu-max-seconds "${QEMU_MAX_SECONDS:-30}" >/dev/null

cp -f "${REPORT_DIR}/qemu_trace.jsonl" "${QEMU_TRACE}"
cp -f "${REPORT_DIR}/dut_trace.jsonl" "${DUT_TRACE}"

python3 - <<'PY' "${REPORT_DIR}/report/crosscheck_report.json"
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
