#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-release}"
case "${BUILD_PROFILE}" in
  dev-fast|release) ;;
  *)
    echo "error: unsupported LINXCORE_BUILD_PROFILE=${BUILD_PROFILE} (expected dev-fast|release)" >&2
    exit 2
    ;;
esac

MAX_COMMITS="${LINXCORE_BENCH_MAX_COMMITS:-1000}"
MAX_DUT_CYCLES="${LINXCORE_BENCH_MAX_DUT_CYCLES:-200000000}"
TRACE_MAX_COMMITS="${LINXCORE_BENCH_TRACE_MAX_COMMITS:-${MAX_COMMITS}}"

if [[ -n "${PYC_TB_CXXFLAGS:-}" ]]; then
  TB_CXXFLAGS="${PYC_TB_CXXFLAGS}"
elif [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  TB_CXXFLAGS="-O1 -DNDEBUG -g0"
else
  TB_CXXFLAGS="-O3 -DNDEBUG -g0"
fi

LINXCORE_BUILD_PROFILE="${BUILD_PROFILE}" \
PYC_TB_CXXFLAGS="${TB_CXXFLAGS}" \
PYC_LINXTRACE_MAX_COMMITS="${TRACE_MAX_COMMITS}" \
  bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace_benchmarks.sh" "${TRACE_MAX_COMMITS}" >/dev/null

python3 - <<'PY' "${ROOT_DIR}" "${TRACE_MAX_COMMITS}"
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
want = sys.argv[2]
for bench in ("coremark", "dhrystone"):
    base = root / "generated" / "linxtrace" / bench
    if not base.is_dir():
        raise SystemExit(f"missing trace dir: {base}")
    cand = sorted(base.glob(f"*_{want}insn.linxtrace"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not cand:
        raise SystemExit(f"missing {bench} linxtrace output for {want} commits in {base}")
    trace_path = cand[0]
    commit_cand = sorted(base.glob(f"*_{want}insn.commit.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not commit_cand:
        raise SystemExit(f"missing {bench} commit trace for {want} commits in {base}")
print("benchmark trace artifacts: ok")
PY

for bench in coremark dhrystone; do
  commit_path="$(
    python3 - <<'PY' "${ROOT_DIR}" "${bench}" "${TRACE_MAX_COMMITS}"
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
bench = sys.argv[2]
want = sys.argv[3]
base = root / "generated" / "linxtrace" / bench
cand = sorted(base.glob(f"*_{want}insn.commit.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
if not cand:
    raise SystemExit(f"missing {bench} commit trace for {want} commits in {base}")
print(cand[0])
PY
  )"
  python3 "${ROOT_DIR}/tools/trace/check_benchmark_commit_window.py" \
    --trace "${commit_path}" \
    --want-commits "${TRACE_MAX_COMMITS}" \
    --label "${bench}" >/dev/null
done

LINXCORE_BUILD_PROFILE="${BUILD_PROFILE}" \
PYC_TB_CXXFLAGS="${TB_CXXFLAGS}" \
LINXCORE_COSIM_MAX_COMMITS="${MAX_COMMITS}" \
LINXCORE_COSIM_MAX_DUT_CYCLES="${MAX_DUT_CYCLES}" \
  bash "${ROOT_DIR}/tools/qemu/run_cosim_benchmarks.sh" >/dev/null

for bench in coremark dhrystone; do
  log_path="${ROOT_DIR}/generated/cosim/${bench}.log"
  if [[ ! -s "${log_path}" ]]; then
    echo "error: missing cosim log: ${log_path}" >&2
    exit 1
  fi
done

echo "benchmark trace + cosim gate: ok (coremark + dhrystone)"
