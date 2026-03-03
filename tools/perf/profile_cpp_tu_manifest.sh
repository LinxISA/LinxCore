#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
MANIFEST="${PYC_MANIFEST_PATH:-${ROOT_DIR}/generated/cpp/linxcore_top/cpp_compile_manifest.json}"
OUT_DIR="${PYC_PERF_OUT_DIR:-${ROOT_DIR}/generated/perf}"
PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}"
OUT_JSON="${PYC_PERF_TU_OUT_JSON:-${OUT_DIR}/cpp_tu_profile_${PROFILE}.json}"

# Prefer the same compiler default as the LinxCore build scripts (clang++ on Darwin).
if [[ -z "${CXX:-}" ]]; then
  if command -v clang++ >/dev/null 2>&1; then
    export CXX="$(command -v clang++)"
  elif [[ -x "/opt/homebrew/bin/g++-15" ]]; then
    export CXX="/opt/homebrew/bin/g++-15"
  fi
fi
CXX_BIN="${CXX:-clang++}"

CXXFLAGS_STR="${PYC_TU_CXXFLAGS:-${PYC_MODEL_CXXFLAGS:--O2 -DNDEBUG}}"
CXXFLAGS_TICK_STR="${PYC_TU_CXXFLAGS_TICK:-}"
CXXFLAGS_XFER_STR="${PYC_TU_CXXFLAGS_XFER:-}"
TU_LIMIT="${PYC_TU_LIMIT:-0}"

mkdir -p "${OUT_DIR}"

if [[ ! -f "${MANIFEST}" ]]; then
  echo "error: manifest not found: ${MANIFEST}" >&2
  exit 1
fi

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

python3 - <<'PY' "${MANIFEST}" "${OUT_JSON}" "${PYC_ROOT_DIR}" "${CXX_BIN}" "${CXXFLAGS_STR}" "${CXXFLAGS_TICK_STR}" "${CXXFLAGS_XFER_STR}" "${TU_LIMIT}"
import json
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import time

manifest_path = pathlib.Path(sys.argv[1])
out_json = pathlib.Path(sys.argv[2])
pyc_root = pathlib.Path(sys.argv[3])
cxx = sys.argv[4]
cxxflags = shlex.split(sys.argv[5])
tick_cxxflags_raw = sys.argv[6]
xfer_cxxflags_raw = sys.argv[7]
tu_limit = int(sys.argv[8])

def _derive_tick_flags(flags: list[str]) -> list[str]:
    opt = None
    rest: list[str] = []
    for f in flags:
        if f in ("-O0", "-O1", "-O2", "-O3", "-Os", "-Oz"):
            opt = f
            continue
        rest.append(f)
    if opt in ("-O0", "-O1"):
        return [opt] + rest
    return ["-O1"] + rest

tick_cxxflags = shlex.split(tick_cxxflags_raw) if tick_cxxflags_raw else _derive_tick_flags(cxxflags)
xfer_cxxflags = shlex.split(xfer_cxxflags_raw) if xfer_cxxflags_raw else tick_cxxflags

data = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest_dir = manifest_path.parent

include_dirs = []
runtime_inc = pyc_root / "runtime"
if runtime_inc.is_dir():
    include_dirs.append(str(runtime_inc))
for p in data.get("include_dirs", []):
    pp = pathlib.Path(p)
    if not pp.is_absolute():
        pp = manifest_dir / pp
    include_dirs.append(str(pp))

compile_defines = []
for d in data.get("compile_defines", []):
    if d:
        compile_defines.append(str(d))

sources = []
for src in data.get("sources", []):
    rel = src.get("path", "")
    if not rel:
        continue
    p = pathlib.Path(rel)
    if not p.is_absolute():
        p = manifest_dir / p
    sources.append({
        "path": str(p),
        "kind": src.get("kind", ""),
        "module": src.get("module", ""),
        "predicted_compile_cost": float(src.get("predicted_compile_cost", 0.0) or 0.0),
        "complexity_score": int(src.get("complexity_score", 0) or 0),
    })

sources.sort(key=lambda r: (r["predicted_compile_cost"], r["complexity_score"]), reverse=True)
if tu_limit > 0:
    sources = sources[:tu_limit]

rows = []
peak_rss = 0
with tempfile.TemporaryDirectory(prefix="linxcore_tu_prof_") as td:
    td_path = pathlib.Path(td)
    for idx, src in enumerate(sources):
        src_path = pathlib.Path(src["path"])
        obj = td_path / (src_path.name.replace(".cpp", "") + f".{idx}.o")
        cmd = ["/usr/bin/time", "-l", cxx, "-std=c++17", *cxxflags]
        kind = src.get("kind", "")
        if kind == "tick":
            cmd = ["/usr/bin/time", "-l", cxx, "-std=c++17", *tick_cxxflags]
        elif kind in ("xfer", "transfer"):
            cmd = ["/usr/bin/time", "-l", cxx, "-std=c++17", *xfer_cxxflags]
        for d in compile_defines:
            cmd.append(f"-D{d}")
        for inc in include_dirs:
            cmd.extend(["-I", inc])
        cmd.extend(["-c", str(src_path), "-o", str(obj)])

        t0 = time.time()
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        wall_s = time.time() - t0
        stderr = proc.stderr
        m_real = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+real\b", stderr)
        m_user = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+user\b", stderr)
        m_sys = re.search(r"([0-9]+(?:\.[0-9]+)?)\s+sys\b", stderr)
        m_rss = re.search(r"([0-9]+)\s+maximum resident set size\b", stderr)
        if m_rss is None:
            # Some macOS versions also report this as "peak memory footprint".
            m_rss = re.search(r"([0-9]+)\s+peak memory footprint\b", stderr)

        real_s = float(m_real.group(1)) if m_real else wall_s
        user_s = float(m_user.group(1)) if m_user else None
        sys_s = float(m_sys.group(1)) if m_sys else None
        rss_b = int(m_rss.group(1)) if m_rss else 0
        peak_rss = max(peak_rss, rss_b)

        rows.append({
            "path": str(src_path),
            "module": src["module"],
            "kind": src["kind"],
            "predicted_compile_cost": src["predicted_compile_cost"],
            "complexity_score": src["complexity_score"],
            "rc": proc.returncode,
            "real_s": real_s,
            "user_s": user_s,
            "sys_s": sys_s,
            "max_rss_bytes": rss_b,
        })

rows.sort(key=lambda r: r["real_s"], reverse=True)
summary = {
    "version": 1,
    "manifest": str(manifest_path),
    "tu_count_profiled": len(rows),
    "tu_limit": tu_limit,
    "peak_rss_bytes": peak_rss,
    "top20_by_real_s": rows[:20],
    "all_rows": rows,
}
out_json.parent.mkdir(parents=True, exist_ok=True)
out_json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(out_json)
PY
