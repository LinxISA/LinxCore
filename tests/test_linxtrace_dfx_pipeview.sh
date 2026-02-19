#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
MAX_COMMITS="${PYC_LINXTRACE_MAX_COMMITS:-300}"
OUT="${ROOT_DIR}/generated/linxtrace/branch_${MAX_COMMITS}insn.linxtrace"

if [[ ! -f "${MEMH}" ]]; then
  echo "missing memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-12000}" \
bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${MEMH}" "${MAX_COMMITS}" >/dev/null

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" "${OUT}" \
  --require-stages F1,F2,F3,F4,IB,D1,D2,D3,S1,IQ,CMT,FLS

echo "ok: dfx linxtrace smoke ${OUT}"
