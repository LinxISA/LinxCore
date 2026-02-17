#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
OUT="${ROOT_DIR}/generated/konata/coremark/konata_dfx_test.konata"

if [[ ! -f "${MEMH}" ]]; then
  echo "missing memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

PYC_KONATA=1 \
PYC_KONATA_PATH="${OUT}" \
PYC_KONATA_SYNTHETIC=0 \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-12000}" \
bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" || true

python3 "${ROOT_DIR}/tools/konata/check_konata_stages.py" "${OUT}" \
  --require-stages F0,D3,IQ,P1,ROB,FLS

echo "ok: dfx konata smoke ${OUT}"
