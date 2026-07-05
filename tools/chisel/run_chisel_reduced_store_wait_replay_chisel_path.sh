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
REDUCED_STORE_WAIT_REPLAY_REQUIRE_TRUE="${REDUCED_STORE_WAIT_REPLAY_REQUIRE_TRUE:-mdb_lookup_wait_plan_scb_evidence,mdb_lookup_wait_plan_write,mdb_lookup_wait_plan_apply,mdb_lookup_wait_plan_wait_status_after_write}"
REDUCED_STORE_WAIT_REPLAY_REQUIRE_NONZERO="${REDUCED_STORE_WAIT_REPLAY_REQUIRE_NONZERO:-liq_replay_wake_completed_mask,liq_scb_returned_mask_before_mdb_write,liq_sources_returned_mask_before_mdb_write,liq_wait_mask_after_mdb_write,liq_wait_store_mask_after_mdb_write}"

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

REPORT_VALIDATOR_ARGS=("${REPORT_JSON}")
if [[ -n "${REDUCED_STORE_WAIT_REPLAY_REQUIRE_TRUE}" ]]; then
  IFS=',' read -r -a reduced_store_required_true <<< "${REDUCED_STORE_WAIT_REPLAY_REQUIRE_TRUE}"
  for reduced_store_report_key in "${reduced_store_required_true[@]}"; do
    if [[ -n "${reduced_store_report_key}" ]]; then
      REPORT_VALIDATOR_ARGS+=(--require-true "${reduced_store_report_key}")
    fi
  done
fi
if [[ -n "${REDUCED_STORE_WAIT_REPLAY_REQUIRE_NONZERO}" ]]; then
  IFS=',' read -r -a reduced_store_required_nonzero <<< "${REDUCED_STORE_WAIT_REPLAY_REQUIRE_NONZERO}"
  for reduced_store_report_key in "${reduced_store_required_nonzero[@]}"; do
    if [[ -n "${reduced_store_report_key}" ]]; then
      REPORT_VALIDATOR_ARGS+=(--require-nonzero "${reduced_store_report_key}")
    fi
  done
fi
python3 "${ROOT_DIR}/tools/chisel/validate_reduced_store_wait_replay_chisel_path.py" \
  "${REPORT_VALIDATOR_ARGS[@]}"

echo "reduced-store-wait-replay-chisel-path-report=${REPORT_JSON}"
