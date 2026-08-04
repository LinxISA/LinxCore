#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
FIRTOOL_DIR="${LINX_TASK15_FIRTOOL_DIR:-/tmp/linxcore-firtool-arm64-1.154.0/firtool-1.154.0/bin}"
LOG="$(mktemp "${TMPDIR:-/tmp}/linxcore-task15-warning-matrix.XXXXXX.log")"
trap 'rm -f "${LOG}"' EXIT

if [[ ! -x "${FIRTOOL_DIR}/firtool" ]]; then
  echo "error: native arm64 firtool is missing: ${FIRTOOL_DIR}/firtool" >&2
  exit 2
fi

PATH="${FIRTOOL_DIR}:${PATH}" \
LINX_CHISEL_TEST_JOBS=1 \
LINX_CHISEL_ARTIFACT_BUDGET_BYTES=1073741824 \
bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" \
  --jobs 1 \
  --wall-seconds 420 \
  --artifact-budget-bytes 1073741824 \
  --only Task15WarningMatrixSpec 2>&1 | tee "${LOG}"

python3 "${ROOT_DIR}/tools/chisel/classify_task15_warning_matrix.py" "${LOG}"
