#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"
TMP_DIR="$(mktemp -d -t linxcore_cbstop_guard.XXXXXX)"
LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"
LINXISA_ELF_DIR="${LINXISA_ELF_DIR:-${LINXISA_ROOT:+${LINXISA_ROOT}/workloads/generated/elf}}"
CORE_ELF="${COREMARK_ELF:-${LINXISA_ELF_DIR:+${LINXISA_ELF_DIR}/coremark.elf}}"
if [[ ! -f "${CORE_ELF}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" ]]; then
  CORE_ELF="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf"
fi
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
REPORT_DIR="${TMP_DIR}/report"
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
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE=diagnostic \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT="${REPORT_DIR}/crosscheck" \
PYC_TB_CXXFLAGS="${TB_CXXFLAGS}" \
LINXCORE_BUILD_PROFILE="${BUILD_PROFILE}" \
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
