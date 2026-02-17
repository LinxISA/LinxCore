#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <trace.konata> [--top N] [--json]" >&2
  exit 2
fi

node "${ROOT_DIR}/tools/konata/konata_internal_diag.js" "$@"
