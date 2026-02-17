#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_CPP_SCRIPT="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
CHECK_SCRIPT="${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
OUT="${ROOT_DIR}/generated/linxtrace/coremark/linxtrace_dfx_template_test.linxtrace.jsonl"
META="${ROOT_DIR}/generated/linxtrace/coremark/linxtrace_dfx_template_test.linxtrace.meta.json"

if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing template smoke memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
PYC_LINXTRACE=1 \
PYC_LINXTRACE_PATH="${OUT}" \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-800}" \
  bash "${RUN_CPP_SCRIPT}" "${MEMH}" >/dev/null 2>&1 || true

python3 "${CHECK_SCRIPT}" "${OUT}" --meta "${META}" --require-stages F1,D3,ROB,FLS

python3 - <<PY
import json
from pathlib import Path
events = [json.loads(line) for line in Path(r"${OUT}").read_text().splitlines() if line.strip()]
if not any(rec.get("type") == "OP_DEF" and rec.get("kind") == "template_child" for rec in events):
    raise SystemExit("missing template_child rows in linxtrace")
PY

echo "ok: template dfx linxtrace smoke ${OUT}"
