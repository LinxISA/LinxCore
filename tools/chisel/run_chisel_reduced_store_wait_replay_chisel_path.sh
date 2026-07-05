#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${ROOT_DIR}/generated/chisel-verilog/reduced-store-wait-replay-chisel-path"
SV_FILE="${SV_DIR}/ReducedStoreWaitReplayChiselPathProbe.sv"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-reduced-store-wait-replay-chisel-path}"
OBJ_DIR="${BUILD_DIR}/obj_dir"
REPORT_DIR="${BUILD_DIR}/report"
REPORT_JSON="${REPORT_DIR}/reduced_store_wait_replay_chisel_path.json"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

mkdir -p "${REPORT_DIR}"

cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.lsu.EmitReducedStoreWaitReplayChiselPathProbe"

if [[ ! -f "${SV_FILE}" ]]; then
  echo "error: reduced store wait/replay probe SystemVerilog was not emitted: ${SV_FILE}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)

if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no reduced store wait/replay SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module ReducedStoreWaitReplayChiselPathProbe \
  --exe "${ROOT_DIR}/tools/chisel/reduced_store_wait_replay_chisel_path_tb.cpp" \
  --build \
  -Mdir "${OBJ_DIR}" \
  -o reduced_store_wait_replay_chisel_path_tb \
  -CFLAGS "-std=c++17 -O2"

"${OBJ_DIR}/reduced_store_wait_replay_chisel_path_tb" \
  --report-json "${REPORT_JSON}"

echo "reduced-store-wait-replay-chisel-path-report=${REPORT_JSON}"
