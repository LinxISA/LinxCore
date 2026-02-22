#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_konata_sanity.XXXXXX)"
LINXTRACE="${TMP_DIR}/trace.linxtrace.jsonl"
LINXMETA="${TMP_DIR}/trace.linxtrace.meta.json"
TRACE="${TMP_DIR}/commit.jsonl"
MEMH="${PYC_LINXTRACE_TEST_MEMH:-}"

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
  echo "error: missing memh for linxtrace sanity" >&2
  exit 2
fi

PYC_LINXTRACE=1 \
PYC_LINXTRACE_PATH="${LINXTRACE}" \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-2000}" \
PYC_COMMIT_TRACE="${TRACE}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null 2>&1 || true

if [[ ! -s "${LINXTRACE}" ]]; then
  echo "error: missing linxtrace output" >&2
  exit 1
fi
if [[ ! -s "${TRACE}" ]]; then
  echo "error: missing commit trace output" >&2
  exit 1
fi

if [[ ! -s "${LINXMETA}" ]]; then
  echo "error: missing linxtrace meta output" >&2
  exit 1
fi

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" "${LINXTRACE}" --meta "${LINXMETA}"

python3 - <<PY
from pathlib import Path
import json

events = [json.loads(line) for line in Path(r"${LINXTRACE}").read_text().splitlines() if line.strip()]
trace_rows = [json.loads(line) for line in Path(r"${TRACE}").read_text().splitlines() if line.strip()]

rob_occ = sum(1 for rec in events if rec.get("type") == "OCC" and rec.get("stage_id") == "ROB")
retires = sum(1 for rec in events if rec.get("type") == "RETIRE")
if rob_occ < 1:
    raise SystemExit("no ROB stages in linxtrace")
if retires < 1:
    raise SystemExit("no retire records in linxtrace")
if len(trace_rows) < 1:
    raise SystemExit("no commit trace rows")
if rob_occ > len(trace_rows) * 64:
    raise SystemExit(f"ROB occupancy implausibly high: rob={rob_occ} trace={len(trace_rows)}")
print(f"linxtrace sanity: rob_occ={rob_occ} retires={retires} commits={len(trace_rows)}")
PY

echo "linxtrace sanity test: ok"
