#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

SV_DIR="${ROOT_DIR}/generated/chisel-verilog/lsu-reduced-store-sta-address-exec-sb-probe-r813"
BUILD_DIR="${ROOT_DIR}/generated/chisel-verilator/lsu-reduced-store-sta-address-exec-sb-probe-r813"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain linxcore.lsu.EmitReducedStoreStaAddressExecSbProbe --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" --top-module ReducedStoreStaAddressExecSbProbe \
  --exe "${ROOT_DIR}/tools/chisel/lsu_reduced_store_sta_address_exec_sb_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${BUILD_DIR}/obj_dir" \
  -o lsu_reduced_store_sta_address_exec_sb_probe_tb -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/lsu_reduced_store_sta_address_exec_sb_probe_tb"
