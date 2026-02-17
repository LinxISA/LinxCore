#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_konata_sanity.XXXXXX)"
KONATA="${TMP_DIR}/trace.konata"
TRACE="${TMP_DIR}/commit.jsonl"
MEMH="${PYC_KONATA_TEST_MEMH:-}"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ -z "${MEMH}" ]]; then
  build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
  memh1="$(printf "%s\n" "${build_out}" | sed -n '1p')"
  if [[ -n "${memh1}" && -f "${memh1}" && "${memh1}" == *"coremark"* ]]; then
    MEMH="${memh1}"
  elif [[ -f "/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_branch2.memh" ]]; then
    MEMH="/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_branch2.memh"
  elif [[ -n "${memh1}" && -f "${memh1}" ]]; then
    MEMH="${memh1}"
  fi
fi

if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing memh for konata sanity" >&2
  exit 2
fi

PYC_KONATA=1 \
PYC_KONATA_PATH="${KONATA}" \
PYC_KONATA_SKIP_BSTOP_PREFIX=0 \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-2000}" \
PYC_COMMIT_TRACE="${TRACE}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null 2>&1 || true

if [[ ! -s "${KONATA}" ]]; then
  echo "error: missing konata output" >&2
  exit 1
fi
if [[ ! -s "${TRACE}" ]]; then
  echo "error: missing commit trace output" >&2
  exit 1
fi

python3 "${ROOT_DIR}/tools/konata/check_konata_stages.py" "${KONATA}"

python3 - <<PY
from pathlib import Path
import json

konata = Path(r"${KONATA}").read_text().splitlines()
trace_rows = [json.loads(line) for line in Path(r"${TRACE}").read_text().splitlines() if line.strip()]

rob_starts = sum(1 for ln in konata if ln.startswith("S\t") and ln.rstrip().endswith("\tROB"))
retires = sum(1 for ln in konata if ln.startswith("R\t"))
if rob_starts < 1:
    raise SystemExit("no ROB stages in konata")
if retires < 1:
    raise SystemExit("no retire records in konata")
if len(trace_rows) < 1:
    raise SystemExit("no commit trace rows")
if rob_starts > len(trace_rows):
    raise SystemExit(f"ROB count exceeds commit trace rows: rob={rob_starts} trace={len(trace_rows)}")
print(f"konata sanity: rob={rob_starts} retires={retires} commits={len(trace_rows)}")
PY

echo "konata sanity test: ok"
