#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MEMH="${PYC_TEST_MEMH:-/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_or.memh}"
TMP_DIR="$(mktemp -d -t linxcore_trace_schema.XXXXXX)"
TRACE="${TMP_DIR}/trace.jsonl"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ ! -f "${MEMH}" ]]; then
  build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
  memh2="$(printf "%s\n" "${build_out}" | sed -n '2p')"
  if [[ -n "${memh2}" && -f "${memh2}" ]]; then
    MEMH="${memh2}"
  fi
fi
if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing memh after benchmark build: ${MEMH}" >&2
  exit 2
fi

PYC_BOOT_PC=0x10000 \
PYC_BOOT_SP=0x00000000000ff000 \
PYC_MAX_CYCLES=12000 \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
PYC_COMMIT_TRACE="${TRACE}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null 2>&1 || true

if [[ ! -s "${TRACE}" ]]; then
  echo "error: missing trace output" >&2
  exit 1
fi

python3 - <<PY
import json
from pathlib import Path

trace = Path(r"${TRACE}")
rows = [json.loads(line) for line in trace.read_text().splitlines() if line.strip()]
if len(rows) < 16:
    raise SystemExit("trace too short")

required = [
    "pc", "insn", "len",
    "wb_valid", "wb_rd", "wb_data",
    "mem_valid", "mem_is_store", "mem_addr", "mem_wdata", "mem_rdata", "mem_size",
    "trap_valid", "trap_cause", "traparg0", "next_pc",
]
for i, row in enumerate(rows[:64]):
    for k in required:
        if k not in row:
            raise SystemExit(f"missing key {k} at row {i}")

# Normalization expectations from M1 schema.
for i, row in enumerate(rows[:256]):
    if int(row["wb_valid"]) == 0:
        # wb_rd/wb_data may be garbage in trace taps but should be numeric.
        int(row["wb_rd"])
        int(row["wb_data"])
    if int(row["mem_valid"]) == 0:
        int(row["mem_addr"])
        int(row["mem_wdata"])
        int(row["mem_rdata"])
        int(row["mem_size"])

has_store = any(int(r["mem_valid"]) and int(r["mem_is_store"]) for r in rows)
has_load = any(int(r["mem_valid"]) and not int(r["mem_is_store"]) for r in rows)
if not has_store:
    raise SystemExit("no store commit observed")
if not has_load:
    raise SystemExit("no load commit observed")

print(f"trace rows={len(rows)} store+load observed")
PY

echo "trace schema/mem tests: ok"
