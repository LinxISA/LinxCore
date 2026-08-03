#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/top-xcheck"
TOP_SV="${SV_DIR}/LinxCoreTop.sv"
BUILD_DIR="${ROOT_DIR}/generated/chisel-top-xcheck"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
DUT_TRACE="${TRACE_DIR}/dut.chisel.jsonl"
QEMU_TRACE="${TRACE_DIR}/qemu.reference.jsonl"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.top.EmitLinxCoreTopXcheck"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: LinxCoreTop SystemVerilog was not emitted: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no LinxCoreTop SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module LinxCoreTop \
  --exe "${ROOT_DIR}/tools/chisel/reduced_rob_trace_tb.cpp" \
  --build \
  -Mdir "${OBJ_DIR}" \
  -o linxcore_top_trace_tb \
  -CFLAGS "-std=c++17 -O2 -DLINXCORE_COMMIT_TRACE_DUT_HEADER=\\\"VLinxCoreTop.h\\\" -DLINXCORE_COMMIT_TRACE_DUT_CLASS=VLinxCoreTop"

"${OBJ_DIR}/linxcore_top_trace_tb" \
  --dut-trace "${DUT_TRACE}" \
  --qemu-trace "${QEMU_TRACE}"

bash "${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --report-dir "${REPORT_DIR}" \
  --max-commits 3 \
  --mode failfast

echo "top-xcheck-report=${REPORT_DIR}"
