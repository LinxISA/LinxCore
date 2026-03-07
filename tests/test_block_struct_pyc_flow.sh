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

# Add LinxCore src/ to the default pyc PYTHONPATH.
PYTHONPATH_VAL="$(pyc_pythonpath):${ROOT_DIR}/src"

run_case() {
  local name="$1"
  local tb_src="$2"
  local out_dir="${ROOT_DIR}/out/pyc/${name}"

  rm -rf "${out_dir}" >/dev/null 2>&1 || true
  mkdir -p "${out_dir}"

  echo "[block_struct] build+sim ${name}"
  PYTHONPATH="${PYTHONPATH_VAL}" PYTHONDONTWRITEBYTECODE=1 PYCC="${PYCC}" \
    python3 -m pycircuit.cli build \
      "${tb_src}" \
      --out-dir "${out_dir}" \
      --target both \
      --jobs "${PYC_SIM_JOBS:-4}" \
      --logic-depth "${PYC_SIM_LOGIC_DEPTH:-128}" \
      --run-verilator

  local cpp_bin
  cpp_bin="$(
    python3 - "${out_dir}/project_manifest.json" <<'PY'
import json
import sys
from pathlib import Path

p = Path(sys.argv[1])
data = json.loads(p.read_text(encoding='utf-8'))
print(data.get('cpp_executable', ''))
PY
  )"

  if [[ -z "${cpp_bin}" || ! -x "${cpp_bin}" ]]; then
    echo "error: missing cpp_executable for ${name}: ${cpp_bin}" >&2
    exit 3
  fi

  echo "[block_struct] run(cpp) ${name}: ${cpp_bin}"
  (cd "${out_dir}" && "${cpp_bin}")
}

run_case "block_struct_brob" "${ROOT_DIR}/tests/pyc/tb_block_struct_brob.py"
run_case "block_struct_rob" "${ROOT_DIR}/tests/pyc/tb_block_struct_rob.py"

echo "block_struct pyc flow passed"
