#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${ROOT_DIR}/tools/architecture/check_microarchitecture_contract.py"
REPORT_A="$(mktemp)"
REPORT_B="$(mktemp)"
NO_LEGACY_LOG="$(mktemp)"

cleanup() {
  rm -f "${REPORT_A}" "${REPORT_B}" "${NO_LEGACY_LOG}"
}
trap cleanup EXIT

python3 "${CHECKER}" --self-test
python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --out "${REPORT_A}"
python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --out "${REPORT_B}"
cmp "${REPORT_A}" "${REPORT_B}"

if python3 "${CHECKER}" --root "${ROOT_DIR}" --strict --require-no-legacy >"${NO_LEGACY_LOG}" 2>&1; then
  echo "error: strict no-legacy gate unexpectedly passed" >&2
  exit 1
fi

if ! grep -q "legacy migration input remains" "${NO_LEGACY_LOG}"; then
  echo "error: strict no-legacy gate failed for an unexpected reason" >&2
  cat "${NO_LEGACY_LOG}" >&2
  exit 1
fi

echo "ok: LinxCore microarchitecture contract gate passed"
