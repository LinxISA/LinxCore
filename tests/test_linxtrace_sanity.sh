#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_konata_sanity.XXXXXX)"
MEMH="${PYC_LINXTRACE_TEST_MEMH:-}"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

if [[ -z "${MEMH}" ]]; then
  if [[ -f "${ROOT_DIR}/tests/artifacts/suites/branch.memh" ]]; then
    MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
  elif [[ -f "/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_branch2.memh" ]]; then
    MEMH="/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_branch2.memh"
  else
    build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
    memh1="$(printf "%s\n" "${build_out}" | sed -n '1p')"
    if [[ -n "${memh1}" && -f "${memh1}" ]]; then
      MEMH="${memh1}"
    fi
  fi
fi

if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing memh for linxtrace sanity" >&2
  exit 2
fi

MAX_COMMITS="${PYC_LINXTRACE_MAX_COMMITS:-200}"
bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${MEMH}" "${MAX_COMMITS}" >/dev/null

name="$(basename "${MEMH}")"
name="${name%.memh}"
LINXTRACE="${ROOT_DIR}/generated/linxtrace/${name}_${MAX_COMMITS}insn.linxtrace"
if [[ "${name}" == *"coremark"* ]]; then
  LINXTRACE="${ROOT_DIR}/generated/linxtrace/coremark/${name}_${MAX_COMMITS}insn.linxtrace"
elif [[ "${name}" == *"dhrystone"* ]]; then
  LINXTRACE="${ROOT_DIR}/generated/linxtrace/dhrystone/${name}_${MAX_COMMITS}insn.linxtrace"
fi

if [[ ! -s "${LINXTRACE}" ]]; then
  echo "error: missing linxtrace output" >&2
  exit 1
fi

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" "${LINXTRACE}"

python3 - <<PY
from pathlib import Path
import json

events = [json.loads(line) for line in Path(r"${LINXTRACE}").read_text().splitlines() if line.strip()]

meta = events[0]
if meta.get("type") != "META":
    raise SystemExit("linxtrace missing in-band META")
row_catalog = meta.get("row_catalog", [])
if not any(row.get("row_kind") == "uop" for row in row_catalog):
    raise SystemExit("row_catalog missing uop rows")
if not any(row.get("row_kind") == "block" for row in row_catalog):
    raise SystemExit("row_catalog missing block rows")
cmt_occ = sum(1 for rec in events if rec.get("type") == "OCC" and rec.get("stage_id") == "CMT")
retires = sum(1 for rec in events if rec.get("type") == "RETIRE")
block_evt = sum(1 for rec in events if rec.get("type") == "BLOCK_EVT")
if not any(rec.get("type") == "OCC" and rec.get("stage_id") == "IB" for rec in events):
    raise SystemExit("no IB stages in linxtrace")
if cmt_occ < 1:
    raise SystemExit("no CMT stages in linxtrace")
if retires < 1:
    raise SystemExit("no retire records in linxtrace")
if block_evt < 1:
    raise SystemExit("no BLOCK_EVT records in linxtrace")
if any(rec.get("type") == "OCC" and rec.get("stage_id") == "ROB" for rec in events):
    raise SystemExit("legacy ROB stage leaked into canonical linxtrace")
print(f"linxtrace sanity: cmt_occ={cmt_occ} retires={retires} block_evt={block_evt}")
PY

echo "linxtrace sanity test: ok"
