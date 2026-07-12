#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

bash "${ROOT_DIR}/tests/test_lsu_scb_pyc_flow.sh"

for suite in \
  LoadStoreForwardingSpec \
  LoadInflightQueueSpec \
  LoadReplayWakeupSpec \
  LoadRefillWakeupSpec \
  LoadReplayReturnDataExtractSpec \
  LoadMissQueueSpec \
  LoadRefillTransportSpec \
  STQEntryBankSpec \
  SCBRowBankSpec \
  MDBSSITSpec \
  MDBConflictDetectSpec; do
  bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only "${suite}"
done

bash "${ROOT_DIR}/tools/chisel/run_chisel_load_miss_queue_probe.sh"
bash "${ROOT_DIR}/tools/chisel/run_chisel_load_refill_transport_probe.sh"
bash "${ROOT_DIR}/tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh"

echo "ok: focused LSU promotion gate passed"
