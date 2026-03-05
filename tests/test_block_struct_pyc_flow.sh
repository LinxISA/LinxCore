#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

find_pyc_root() {
  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    echo "${PYC_ROOT}"
    return 0
  fi

  local super
  super="$(git -C "${ROOT_DIR}" rev-parse --show-superproject-working-tree 2>/dev/null || true)"
  if [[ -n "${super}" && -d "${super}/tools/pyCircuit" ]]; then
    echo "${super}/tools/pyCircuit"
    return 0
  fi

  local cand
  cand="${ROOT_DIR}/../../tools/pyCircuit"
  if [[ -d "${cand}" ]]; then
    echo "${cand}"
    return 0
  fi

  return 1
}

PYC_ROOT_DIR="$(find_pyc_root)" || {
  echo "error: cannot locate pyCircuit; set PYC_ROOT=..." >&2
  exit 2
}

# shellcheck disable=SC1090
source "${PYC_ROOT_DIR}/flows/scripts/lib.sh"
pyc_find_pycc

# Add LinxCore src/ to the default pyc PYTHONPATH.
find_python_bin() {
  if [[ -n "${PYC_PYTHON:-}" && -x "${PYC_PYTHON}" ]]; then
    echo "${PYC_PYTHON}"
    return 0
  fi
  local cand
  for cand in \
    "${PYC_PYTHON_BIN:-}" \
    "/opt/homebrew/bin/python3" \
    "python3.14" \
    "python3.13" \
    "python3.12" \
    "python3.11" \
    "python3.10" \
    "python3"
  do
    [[ -n "${cand}" ]] || continue
    local exe="${cand}"
    if [[ "${exe}" != /* ]]; then
      if ! command -v "${exe}" >/dev/null 2>&1; then
        continue
      fi
      exe="$(command -v "${exe}")"
    elif [[ ! -x "${exe}" ]]; then
      continue
    fi
    if "${exe}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
      echo "${exe}"
      return 0
    fi
  done
  return 1
}

PYTHON_BIN="$(find_python_bin)" || {
  echo "error: need python>=3.10 to run pyc4 frontend (set PYC_PYTHON_BIN=...)" >&2
  exit 2
}

PYTHONPATH_VAL="$(pyc_pythonpath):${ROOT_DIR}/src"

run_case() {
  local name="$1"
  local tb_src="$2"
  local out_dir="${ROOT_DIR}/out/pyc/${name}"

  rm -rf "${out_dir}" >/dev/null 2>&1 || true
  mkdir -p "${out_dir}"

  echo "[block_struct] build+sim ${name}"
  PYTHONPATH="${PYTHONPATH_VAL}" PYTHONDONTWRITEBYTECODE=1 PYCC="${PYCC}" \
    "${PYTHON_BIN}" -m pycircuit.cli build \
      "${tb_src}" \
      --out-dir "${out_dir}" \
      --target both \
      --jobs "${PYC_SIM_JOBS:-4}" \
      --logic-depth "${PYC_SIM_LOGIC_DEPTH:-128}" \
      --run-verilator

  local cpp_bin
  cpp_bin="$(
    "${PYTHON_BIN}" - "${out_dir}/project_manifest.json" <<'PY'
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
