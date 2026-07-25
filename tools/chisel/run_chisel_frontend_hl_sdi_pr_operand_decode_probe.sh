#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

SV_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-hl-sdi-pr-operand-decode-probe"
BUILD_DIR="${ROOT_DIR}/generated/chisel-verilator/frontend-hl-sdi-pr-operand-decode-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain linxcore.frontend.EmitFrontendHlSdiPrOperandDecodeProbe --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no frontend HL.SDI.PR operand decode SystemVerilog emitted under ${SV_DIR}" >&2
  exit 2
fi

verilator --cc "${sv_sources[@]}" --top-module FrontendHlSdiPrOperandDecodeProbe \
  --exe "${ROOT_DIR}/tools/chisel/frontend_hl_sdi_pr_operand_decode_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${BUILD_DIR}/obj_dir" \
  -o frontend_hl_sdi_pr_operand_decode_probe_tb -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/frontend_hl_sdi_pr_operand_decode_probe_tb"
