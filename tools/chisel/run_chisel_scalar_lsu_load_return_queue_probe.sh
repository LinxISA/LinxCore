#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

SV_DIR="${ROOT_DIR}/generated/chisel-verilog/scalar-lsu-load-return-queue-probe"
BUILD_DIR="${ROOT_DIR}/generated/scalar-lsu-load-return-queue-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "runMain linxcore.lsu.EmitScalarLSULoadReturnQueueProbe --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" \
  --top-module ScalarLSULoadReturnQueueProbe \
  --exe "${ROOT_DIR}/tools/chisel/scalar_lsu_load_return_queue_probe_tb.cpp" \
  --build --build-jobs 0 \
  -Mdir "${BUILD_DIR}/obj_dir" \
  -o scalar_lsu_load_return_queue_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/scalar_lsu_load_return_queue_probe_tb"
