#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-trace-top"
TOP_SV="${SV_DIR}/LinxCoreFrontendTraceTop.sv"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-frontend-trace-top-xcheck}"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
DUT_TRACE="${TRACE_DIR}/dut.chisel.jsonl"
QEMU_TRACE="${TRACE_DIR}/qemu.reference.jsonl"

if [[ "${1:-}" == "--check-dependencies" ]]; then
  exec python3 "${ROOT_DIR}/tools/chisel/check_crosscheck_wrapper_dependencies.py"
fi
if [[ "${1:-}" == "--dry-run" ]]; then
  EMITTER_SOURCE="${ROOT_DIR}/chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala"
  HARNESS_SOURCE="${ROOT_DIR}/tools/chisel/frontend_trace_top_tb.cpp"
  CROSSCHECK="${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh"
  [[ -f "${EMITTER_SOURCE}" ]] || { echo "error: missing emitter source: ${EMITTER_SOURCE}" >&2; exit 2; }
  [[ -f "${HARNESS_SOURCE}" ]] || { echo "error: missing harness: ${HARNESS_SOURCE}" >&2; exit 2; }
  [[ -f "${CROSSCHECK}" ]] || { echo "error: missing crosscheck: ${CROSSCHECK}" >&2; exit 2; }
  grep -q 'object EmitLinxCoreFrontendTraceTop' "${EMITTER_SOURCE}" || {
    echo "error: canonical frontend trace emitter object is absent" >&2
    exit 2
  }
  grep -q 'LinxCoreFrontendTraceTop' "${HARNESS_SOURCE}" || {
    echo "error: harness does not bind LinxCoreFrontendTraceTop" >&2
    exit 2
  }
  bash -n "${CROSSCHECK}"
  echo "frontend-trace-top-xcheck-dry-run=pass"
  echo "emitter=linxcore.top.EmitLinxCoreFrontendTraceTop"
  echo "top=LinxCoreFrontendTraceTop"
  echo "harness=${HARNESS_SOURCE} --dut-trace ${DUT_TRACE} --qemu-trace ${QEMU_TRACE}"
  echo "crosscheck=${CROSSCHECK} --qemu-trace ${QEMU_TRACE} --dut-trace ${DUT_TRACE} --report-dir ${REPORT_DIR} --max-commits 3 --mode failfast"
  exit 0
fi
if [[ $# -ne 0 ]]; then
  echo "error: unsupported argument: $1" >&2
  exit 2
fi

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for Chisel frontend trace top xcheck" >&2
  exit 2
fi

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.top.EmitLinxCoreFrontendTraceTop"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no frontend trace top SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module LinxCoreFrontendTraceTop \
  --exe "${ROOT_DIR}/tools/chisel/frontend_trace_top_tb.cpp" \
  --build \
  -Mdir "${OBJ_DIR}" \
  -o linxcore_frontend_trace_top_tb \
  -CFLAGS "-std=c++17 -O2"

"${OBJ_DIR}/linxcore_frontend_trace_top_tb" \
  --dut-trace "${DUT_TRACE}" \
  --qemu-trace "${QEMU_TRACE}"

bash "${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --report-dir "${REPORT_DIR}" \
  --max-commits 3 \
  --mode failfast

echo "frontend-trace-top-xcheck-report=${REPORT_DIR}"
