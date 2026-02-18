#!/usr/bin/env bash
set -euo pipefail

# Build (only) the pycircuit-generated LinxCore model object files listed in
# generated/cpp/linxcore_top/cpp_compile_manifest.json.
#
# Goals:
# - Single entry point used by both TB builds and cosim runner builds.
# - Robust to interrupts: atomic .o writes via tmp+mv.
# - Fast incremental rebuilds: only recompile stale/missing objs.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
CPP_MANIFEST="${PYC_MANIFEST_PATH:-${GEN_CPP_DIR}/cpp_compile_manifest.json}"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
HDR="${GEN_CPP_DIR}/linxcore_top.hpp"

MODEL_CXXFLAGS="${PYC_MODEL_CXXFLAGS:-${PYC_TB_CXXFLAGS:--O3 -DNDEBUG}}"

if [[ -z "${CXX:-}" ]]; then
  if command -v clang++ >/dev/null 2>&1; then
    export CXX="$(command -v clang++)"
  elif [[ -x "/opt/homebrew/bin/g++-15" ]]; then
    export CXX="/opt/homebrew/bin/g++-15"
  fi
fi
CXX_BIN="${CXX:-clang++}"

PYC_API_INCLUDE="${PYC_ROOT}/include"
if [[ ! -f "${PYC_API_INCLUDE}/pyc/cpp/pyc_sim.hpp" ]]; then
  cand="$(find "${PYC_ROOT}" -path '*/include/pyc/cpp/pyc_sim.hpp' -print -quit 2>/dev/null || true)"
  if [[ -n "${cand}" ]]; then
    PYC_API_INCLUDE="${cand%/pyc/cpp/pyc_sim.hpp}"
  fi
fi
PYC_COMPAT_INCLUDE="${ROOT_DIR}/generated/include_compat"
mkdir -p "${PYC_COMPAT_INCLUDE}/pyc"
if [[ -L "${PYC_COMPAT_INCLUDE}/pyc/cpp" || -f "${PYC_COMPAT_INCLUDE}/pyc/cpp" ]]; then
  rm -f "${PYC_COMPAT_INCLUDE}/pyc/cpp"
elif [[ -d "${PYC_COMPAT_INCLUDE}/pyc/cpp" ]]; then
  rmdir "${PYC_COMPAT_INCLUDE}/pyc/cpp" 2>/dev/null || true
fi
ln -s "${PYC_ROOT}/runtime/cpp" "${PYC_COMPAT_INCLUDE}/pyc/cpp"

auto_build_jobs() {
  if [[ -n "${PYC_BUILD_JOBS:-}" ]]; then
    printf '%s\n' "${PYC_BUILD_JOBS}"
    return
  fi
  if command -v nproc >/dev/null 2>&1; then
    nproc
    return
  fi
  if command -v sysctl >/dev/null 2>&1; then
    sysctl -n hw.logicalcpu 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || true
    return
  fi
  printf '4\n'
}

read_manifest_hash() {
  python3 - <<'PY' "${CPP_MANIFEST}"
import hashlib
import pathlib
import sys
p = pathlib.Path(sys.argv[1])
if not p.exists():
    print("")
else:
    print(hashlib.sha256(p.read_bytes()).hexdigest())
PY
}

manifest_sources() {
  python3 - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}" "${GEN_OBJ_DIR}"
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
obj_base = pathlib.Path(sys.argv[3])
if not manifest.exists():
    sys.exit(0)
data = json.loads(manifest.read_text(encoding="utf-8"))
for entry in data.get("sources", []):
    rel = entry.get("path", "")
    if not rel:
        continue
    src = pathlib.Path(rel)
    if not src.is_absolute():
        src = base / src
    stem = src.as_posix().replace("/", "__")
    if stem.endswith(".cpp"):
        stem = stem[:-4]
    obj = obj_base / f"{stem}.o"
    print(f"{src}\t{obj}")
PY
}

# Regenerate if required (keeps behavior consistent with existing scripts).
need_regen=0
if [[ ! -f "${HDR}" ]]; then
  need_regen=1
elif [[ ! -f "${CPP_MANIFEST}" ]]; then
  need_regen=1
elif find "${ROOT_DIR}/src" -name '*.py' -newer "${HDR}" | grep -q .; then
  need_regen=1
fi
if [[ "${need_regen}" -ne 0 ]]; then
  bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi

CURRENT_MANIFEST_HASH="$(read_manifest_hash)"
if [[ -z "${CURRENT_MANIFEST_HASH}" ]]; then
  echo "error: missing cpp manifest: ${CPP_MANIFEST}" >&2
  exit 2
fi

GEN_SRC_OBJ_PAIRS=()
while IFS= read -r pair; do
  GEN_SRC_OBJ_PAIRS+=("${pair}")
done < <(manifest_sources)
if [[ "${#GEN_SRC_OBJ_PAIRS[@]}" -eq 0 ]]; then
  echo "error: manifest has no sources: ${CPP_MANIFEST}" >&2
  exit 2
fi

# Build lock to avoid multiple simultaneous compiles into the same obj dir.
BUILD_LOCK_DIR="${GEN_CPP_DIR}/.model_build_lock"
BUILD_LOCK_OWNER="${BUILD_LOCK_DIR}/owner.pid"
LOCK_STALE_SECS="${PYC_MODEL_LOCK_STALE_SECS:-900}"
lock_held=0

cleanup_lock() {
  if [[ "${lock_held}" -eq 1 ]]; then
    rm -f "${BUILD_LOCK_OWNER}" >/dev/null 2>&1 || true
    rmdir "${BUILD_LOCK_DIR}" >/dev/null 2>&1 || true
    lock_held=0
  fi
}

acquire_lock() {
  for _ in $(seq 1 1800); do
    if mkdir "${BUILD_LOCK_DIR}" 2>/dev/null; then
      printf '%s\n' "$$" > "${BUILD_LOCK_OWNER}" 2>/dev/null || true
      lock_held=1
      return 0
    fi

    stale=0
    owner_pid=""
    if [[ -f "${BUILD_LOCK_OWNER}" ]]; then
      owner_pid="$(tr -cd '0-9' < "${BUILD_LOCK_OWNER}" || true)"
    fi
    if [[ -n "${owner_pid}" ]]; then
      if ! kill -0 "${owner_pid}" 2>/dev/null; then
        stale=1
      fi
    else
      lock_age="$(python3 - <<'PY' "${BUILD_LOCK_DIR}"
import os
import sys
import time
path = sys.argv[1]
try:
    print(int(max(0, time.time() - os.path.getmtime(path))))
except Exception:
    print(0)
PY
      )"
      if [[ "${lock_age}" =~ ^[0-9]+$ ]] && [[ "${lock_age}" -ge "${LOCK_STALE_SECS}" ]]; then
        stale=1
      fi
    fi
    if [[ "${stale}" -eq 1 ]]; then
      rm -f "${BUILD_LOCK_OWNER}" >/dev/null 2>&1 || true
      rmdir "${BUILD_LOCK_DIR}" >/dev/null 2>&1 || true
    fi
    sleep 0.1
  done
  return 1
}

trap cleanup_lock EXIT INT TERM
if ! acquire_lock; then
  echo "error: timeout waiting for model build lock: ${BUILD_LOCK_DIR}" >&2
  exit 3
fi

mkdir -p "${GEN_OBJ_DIR}"

# Clean up stale temp/zero-sized artifacts from interrupted builds.
rm -f "${GEN_OBJ_DIR}"/*.tmp* >/dev/null 2>&1 || true
find "${GEN_OBJ_DIR}" -maxdepth 1 -type f -name '*.o' -size 0 -delete >/dev/null 2>&1 || true

MODEL_MANIFEST_HASH_CFG="${GEN_CPP_DIR}/.model_manifest_hash"
MODEL_CXXFLAGS_CFG="${GEN_CPP_DIR}/.model_cxxflags"
manifest_hash_cfg=""; cxxflags_cfg=""
if [[ -f "${MODEL_MANIFEST_HASH_CFG}" ]]; then manifest_hash_cfg="$(cat "${MODEL_MANIFEST_HASH_CFG}")"; fi
if [[ -f "${MODEL_CXXFLAGS_CFG}" ]]; then cxxflags_cfg="$(cat "${MODEL_CXXFLAGS_CFG}")"; fi

stale_pairs=()
for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
  gen_src="${pair%%$'\t'*}"
  gen_obj="${pair#*$'\t'}"
  mkdir -p "$(dirname "${gen_obj}")"
  if [[ ! -f "${gen_src}" || ! -f "${gen_obj}" ]]; then
    stale_pairs+=("${pair}")
    continue
  fi
  if [[ "${gen_src}" -nt "${gen_obj}" ]]; then
    stale_pairs+=("${pair}")
    continue
  fi
  if find "${GEN_CPP_DIR}" -maxdepth 1 -name '*.hpp' -newer "${gen_obj}" | grep -q .; then
    stale_pairs+=("${pair}")
    continue
  fi
  if [[ "${manifest_hash_cfg}" != "${CURRENT_MANIFEST_HASH}" ]]; then
    stale_pairs+=("${pair}")
    continue
  fi
  if [[ "${cxxflags_cfg}" != "${MODEL_CXXFLAGS}" ]]; then
    stale_pairs+=("${pair}")
    continue
  fi

done

build_jobs="$(auto_build_jobs)"
if [[ -z "${build_jobs}" || ! "${build_jobs}" =~ ^[0-9]+$ || "${build_jobs}" -lt 1 ]]; then
  build_jobs=4
fi

common_flags=(
  -std=c++17
  ${MODEL_CXXFLAGS}
  -I "${PYC_COMPAT_INCLUDE}"
  -I "${PYC_API_INCLUDE}"
  -I "${PYC_ROOT}/runtime"
  -I "${PYC_ROOT}/runtime/cpp"
  -I "${GEN_CPP_DIR}"
)
if [[ "$(uname -s)" == "Darwin" ]]; then
  sysroot="${PYC_TB_SYSROOT:-}"
  if [[ -z "${sysroot}" ]]; then
    sysroot="$(xcrun --show-sdk-path 2>/dev/null || true)"
  fi
  if [[ -n "${sysroot}" ]]; then
    common_flags+=(-isysroot "${sysroot}")
    if [[ "$(basename "${CXX_BIN}")" == *clang* ]]; then
      common_flags+=(-isystem "${sysroot}/usr/include/c++/v1")
    fi
  fi
fi

compile_one() {
  local src="$1"
  local obj="$2"
  local tmp="${obj}.tmp.$$.$RANDOM"
  rm -f "${tmp}" >/dev/null 2>&1 || true
  if ! "${CXX_BIN}" "${common_flags[@]}" -c "${src}" -o "${tmp}"; then
    rm -f "${tmp}" >/dev/null 2>&1 || true
    return 1
  fi
  mv -f "${tmp}" "${obj}"
}

compile_fail=0
if [[ "${#stale_pairs[@]}" -gt 0 ]]; then
  if [[ "${build_jobs}" -le 1 ]]; then
    for pair in "${stale_pairs[@]}"; do
      src="${pair%%$'\t'*}"; obj="${pair#*$'\t'}"
      if ! compile_one "${src}" "${obj}"; then
        compile_fail=1
        break
      fi
    done
  else
    pids=()
    for pair in "${stale_pairs[@]}"; do
      src="${pair%%$'\t'*}"; obj="${pair#*$'\t'}"
      (compile_one "${src}" "${obj}") &
      pids+=("$!")
      if [[ "${#pids[@]}" -ge "${build_jobs}" ]]; then
        if ! wait "${pids[0]}"; then
          compile_fail=1
        fi
        if [[ "${#pids[@]}" -gt 1 ]]; then
          pids=("${pids[@]:1}")
        else
          pids=()
        fi
      fi
    done
    if [[ "${#pids[@]}" -gt 0 ]]; then
      for pid in "${pids[@]}"; do
        if ! wait "${pid}"; then
          compile_fail=1
        fi
      done
    fi
  fi
fi

if [[ "${compile_fail}" -ne 0 ]]; then
  echo "error: model object compile failed" >&2
  exit 2
fi

# Validate: all objects exist.
for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
  obj="${pair#*$'\t'}"
  if [[ ! -f "${obj}" ]]; then
    echo "error: missing model object after build: ${obj}" >&2
    exit 2
  fi
  if [[ ! -s "${obj}" ]]; then
    echo "error: zero-sized model object after build: ${obj}" >&2
    exit 2
  fi

done

printf '%s\n' "${MODEL_CXXFLAGS}" > "${MODEL_CXXFLAGS_CFG}"
printf '%s\n' "${CURRENT_MANIFEST_HASH}" > "${MODEL_MANIFEST_HASH_CFG}"

# Print the object directory for convenience.
printf '%s\n' "${GEN_OBJ_DIR}"
