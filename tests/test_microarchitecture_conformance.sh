#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${ROOT_DIR}/tools/architecture/check_conformance.py"
REPORT_A="$(mktemp)"
REPORT_B="$(mktemp)"

cleanup() {
  rm -f "${REPORT_A}" "${REPORT_B}"
}
trap cleanup EXIT

python3 "${CHECKER}" --root "${ROOT_DIR}" --self-test --out "${REPORT_A}"
python3 "${CHECKER}" --root "${ROOT_DIR}" --self-test --out "${REPORT_B}"
cmp "${REPORT_A}" "${REPORT_B}"

echo "ok: shared microarchitecture conformance foundation passed"
