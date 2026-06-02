#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"

PYC_ROOT_DIR="$(linxcore_resolve_pyc_root "${ROOT_DIR}" || true)"
if [[ -z "${PYC_ROOT_DIR}" || ! -d "${PYC_ROOT_DIR}" ]]; then
  echo "error: cannot locate pyCircuit; set PYC_ROOT=..." >&2
  exit 2
fi

# shellcheck disable=SC1090
source "${PYC_ROOT_DIR}/flows/scripts/lib.sh"
pyc_find_pycc

OUT_DIR="${ROOT_DIR}/out/pyc/code_template_unit_wait_block"
rm -rf "${OUT_DIR}" >/dev/null 2>&1 || true
mkdir -p "${OUT_DIR}"

PYTHONPATH="$(pyc_pythonpath):${ROOT_DIR}/src" PYTHONDONTWRITEBYTECODE=1 PYCC="${PYCC}" \
  python3 -m pycircuit.cli build \
    "${ROOT_DIR}/tests/pyc/tb_code_template_unit_wait_block.py" \
    --out-dir "${OUT_DIR}" \
    --target both \
    --jobs "${PYC_SIM_JOBS:-4}" \
    --logic-depth "${PYC_SIM_LOGIC_DEPTH:-128}" \
    --run-verilator

CPP_BIN="$(
  python3 - "${OUT_DIR}/project_manifest.json" <<'PY'
import json
import sys
from pathlib import Path

p = Path(sys.argv[1])
data = json.loads(p.read_text(encoding="utf-8"))
print(data.get("cpp_executable", ""))
PY
)"

if [[ -z "${CPP_BIN}" || ! -x "${CPP_BIN}" ]]; then
  echo "error: missing cpp_executable for CTU handoff guard: ${CPP_BIN}" >&2
  exit 3
fi

(cd "${OUT_DIR}" && "${CPP_BIN}")

echo "ctu handoff guard: ok"
