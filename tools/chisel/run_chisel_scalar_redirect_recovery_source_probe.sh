#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

SV_DIR="${ROOT_DIR}/generated/chisel-verilog/scalar-redirect-recovery-source-probe"
BUILD_DIR="${ROOT_DIR}/generated/scalar-redirect-recovery-source-probe"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    'runMain linxcore.recovery.EmitScalarRedirectRecoverySourceProbe'
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

verilator --cc "${sv_sources[@]}" \
  --top-module ScalarRedirectRecoverySourceProbe \
  --exe "${ROOT_DIR}/tools/chisel/scalar_redirect_recovery_source_probe_tb.cpp" \
  --build --build-jobs 0 \
  -Mdir "${BUILD_DIR}/obj_dir" \
  -o scalar_redirect_recovery_source_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/scalar_redirect_recovery_source_probe_tb"
