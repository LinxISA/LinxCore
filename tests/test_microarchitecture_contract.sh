#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${ROOT_DIR}/tools/architecture/check_microarchitecture_contract.py"
REPORT_A="$(mktemp)"
REPORT_B="$(mktemp)"

cleanup() {
  rm -f "${REPORT_A}" "${REPORT_B}"
}
trap cleanup EXIT

python3 "${CHECKER}" --self-test
python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --out "${REPORT_A}"
python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --out "${REPORT_B}"
cmp "${REPORT_A}" "${REPORT_B}"

python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --require-no-legacy
bash "${ROOT_DIR}/tests/test_pycircuit_architecture_adapter.sh"
bash "${ROOT_DIR}/tests/test_chisel_architecture_adapter.sh"
bash "${ROOT_DIR}/tests/test_microarchitecture_conformance.sh"

echo "ok: LinxCore microarchitecture contract gate passed"
