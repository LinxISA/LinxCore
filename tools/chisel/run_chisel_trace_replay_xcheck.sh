#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPENDENCY_CHECKER="${ROOT_DIR}/tools/chisel/check_crosscheck_wrapper_dependencies.py"

if [[ "${1:-}" == "--check-dependencies" ]]; then
  exec python3 "${DEPENDENCY_CHECKER}"
fi

cat >&2 <<'MESSAGE'
error: arbitrary commit-trace replay is not supported by the current Chisel
cross-check contract. The deleted full-core replay top is not a valid fallback.
Migrate bounded generated-RTL checks to:
  bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
Use --check-dependencies for a non-RTL source-contract check.
MESSAGE
exit 2
