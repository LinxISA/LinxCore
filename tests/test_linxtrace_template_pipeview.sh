#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_SCRIPT="${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
OUT="${ROOT_DIR}/generated/linxtrace/coremark/linxtrace_dfx_template_test.linxtrace"

if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing template smoke memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${MEMH}" "${PYC_LINXTRACE_MAX_COMMITS:-200}" >/dev/null

name="$(basename "${MEMH}")"
name="${name%.memh}"
OUT="${ROOT_DIR}/generated/linxtrace/${name}_${PYC_LINXTRACE_MAX_COMMITS:-200}insn.linxtrace"

python3 "${CHECK_SCRIPT}" "${OUT}" --require-stages IB,CMT

python3 - <<PY
import json
from pathlib import Path
events = [json.loads(line) for line in Path(r"${OUT}").read_text().splitlines() if line.strip()]
meta = events[0]
if not any(row.get("row_kind") == "uop" for row in meta.get("row_catalog", [])):
    raise SystemExit("template linxtrace missing uop rows")
if not any(rec.get("type") == "OP_DEF" and rec.get("kind") == "template_child" for rec in events):
    raise SystemExit("missing template_child rows in linxtrace")
PY

echo "ok: template dfx linxtrace smoke ${OUT}"
