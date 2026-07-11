#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

bash "${ROOT_DIR}/tests/test_backend_mapq_pyc_flow.sh"
bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only BIDRingOrderSpec

echo "ok: focused OOO promotion gate passed"
