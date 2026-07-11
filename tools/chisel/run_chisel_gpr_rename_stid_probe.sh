#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/gpr-rename-stid-probe"
BUILD_DIR="${ROOT_DIR}/generated/gpr-rename-stid-probe"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
rm -rf "${SV_DIR}" "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 'runMain linxcore.rename.EmitGPRRenameStidProbe'
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
  --top-module GPRRenameStidProbe \
  --exe "${ROOT_DIR}/tools/chisel/gpr_rename_stid_probe_tb.cpp" \
  --Mdir "${BUILD_DIR}/obj_dir" \
  --build --build-jobs 0 \
  -o gpr_rename_stid_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${BUILD_DIR}/obj_dir/gpr_rename_stid_probe_tb"
