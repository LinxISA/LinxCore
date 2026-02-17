#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <trace.linxtrace.jsonl> [top_rows]" >&2
  exit 2
fi
TRACE="$1"
TOP="${2:-12}"
python3 "${ROOT_DIR}/tools/linxcoresight/linxtrace_cli_debug.py" "${TRACE}" --top "${TOP}"

