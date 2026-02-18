#!/usr/bin/env bash
set -euo pipefail

# One-shot trace regression runner for LinxCore.
# Generates commit trace (jsonl + txt) for a given memh suite.
#
# Usage:
#   tools/trace/run_linx_trace_regression.sh <suite.memh> <out_dir>

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SUITE_MEMH="${1:-}"
OUT_DIR="${2:-}"

if [[ -z "${SUITE_MEMH}" || -z "${OUT_DIR}" ]]; then
  echo "usage: $(basename "$0") <suite.memh> <out_dir>" >&2
  exit 2
fi
if [[ ! -f "${SUITE_MEMH}" ]]; then
  echo "error: suite not found: ${SUITE_MEMH}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
TRACE_JSONL="${OUT_DIR}/commit_trace.jsonl"
TRACE_TXT="${OUT_DIR}/commit_trace.txt"

# Force a clean trace.
rm -f "${TRACE_JSONL}" "${TRACE_TXT}" >/dev/null 2>&1 || true

export PYC_BUILD_JOBS="${PYC_BUILD_JOBS:-12}"
export PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2}"
export PYC_COMMIT_TRACE="${TRACE_JSONL}"
export PYC_COMMIT_TRACE_TEXT="${TRACE_TXT}"

bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${SUITE_MEMH}" >/dev/null

if [[ ! -s "${TRACE_JSONL}" ]]; then
  echo "error: missing/empty trace jsonl: ${TRACE_JSONL}" >&2
  exit 3
fi
if [[ ! -s "${TRACE_TXT}" ]]; then
  echo "error: missing/empty trace txt: ${TRACE_TXT}" >&2
  exit 3
fi

echo "trace_jsonl=${TRACE_JSONL}"
echo "trace_txt=${TRACE_TXT}"
