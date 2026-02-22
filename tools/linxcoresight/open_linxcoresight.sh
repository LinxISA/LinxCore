#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

TRACE="${1:-}"
if [[ -z "${TRACE}" ]]; then
  TRACE="$(find "${ROOT_DIR}/generated/linxtrace" -name '*.linxtrace.jsonl' -type f -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "${TRACE}" ]]; then
  echo "error: no LinxTrace found. Generate one first via:" >&2
  echo "  bash ${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh <program.memh> [max_commits]" >&2
  exit 2
fi
if [[ ! -f "${TRACE}" ]]; then
  echo "error: trace not found: ${TRACE}" >&2
  exit 2
fi

meta="${TRACE%.linxtrace.jsonl}.linxtrace.meta.json"
if [[ ! -f "${meta}" ]]; then
  echo "error: missing sidecar meta: ${meta}" >&2
  exit 2
fi

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" "${TRACE}" --meta "${meta}" >/dev/null

APP="${LINXCORESIGHT_APP:-}"
if [[ -z "${APP}" ]]; then
  for cand in \
    "/Users/zhoubot/LinxCoreSight/release/mac-arm64/LinxCoreSight.app" \
    "/Applications/LinxCoreSight.app" \
    "${HOME}/Applications/LinxCoreSight.app"
  do
    if [[ -d "${cand}" ]]; then
      APP="${cand}"
      break
    fi
  done
fi

if [[ -z "${APP}" || ! -d "${APP}" ]]; then
  echo "error: LinxCoreSight app not found. Build/install it first." >&2
  exit 3
fi

echo "opening LinxCoreSight with trace: ${TRACE}"
open -a "${APP}" "${TRACE}"

