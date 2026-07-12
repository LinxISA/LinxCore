#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

OUT_DIR="${ROOT_DIR}/generated/chisel-verilog/load-refill-transport-probe"
OBJ_DIR="${OUT_DIR}/obj_dir"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cd "${ROOT_DIR}/chisel"
sbt --server --batch --no-colors \
  "runMain linxcore.lsu.EmitLoadRefillTransportProbe --target-dir ${OUT_DIR}"

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${OUT_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" --top-module LoadRefillTransportProbe \
  --exe "${ROOT_DIR}/tools/chisel/load_refill_transport_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${OBJ_DIR}" \
  -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/VLoadRefillTransportProbe"
