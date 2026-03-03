#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"

PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}"
OUT_DIR="${PYC_PERF_OUT_DIR:-${ROOT_DIR}/generated/perf}"
OUT_JSON="${PYC_PERF_OUT_JSON:-${OUT_DIR}/pyc_compile_pipeline_${PROFILE}.json}"
RUN_CPP_BUILD="${PYC_PERF_RUN_CPP_BUILD:-0}"
KEEP_TMP_ON_FAIL="${PYC_PERF_KEEP_TMP_ON_FAIL:-1}"
# Match LinxCore generator default; override via PYC_LOGIC_DEPTH to tighten.
LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-2048}"

find_python_bin() {
  if [[ -n "${PYC_PYTHON:-}" && -x "${PYC_PYTHON}" ]]; then
    echo "${PYC_PYTHON}"
    return 0
  fi
  local cand
  for cand in \
    "${PYC_PYTHON_BIN:-}" \
    "/opt/homebrew/bin/python3" \
    "python3.14" \
    "python3.13" \
    "python3.12" \
    "python3.11" \
    "python3.10" \
    "python3"
  do
    [[ -n "${cand}" ]] || continue
    local exe="${cand}"
    if [[ "${exe}" != /* ]]; then
      if ! command -v "${exe}" >/dev/null 2>&1; then
        continue
      fi
      exe="$(command -v "${exe}")"
    elif [[ ! -x "${exe}" ]]; then
      continue
    fi
    if "${exe}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
      echo "${exe}"
      return 0
    fi
  done
  return 1
}

PYTHON_BIN="$(find_python_bin)" || {
  echo "error: need python>=3.10 to run pyc4 frontend (set PYC_PYTHON_BIN=...)" >&2
  exit 2
}

CALLFRAME_SIZE_RAW="${LINXCORE_CALLFRAME_SIZE:-0}"
CALLFRAME_SIZE="$(
"${PYTHON_BIN}" - <<'PY' "${CALLFRAME_SIZE_RAW}"
import sys
s = sys.argv[1]
try:
    v = int(s, 0)
except ValueError:
    v = 0
if v < 0 or (v & 0x7):
    v = 0
print(v & ((1 << 64) - 1))
PY
)"

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

find_pyc_root() {
  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    echo "${PYC_ROOT}"
    return 0
  fi
  if [[ -d "${LINX_ROOT}/tools/pyCircuit" ]]; then
    echo "${LINX_ROOT}/tools/pyCircuit"
    return 0
  fi
  if [[ -d "/Users/zhoubot/pyCircuit" ]]; then
    echo "/Users/zhoubot/pyCircuit"
    return 0
  fi
  return 1
}

PYC_ROOT_DIR="$(find_pyc_root)" || {
  echo "error: cannot locate pyCircuit; set PYC_ROOT=..." >&2
  exit 2
}

# shellcheck disable=SC1090
source "${PYC_ROOT_DIR}/flows/scripts/lib.sh"
pyc_find_pycc

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
  "${PYTHON_BIN}" - <<'PY' "${stage}" "${time_log}" "${rc}" "${TMP_DIR}/${stage}.json"
import json
import pathlib
import re
import sys

stage = sys.argv[1]
time_log = pathlib.Path(sys.argv[2])
rc = int(sys.argv[3])
out_path = pathlib.Path(sys.argv[4])
text = time_log.read_text(encoding="utf-8", errors="replace")

def _find_float(pat: str) -> float | None:
    m = re.search(pat, text)
    if not m:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None

def _find_int(pat: str) -> int | None:
    m = re.search(pat, text)
    if not m:
        return None
    try:
        return int(m.group(1))
    except ValueError:
        return None

real_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+real\b")
user_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+user\b")
sys_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+sys\b")
max_rss = _find_int(r"([0-9]+)\s+maximum resident set size\b")
if max_rss is None:
    # Some macOS versions also report this as "peak memory footprint".
    max_rss = _find_int(r"([0-9]+)\s+peak memory footprint\b")

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

EMIT_PARAM_ARGS=()
while IFS='=' read -r key value; do
  [[ -z "${key}" ]] && continue
  [[ "${key}" != PYC_PARAM_* ]] && continue
  param_name="${key#PYC_PARAM_}"
  param_name="$(printf '%s' "${param_name}" | tr '[:upper:]' '[:lower:]')"
  [[ -z "${param_name}" ]] && continue
  EMIT_PARAM_ARGS+=(--param "${param_name}=${value}")
done < <(env)

PYTHONPATH_VAL="$(pyc_pythonpath):${ROOT_DIR}/src"
if [[ "${#EMIT_PARAM_ARGS[@]}" -gt 0 ]]; then
  run_timed_stage emit \
    env PYTHONDONTWRITEBYTECODE=1 \
      LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
      PYTHONPATH="${PYTHONPATH_VAL}" \
      "${PYTHON_BIN}" -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}" "${EMIT_PARAM_ARGS[@]}"
else
  run_timed_stage emit \
    env PYTHONDONTWRITEBYTECODE=1 \
      LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
      PYTHONPATH="${PYTHONPATH_VAL}" \
      "${PYTHON_BIN}" -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}"
fi

run_timed_stage pycc_cpp \
  "${PYCC}" "${TMP_PYC}" \
    --emit=cpp \
    --build-profile="${PROFILE}" \
    --hierarchy-policy=strict \
    --logic-depth="${LOGIC_DEPTH}" \
    --out-dir="${TMP_CPP_DIR}" \
    --cpp-split=module \
    --profile-json="${TMP_DIR}/pycc_profile.json"

if [[ "${RUN_CPP_BUILD}" == "1" ]]; then
  run_timed_stage cpp_build \
    env LINXCORE_BUILD_PROFILE="${PROFILE}" PYC_SKIP_RUN=1 \
      bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
fi

"${PYTHON_BIN}" - <<'PY' "${OUT_JSON}" "${PROFILE}" "${TMP_DIR}" "${RUN_CPP_BUILD}"
import json
import pathlib
import sys

out_json = pathlib.Path(sys.argv[1])
profile = sys.argv[2]
tmp = pathlib.Path(sys.argv[3])
run_cpp_build = sys.argv[4] == "1"

stages = []
for stage in ["emit", "pycc_cpp", "cpp_build"]:
    p = tmp / f"{stage}.json"
    if p.exists():
        stages.append(json.loads(p.read_text(encoding="utf-8")))

pycc_profile_path = tmp / "pycc_profile.json"
pycc_profile = None
if pycc_profile_path.exists():
    pycc_profile = json.loads(pycc_profile_path.read_text(encoding="utf-8"))

manifest_path = tmp / "cpp" / "cpp_compile_manifest.json"
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
    "manifest": str(manifest_path),
    "manifest_hotspots_top20": hotspots,
    "manifest_module_hotspots_top20": module_hotspots,
}

out_json.parent.mkdir(parents=True, exist_ok=True)
out_json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(out_json)
PY
