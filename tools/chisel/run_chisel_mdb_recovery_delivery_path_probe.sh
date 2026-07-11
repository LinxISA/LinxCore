#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

SV_DIR="${ROOT_DIR}/generated/chisel-verilog/mdb-recovery-delivery-path-probe"
BUILD_DIR="${ROOT_DIR}/generated/mdb-recovery-delivery-path-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    'runMain linxcore.lsu.EmitMDBRecoveryDeliveryPathProbe'
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" \
  --top-module MDBRecoveryDeliveryPathProbe \
  --exe "${ROOT_DIR}/tools/chisel/mdb_recovery_delivery_path_probe_tb.cpp" \
  --build --build-jobs 0 \
  -Mdir "${BUILD_DIR}/obj_dir" \
  -o mdb_recovery_delivery_path_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/mdb_recovery_delivery_path_probe_tb"
