#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PYC_ROOT="${PYC_ROOT:-$(git -C "${ROOT_DIR}" rev-parse --show-superproject-working-tree)/tools/pyCircuit}"
OUT_DIR="${ROOT_DIR}/out/pyc/ooo_recovery_producer"

if [[ ! -f "${PYC_ROOT}/flows/scripts/lib.sh" ]]; then
  echo "error: cannot locate pyCircuit at ${PYC_ROOT}" >&2
  exit 2
fi

# shellcheck disable=SC1090
source "${PYC_ROOT}/flows/scripts/lib.sh"
pyc_find_pycc

PYTHON_BIN="${PYC_PYTHON_BIN:-}"
if [[ -z "${PYTHON_BIN}" ]]; then
  for candidate in /opt/homebrew/bin/python3.14 /opt/homebrew/bin/python3 python3.14 python3; do
    if command -v "${candidate}" >/dev/null 2>&1 && "${candidate}" -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
      PYTHON_BIN="$(command -v "${candidate}")"
      break
    fi
  done
fi
if [[ -z "${PYTHON_BIN}" ]]; then
  echo "error: Python 3.10 or newer is required" >&2
  exit 2
fi

rm -rf "${OUT_DIR}"
PYTHONPATH="$(pyc_pythonpath):${ROOT_DIR}/src" PYTHONDONTWRITEBYTECODE=1 PYCC="${PYCC}" \
  "${PYTHON_BIN}" -m pycircuit.cli build \
    "${ROOT_DIR}/tests/pyc/tb_ooo_recovery_producer.py" \
    --out-dir "${OUT_DIR}" \
    --target both \
    --jobs "${PYC_SIM_JOBS:-4}" \
    --logic-depth 128 \
    --run-verilator

if grep -R -Ei '\b(arm|aarch|cortex)\b' "${ROOT_DIR}/src/bcc/ooo/recovery_producer.py" "${ROOT_DIR}/tests/pyc/tb_ooo_recovery_producer.py" >/dev/null; then
  echo "error: recovery producer flow must not encode ARM-specific semantics" >&2
  exit 1
fi

echo "ok: pyCircuit OOO recovery producer flow passed"
