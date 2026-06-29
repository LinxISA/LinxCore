#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/top-xcheck"
TOP_SV="${SV_DIR}/LinxCoreTop.sv"
BUILD_DIR="${ROOT_DIR}/generated/chisel-trace-replay-xcheck"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
RAW_INPUT_TRACE=""
INPUT_TRACE="${TRACE_DIR}/input.normalized.jsonl"
DUT_TRACE="${TRACE_DIR}/dut.chisel.jsonl"
QEMU_TRACE="${TRACE_DIR}/qemu.reference.jsonl"
MAX_COMMITS="4"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [--input-trace <commit.jsonl>] [--max-commits <int>]

When --input-trace is omitted, this gate generates a small flat commit trace
fixture. Any provided input trace is normalized before the Verilator replay.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input-trace) RAW_INPUT_TRACE="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

if [[ -z "${RAW_INPUT_TRACE}" ]]; then
  RAW_INPUT_TRACE="${TRACE_DIR}/input.fixture.jsonl"
  python3 - "${RAW_INPUT_TRACE}" <<'PY'
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
rows = [
    {
        "seq": 0,
        "cycle": 20,
        "pc": 0x1000,
        "insn": 0x13,
        "len": 4,
        "bid": 9,
        "gid": 0,
        "rid": 0,
        "block_bid": 0x300000090,
        "wb_valid": 1,
        "wb_rd": 5,
        "wb_data": 0x44,
        "next_pc": 0x1004,
    },
    {
        "seq": 1,
        "cycle": 21,
        "pc": 0x1004,
        "insn": 0x23,
        "len": 4,
        "bid": 9,
        "gid": 0,
        "rid": 1,
        "block_bid": 0x300000091,
        "src0_valid": 1,
        "src0_reg": 3,
        "src0_data": 0x99,
        "mem_valid": 1,
        "mem_is_store": 1,
        "mem_addr": 0x2000,
        "mem_wdata": 0x99,
        "mem_size": 8,
        "next_pc": 0x1008,
    },
    {
        "seq": 2,
        "cycle": 22,
        "pc": 0x1008,
        "insn": 0x3,
        "len": 4,
        "bid": 9,
        "gid": 0,
        "rid": 2,
        "block_bid": 0x300000092,
        "mem_valid": 1,
        "mem_addr": 0x2000,
        "mem_rdata": 0x99,
        "mem_size": 8,
        "wb_valid": 1,
        "wb_rd": 6,
        "wb_data": 0x99,
        "next_pc": 0x100c,
    },
    {
        "seq": 3,
        "cycle": 23,
        "pc": 0x100c,
        "insn": 0x6F,
        "len": 4,
        "bid": 9,
        "gid": 0,
        "rid": 3,
        "block_bid": 0x300000093,
        "trap_valid": 1,
        "trap_cause": 0x45,
        "traparg0": 0x100c,
        "next_pc": 0x1010,
    },
]
with out.open("w", encoding="utf-8") as f:
    for row in rows:
        f.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
PY
fi

python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${RAW_INPUT_TRACE}" \
  --output "${INPUT_TRACE}" \
  --max-rows "${MAX_COMMITS}"

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
  -o linxcore_trace_replay_tb \
  -CFLAGS "-std=c++17 -O2 -DLINXCORE_COMMIT_TRACE_DUT_HEADER=\\\"VLinxCoreTop.h\\\" -DLINXCORE_COMMIT_TRACE_DUT_CLASS=VLinxCoreTop"

"${OBJ_DIR}/linxcore_trace_replay_tb" \
  --input-trace "${INPUT_TRACE}" \
  --max-rows "${MAX_COMMITS}" \
  --dut-trace "${DUT_TRACE}" \
  --qemu-trace "${QEMU_TRACE}"

bash "${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --report-dir "${REPORT_DIR}" \
  --max-commits "${MAX_COMMITS}" \
  --mode failfast

echo "trace-replay-xcheck-report=${REPORT_DIR}"
