#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "${1:-}" == "--check-dependencies" ]]; then
  exec python3 "${ROOT_DIR}/tools/chisel/check_crosscheck_wrapper_dependencies.py"
fi

cat >&2 <<'MESSAGE'
error: the reduced frontend-fetch-RF-ALU generated-RTL contract was removed and
is not equivalent to the current production frontend trace top. Migrate bounded
cross-checks to:
  bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
Use --check-dependencies for a non-RTL source-contract check.
MESSAGE
exit 2
