#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CANONICAL_RUNNER="${ROOT_DIR}/tools/chisel/run_chisel_frontend_trace_top_xcheck.sh"

exec bash "${CANONICAL_RUNNER}" "$@"
