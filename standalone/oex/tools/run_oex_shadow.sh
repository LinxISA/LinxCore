#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"

TB_BIN="${TB_BIN:-${ROOT_DIR}/standalone/OEX/generated/tb_oex_shadow}"
TRACE="${1:-}"

if [[ -z "${TRACE}" ]]; then
  echo "usage: $(basename "$0") <qemu_trace.jsonl> [-- extra args]" >&2
  exit 2
fi
shift || true

"${TB_BIN}" --trace "${TRACE}" "$@"

