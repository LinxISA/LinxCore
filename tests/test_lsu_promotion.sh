#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

bash "${ROOT_DIR}/tests/test_lsu_scb_pyc_flow.sh"

for suite in \
  LoadStoreForwardingSpec \
  LoadInflightQueueSpec \
  STQEntryBankSpec \
  SCBRowBankSpec \
  MDBSSITSpec \
  MDBConflictDetectSpec; do
  bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only "${suite}"
done

echo "ok: focused LSU promotion gate passed"
