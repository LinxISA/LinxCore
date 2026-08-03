#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

OUT_DIR="${ROOT_DIR}/generated/chisel-verilog/ooo-iex-lsu-activation"
BUILD_DIR="${OUT_DIR}/verilator"
trash "${OUT_DIR}" 2>/dev/null || true
mkdir -p "${OUT_DIR}"

cd "${ROOT_DIR}/chisel"
JAVA_OPTS="${JAVA_OPTS:-} -Xmx${LINX_CHISEL_SBT_MEM_MB}M -Xss8M" \
  sbt --server --batch --no-colors \
  "runMain linxcore.iex.EmitOOOIEXLSUActivationProbe --target-dir ${OUT_DIR}"

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${OUT_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "OOO-IEX-LSU activation probe emitted no SystemVerilog" >&2
  exit 1
fi

verilator --cc --exe --build --assert --timing -j 0 \
  --top-module OOOIEXLSUActivationProbe \
  --Mdir "${BUILD_DIR}" \
  -CFLAGS '-std=c++17' \
  "${sv_sources[@]}" \
  "${ROOT_DIR}/tools/chisel/ooo_iex_lsu_activation_tb.cpp"
"${BUILD_DIR}/VOOOIEXLSUActivationProbe"
