#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINXISA_ROOT="${LINXISA_ROOT:-/Users/zhoubot/linx-isa}"
PYC_ROOT="${LINXCORE_PYC_ROOT:-/Users/zhoubot/pyCircuit}"
PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}"
OUT_DIR="${PYC_PERF_OUT_DIR:-${ROOT_DIR}/generated/perf}"
OUT_JSON="${PYC_PERF_OUT_JSON:-${OUT_DIR}/pyc_compile_pipeline_${PROFILE}.json}"
RUN_CPP_BUILD="${PYC_PERF_RUN_CPP_BUILD:-0}"
KEEP_TMP_ON_FAIL="${PYC_PERF_KEEP_TMP_ON_FAIL:-1}"
if [[ "${PROFILE}" == "dev-fast" ]]; then
  LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-2048}"
else
  LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-2048}"
fi
TMP_DIR="$(mktemp -d -t linxcore_pyc_perf.XXXXXX)"

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  if [[ "${rc}" -ne 0 && "${KEEP_TMP_ON_FAIL}" == "1" ]]; then
    echo "warn: kept tmp dir for debugging: ${TMP_DIR}" >&2
  else
    rm -rf "${TMP_DIR}"
  fi
  exit "${rc}"
}
trap cleanup EXIT INT TERM

mkdir -p "${OUT_DIR}"

if [[ ! -x "${PYC_ROOT}/build-top/bin/pycc" && ! -x "${PYC_ROOT}/compiler/mlir/build2/bin/pycc" ]]; then
  echo "error: pycc not found under PYC_ROOT=${PYC_ROOT}" >&2
  exit 1
fi

find_pycc() {
  for cand in \
    "${PYC_ROOT}/build-top/bin/pycc" \
    "${PYC_ROOT}/build/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build2/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build/bin/pycc"
  do
    if [[ -x "${cand}" ]]; then
      printf '%s\n' "${cand}"
      return 0
    fi
  done
  return 1
}

PYCC="$(find_pycc)"
PYC_PYTHON_DIR="${PYC_ROOT}/python"
if [[ ! -d "${PYC_PYTHON_DIR}/pycircuit" && -d "${PYC_ROOT}/compiler/frontend/pycircuit" ]]; then
  PYC_PYTHON_DIR="${PYC_ROOT}/compiler/frontend"
fi

TMP_PYC="${TMP_DIR}/linxcore_top.pyc"
TMP_CPP_DIR="${TMP_DIR}/cpp"
mkdir -p "${TMP_CPP_DIR}"

run_timed_stage() {
  local stage="$1"
  shift
  local rc=0
  local time_log="${TMP_DIR}/${stage}.time.log"
  local stdout_log="${TMP_DIR}/${stage}.stdout.log"
  /usr/bin/time -l "$@" >"${stdout_log}" 2>"${time_log}" || rc=$?
  python3 - <<'PY' "${stage}" "${time_log}" "${rc}" "${TMP_DIR}/${stage}.json"
import json
import pathlib
import re
import sys

stage = sys.argv[1]
time_log = pathlib.Path(sys.argv[2])
rc = int(sys.argv[3])
out_path = pathlib.Path(sys.argv[4])
text = time_log.read_text(encoding="utf-8", errors="replace")

real_s = None
user_s = None
sys_s = None
max_rss = None
for line in text.splitlines():
    line = line.strip()
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+real\b", line)
    if m:
        real_s = float(m.group(1))
        continue
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+user\b", line)
    if m:
        user_s = float(m.group(1))
        continue
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+sys\b", line)
    if m:
        sys_s = float(m.group(1))
        continue
    m = re.search(r"([0-9]+)\s+maximum resident set size\b", line)
    if m:
        max_rss = int(m.group(1))
        continue

obj = {
    "stage": stage,
    "rc": rc,
    "real_s": real_s,
    "user_s": user_s,
    "sys_s": sys_s,
    "max_rss_bytes": max_rss,
    "time_log": str(time_log),
}
out_path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")
PY
  return "${rc}"
}

run_timed_stage emit \
  env PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH="${PYC_PYTHON_DIR}:${ROOT_DIR}/src" \
    python3 -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}"

run_timed_stage pycc_cpp \
  "${PYCC}" "${TMP_PYC}" \
    --emit=cpp \
    --build-profile="${PROFILE}" \
    --logic-depth="${LOGIC_DEPTH}" \
    --out-dir="${TMP_CPP_DIR}" \
    --cpp-split=module \
    --profile-json="${TMP_DIR}/pycc_profile.json"

if [[ "${RUN_CPP_BUILD}" == "1" ]]; then
  run_timed_stage cpp_build \
    env LINXCORE_BUILD_PROFILE="${PROFILE}" PYC_SKIP_RUN=1 \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
fi

python3 - <<'PY' "${OUT_JSON}" "${PROFILE}" "${TMP_DIR}" "${ROOT_DIR}" "${RUN_CPP_BUILD}"
import json
import pathlib
import sys

out_json = pathlib.Path(sys.argv[1])
profile = sys.argv[2]
tmp = pathlib.Path(sys.argv[3])
root = pathlib.Path(sys.argv[4])
run_cpp_build = sys.argv[5] == "1"

stages = []
for stage in ["emit", "pycc_cpp", "cpp_build"]:
    p = tmp / f"{stage}.json"
    if p.exists():
        stages.append(json.loads(p.read_text(encoding="utf-8")))

pycc_profile_path = tmp / "pycc_profile.json"
pycc_profile = None
if pycc_profile_path.exists():
    pycc_profile = json.loads(pycc_profile_path.read_text(encoding="utf-8"))

manifest_path = root / "generated" / "cpp" / "linxcore_top" / "cpp_compile_manifest.json"
hotspots = []
module_hotspots = []
if manifest_path.exists():
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = []
    module_rows = {}
    for src in data.get("sources", []):
        rec = {
            "path": src.get("path"),
            "kind": src.get("kind"),
            "complexity_score": src.get("complexity_score", 0),
            "predicted_compile_cost": src.get("predicted_compile_cost", 0.0),
            "bytes": src.get("bytes", 0),
            "lines": src.get("lines", 0),
            "module": src.get("module", ""),
        }
        rows.append(rec)
        mod = rec["module"] or "<unknown>"
        agg = module_rows.setdefault(mod, {
            "module": mod,
            "source_count": 0,
            "predicted_compile_cost": 0.0,
            "complexity_score": 0,
            "bytes": 0,
            "lines": 0,
        })
        agg["source_count"] += 1
        agg["predicted_compile_cost"] += float(rec.get("predicted_compile_cost", 0.0) or 0.0)
        agg["complexity_score"] += int(rec.get("complexity_score", 0) or 0)
        agg["bytes"] += int(rec.get("bytes", 0) or 0)
        agg["lines"] += int(rec.get("lines", 0) or 0)
    rows.sort(key=lambda r: (r.get("predicted_compile_cost", 0.0), r.get("complexity_score", 0)), reverse=True)
    hotspots = rows[:20]
    module_hotspots = sorted(
        module_rows.values(),
        key=lambda r: (r["predicted_compile_cost"], r["complexity_score"], r["source_count"]),
        reverse=True,
    )[:20]

summary = {
    "version": 1,
    "profile": profile,
    "run_cpp_build": run_cpp_build,
    "stages": stages,
    "pycc_profile": pycc_profile,
    "manifest_hotspots_top20": hotspots,
    "manifest_module_hotspots_top20": module_hotspots,
}

out_json.parent.mkdir(parents=True, exist_ok=True)
out_json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(out_json)
PY
