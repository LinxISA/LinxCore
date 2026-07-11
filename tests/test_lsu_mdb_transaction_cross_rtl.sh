#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHISEL_TB="${ROOT_DIR}/tools/chisel/scalar_lsu_mdb_path_probe_tb.cpp"
PYC_TB="${ROOT_DIR}/tests/pyc/tb_lsu_mdb_transaction.py"

scenario_set() {
  sed -n 's/.*CROSS_RTL_SCENARIO: \([a-z0-9-]*\).*/\1/p' "$1" | sort -u
}

chisel_scenarios="$(scenario_set "${CHISEL_TB}")"
pyc_scenarios="$(scenario_set "${PYC_TB}")"
if [[ -z "${chisel_scenarios}" || "${chisel_scenarios}" != "${pyc_scenarios}" ]]; then
  echo "error: MDB transaction scenarios differ across RTL lanes" >&2
  diff -u <(printf '%s\n' "${chisel_scenarios}") <(printf '%s\n' "${pyc_scenarios}") || true
  exit 1
fi

bash "${ROOT_DIR}/tools/chisel/run_chisel_scalar_lsu_mdb_path_probe.sh"
bash "${ROOT_DIR}/tests/test_lsu_mdb_transaction_pyc_flow.sh"

echo "ok: Chisel and pyCircuit MDB transaction scenario sets passed"
