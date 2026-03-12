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

python3 "${PYC_ROOT_DIR}/flows/tools/check_api_hygiene.py" \
  --scan-root "${ROOT_DIR}" \
  "${ROOT_DIR}/src"

python3 "${ROOT_DIR}/tools/lint/check_linxcore_pyc4_frontend_strict.py" \
  --scan-root "${ROOT_DIR}/src" \
  --baseline "${ROOT_DIR}/tools/lint/pyc4_frontend_baseline.json" \
  --strict
