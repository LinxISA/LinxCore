#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

BUILD_ARG="generated/chisel-verilator/lsu-reduced-store-sta-address-exec-hl-sdi-pr-probe-r857"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      BUILD_ARG="$2"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${BUILD_ARG}" = /* ]]; then
  BUILD_DIR="${BUILD_ARG}"
else
  BUILD_DIR="${ROOT_DIR}/${BUILD_ARG}"
fi
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"

rm -rf "${BUILD_DIR}"
mkdir -p "${SV_DIR}" "${OBJ_DIR}"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain linxcore.lsu.EmitReducedStoreStaAddressExecHlSdiPrProbe --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)

verilator --cc "${sv_sources[@]}" --top-module ReducedStoreStaAddressExecHlSdiPrProbe \
  --exe "${ROOT_DIR}/tools/chisel/lsu_reduced_store_sta_address_exec_hl_sdi_pr_probe_tb.cpp" \
  --build --build-jobs 0 -Mdir "${OBJ_DIR}" \
  -o lsu_reduced_store_sta_address_exec_hl_sdi_pr_probe_tb -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/lsu_reduced_store_sta_address_exec_hl_sdi_pr_probe_tb"
