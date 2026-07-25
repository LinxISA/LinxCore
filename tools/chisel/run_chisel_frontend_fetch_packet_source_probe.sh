#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

TARGET_DIR="${ROOT_DIR}/generated/chisel-verilog/frontend-fetch-packet-source-probe"
BUILD_DIR="${ROOT_DIR}/generated/chisel-verilator/frontend-fetch-packet-source-probe"
rm -rf "${TARGET_DIR}" "${BUILD_DIR}"
mkdir -p "${TARGET_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain linxcore.frontend.ElaborateFrontendFetchPacketSourceProbe --target-dir ${TARGET_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${TARGET_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc --exe --build --top-module FrontendFetchPacketSourceProbe \
  -Mdir "${BUILD_DIR}" \
  -CFLAGS "-std=c++17 -O2" \
  "${sv_sources[@]}" \
  "${ROOT_DIR}/tools/chisel/frontend_fetch_packet_source_probe_tb.cpp"

"${BUILD_DIR}/VFrontendFetchPacketSourceProbe"
