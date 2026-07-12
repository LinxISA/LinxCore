#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/load-miss-queue-probe"
BUILD_DIR="${ROOT_DIR}/generated/load-miss-queue-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${BUILD_DIR}"

(cd "${ROOT_DIR}/chisel" && sbt --server --batch --no-colors --mem 4096 \
  "runMain linxcore.lsu.EmitLoadMissQueueProbe --target-dir ${SV_DIR}")

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" --top-module LoadMissQueueProbe \
  --exe "${ROOT_DIR}/tools/chisel/load_miss_queue_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${BUILD_DIR}/obj_dir" \
  -o load_miss_queue_probe_tb -CFLAGS '-std=c++17 -O2'
"${BUILD_DIR}/obj_dir/load_miss_queue_probe_tb"
