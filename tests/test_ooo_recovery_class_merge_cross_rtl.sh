#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHISEL_TB="${ROOT_DIR}/tools/chisel/recovery_class_merge_probe_tb.cpp"
PYC_TB="${ROOT_DIR}/tests/pyc/tb_ooo_recovery_class_merge.py"

scenario_set() {
  sed -n 's/.*CROSS_RTL_SCENARIO: \([a-z0-9-]*\).*/\1/p' "$1" | sort -u
}

chisel_scenarios="$(scenario_set "${CHISEL_TB}")"
pyc_scenarios="$(scenario_set "${PYC_TB}")"
if [[ -z "${chisel_scenarios}" || "${chisel_scenarios}" != "${pyc_scenarios}" ]]; then
  echo "error: recovery-class scenario declarations differ across RTL lanes" >&2
  diff -u <(printf '%s\n' "${chisel_scenarios}") <(printf '%s\n' "${pyc_scenarios}") || true
  exit 1
fi

bash "${ROOT_DIR}/tools/chisel/run_chisel_recovery_class_merge_probe.sh"
bash "${ROOT_DIR}/tests/test_ooo_recovery_class_merge_pyc_flow.sh"

echo "ok: Chisel and pyCircuit recovery-class scenario sets passed"
