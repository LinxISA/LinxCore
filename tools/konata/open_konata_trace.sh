#!/usr/bin/env bash
set -euo pipefail

TRACE="${1:-}"
if [[ -z "${TRACE}" ]]; then
  TRACE="/Users/zhoubot/LinxCore/generated/cpp/linxcore_top/tb_linxcore_top_cpp_program.konata"
fi
if [[ ! -f "${TRACE}" ]]; then
  echo "error: missing trace file: ${TRACE}" >&2
  exit 1
fi
if command -v open >/dev/null 2>&1; then
  open "${TRACE}"
else
  echo "trace ready: ${TRACE}"
fi
