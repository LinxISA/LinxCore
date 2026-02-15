#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <program.memh>" >&2
  exit 2
fi
MEMH="$1"
PYC_KONATA=1 bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}"
