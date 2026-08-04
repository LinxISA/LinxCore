#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "${1:-}" == "--check-dependencies" ]]; then
  exec python3 "${ROOT_DIR}/tools/chisel/check_crosscheck_wrapper_dependencies.py"
fi

cat >&2 <<'MESSAGE'
error: the reduced frontend-fetch-RF-ALU fixture builder belongs to a deleted
test-only top and is no longer supported. Do not regenerate that obsolete ELF.
Use --check-dependencies for a non-RTL source-contract check.
MESSAGE
exit 2
