#!/usr/bin/env bash
set -euo pipefail

# Build (only) the pycircuit-generated LinxCore model object files listed in
# generated/cpp/linxcore_top/cpp_compile_manifest.json.
#
# Phase B: use Ninja (depfiles + parallel scheduling) for correctness and speed.
# Default to Homebrew g++-15 for stability (Apple clang has been observed to crash
# compiling the huge JanusBccBackendCompat__tick TU).

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
CPP_MANIFEST="${PYC_MANIFEST_PATH:-${GEN_CPP_DIR}/cpp_compile_manifest.json}"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
HDR="${GEN_CPP_DIR}/linxcore_top.hpp"
NINJA_FILE="${GEN_CPP_DIR}/build_model.ninja"
NINJA_TARGET="model_objects"
NINJA_GEN_TOOL="${ROOT_DIR}/tools/generate/gen_linxcore_model_ninja.py"

MODEL_CXXFLAGS="${PYC_MODEL_CXXFLAGS:-${PYC_TB_CXXFLAGS:--O3 -DNDEBUG}}"

# Choose compiler:
# - If PYC_MODEL_CXX is set, it wins.
# - Otherwise prefer g++-15 when available.
# - Fall back to clang++.
if [[ -n "${PYC_MODEL_CXX:-}" ]]; then
  export CXX="${PYC_MODEL_CXX}"
elif [[ -z "${CXX:-}" ]]; then
  if [[ -x "/opt/homebrew/bin/g++-15" ]]; then
    export CXX="/opt/homebrew/bin/g++-15"
  elif command -v clang++ >/dev/null 2>&1; then
    export CXX="$(command -v clang++)"
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

# Generate Ninja file (command lines include CXX + flags, so changes force rebuild).
if [[ ! -x "${NINJA_GEN_TOOL}" ]]; then
  echo "error: missing ninja generator: ${NINJA_GEN_TOOL}" >&2
  exit 2
fi

# Prefer sysroot if available (Darwin).
if [[ "$(uname -s)" == "Darwin" ]]; then
  if [[ -z "${PYC_TB_SYSROOT:-}" ]]; then
    export PYC_TB_SYSROOT="$(xcrun --show-sdk-path 2>/dev/null || true)"
  fi
fi

export ROOT_DIR GEN_CPP_DIR GEN_OBJ_DIR CPP_MANIFEST PYC_ROOT PYC_COMPAT_INCLUDE PYC_API_INCLUDE CXX_BIN MODEL_CXXFLAGS
python3 "${NINJA_GEN_TOOL}" >/dev/null

if ! command -v ninja >/dev/null 2>&1; then
  echo "error: ninja not found in PATH" >&2
  exit 2
fi

jobs="$(auto_build_jobs)"
if [[ -z "${jobs}" || ! "${jobs}" =~ ^[0-9]+$ || "${jobs}" -lt 1 ]]; then
  jobs=4
fi

ninja -f "${NINJA_FILE}" -j "${jobs}" "${NINJA_TARGET}"

# Stamp config for external consumers/debug.
printf '%s\n' "${MODEL_CXXFLAGS}" > "${GEN_CPP_DIR}/.model_cxxflags"
printf '%s\n' "${CURRENT_MANIFEST_HASH}" > "${GEN_CPP_DIR}/.model_manifest_hash"

printf '%s\n' "${GEN_OBJ_DIR}"
