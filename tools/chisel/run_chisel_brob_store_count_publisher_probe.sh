#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/brob-store-count-publisher-probe"
BUILD_DIR="${ROOT_DIR}/generated/brob-store-count-publisher-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${BUILD_DIR}"

(cd "${ROOT_DIR}/chisel" && sbt --server --batch --no-colors --mem 4096 \
  "runMain linxcore.bctrl.EmitBrobStoreCountPublisherProbe --target-dir ${SV_DIR}")

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)
verilator --cc "${sv_sources[@]}" --top-module BrobStoreCountPublisherProbe \
  --exe "${ROOT_DIR}/tools/chisel/brob_store_count_publisher_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${BUILD_DIR}/obj_dir" \
  -o brob_store_count_publisher_probe_tb -CFLAGS '-std=c++17 -O2'
"${BUILD_DIR}/obj_dir/brob_store_count_publisher_probe_tb"
