#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -e "${ROOT_DIR}/src/top/modules/dfx_pipeview.py" ]]; then
  echo "error: deprecated trace-only state module still exists: src/top/modules/dfx_pipeview.py" >&2
  exit 1
fi
if [[ -e "${ROOT_DIR}/src/top/modules/stage_probe.py" ]]; then
  echo "error: deprecated trace-only state module still exists: src/top/modules/stage_probe.py" >&2
  exit 1
fi

if rg -n "m\\.out\\(" \
  "${ROOT_DIR}/src/top/modules/trace_event.py" \
  "${ROOT_DIR}/src/bcc/backend/modules/trace_occ.py" \
  >/dev/null; then
  echo "error: probe-only trace modules must not allocate state with m.out()" >&2
  exit 1
fi

if rg -n "trace_commit_seq|trace_prev_commit|dbg__blk_evt_" \
  "${ROOT_DIR}/src/top/modules/export_core.py" \
  >/dev/null; then
  echo "error: top export still contains trace-only sidecar state" >&2
  exit 1
fi

debug_occ_sites="$(rg -l "m\\.debug_occ\\(" "${ROOT_DIR}/src" || true)"
want_sites="${ROOT_DIR}/src/top/modules/trace_event.py
${ROOT_DIR}/src/bcc/backend/modules/trace_occ.py"
if [[ "${debug_occ_sites}" != "${want_sites}" ]]; then
  echo "error: debug_occ must remain isolated to probe-only modules" >&2
  printf 'debug_occ_sites:\n%s\n' "${debug_occ_sites}" >&2
  exit 1
fi

if rg -n "issue_pipe" \
  "${ROOT_DIR}/src/bcc/backend/modules/trace_export_core.py" \
  >/dev/null; then
  echo "error: backend trace path still depends on issue_pipe sidecar hardware" >&2
  exit 1
fi

if rg -n "PYC_LINXTRACE=1|PYC_LINXTRACE_PATH" \
  "${ROOT_DIR}/tests/test_coremark_crosscheck_1000.sh" \
  "${ROOT_DIR}/tools/image/run_linxcore_benchmarks.sh" \
  >/dev/null; then
  echo "error: canonical test/benchmark flows must build linxtrace from raw probe events" >&2
  exit 1
fi

echo "trace probe purity: ok"
