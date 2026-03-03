#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

python3 /Users/zhoubot/pyCircuit/flows/tools/check_api_hygiene.py \
  --scan-root "${ROOT_DIR}" \
  "${ROOT_DIR}/src"

python3 "${ROOT_DIR}/tools/lint/check_linxcore_pyc4_frontend_strict.py" \
  --scan-root "${ROOT_DIR}/src" \
  --baseline "${ROOT_DIR}/tools/lint/pyc4_frontend_baseline.json" \
  --strict

