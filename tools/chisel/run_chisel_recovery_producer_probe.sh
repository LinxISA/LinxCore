#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/recovery-producer-probe"
BUILD_DIR="${ROOT_DIR}/generated/recovery-producer-probe"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 'runMain linxcore.recovery.EmitRecoveryProducerProbe'
)

mapfile_cmd=(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print)
if command -v mapfile >/dev/null 2>&1; then
  mapfile -t sv_sources < <("${mapfile_cmd[@]}" | sort)
else
  sv_sources=()
  while IFS= read -r source; do
    sv_sources+=("${source}")
  done < <("${mapfile_cmd[@]}" | sort)
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module RecoveryProducerProbe \
  --exe "${ROOT_DIR}/tools/chisel/recovery_producer_probe_tb.cpp" \
  --Mdir "${BUILD_DIR}/obj_dir" \
  --build --build-jobs 0 \
  -o recovery_producer_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/recovery_producer_probe_tb"
