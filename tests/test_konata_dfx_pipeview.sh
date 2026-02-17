#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
MAX_COMMITS="${PYC_LINXTRACE_MAX_COMMITS:-300}"
OUT="${ROOT_DIR}/generated/linxtrace/branch_${MAX_COMMITS}insn.linxtrace.jsonl"
META="${ROOT_DIR}/generated/linxtrace/branch_${MAX_COMMITS}insn.linxtrace.meta.json"

if [[ ! -f "${MEMH}" ]]; then
  echo "missing memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-12000}" \
bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${MEMH}" "${MAX_COMMITS}" >/dev/null

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" "${OUT}" --meta "${META}" \
  --require-stages F0,F1,F2,F3,IB,D1,D3,IQ,CMT,FLS

echo "ok: dfx linxtrace smoke ${OUT}"
