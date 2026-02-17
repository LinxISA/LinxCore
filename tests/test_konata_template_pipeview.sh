#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_CPP_SCRIPT="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
CHECK_SCRIPT="${ROOT_DIR}/tools/konata/check_konata_stages.py"
MEMH="${ROOT_DIR}/tests/artifacts/suites/branch.memh"
OUT="${ROOT_DIR}/generated/konata/coremark/konata_dfx_template_test.konata"

if [[ ! -f "${MEMH}" ]]; then
  echo "error: missing template smoke memh: ${MEMH}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUT}")"

PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
PYC_KONATA=1 \
PYC_KONATA_SYNTHETIC=0 \
PYC_KONATA_SKIP_BSTOP_PREFIX=1 \
PYC_KONATA_PATH="${OUT}" \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-800}" \
  bash "${RUN_CPP_SCRIPT}" "${MEMH}" >/dev/null 2>&1 || true

python3 "${CHECK_SCRIPT}" "${OUT}" --require-stages F1,D3,ROB,FLS --require-template

echo "ok: template dfx konata smoke ${OUT}"
