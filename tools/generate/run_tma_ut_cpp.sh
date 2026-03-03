#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"

find_pyc_root() {
  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    echo "${PYC_ROOT}"
    return 0
  fi
  if [[ -d "${LINX_ROOT}/tools/pyCircuit" ]]; then
    echo "${LINX_ROOT}/tools/pyCircuit"
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

HARNESS_PY="${ROOT_DIR}/src/tma/ut_tma_harness.py"
TMA_DUT_PY="${ROOT_DIR}/src/tma/tma.py"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/ut_tma_harness"
GEN_PYC="${GEN_CPP_DIR}/ut_tma_harness.pyc"
CPP_MANIFEST="${GEN_CPP_DIR}/cpp_compile_manifest.json"
TB_SRC="${ROOT_DIR}/tb/tb_tma_ut.cpp"
TB_SCEN_HDR="${ROOT_DIR}/tb/tb_tma_ut_scenarios.hpp"
TB_EXE="${GEN_CPP_DIR}/tb_tma_ut_cpp"
OBJ_DIR="${GEN_CPP_DIR}/.obj"
TB_OBJ_DIR="${GEN_CPP_DIR}/.tb_obj"
TB_OBJ="${TB_OBJ_DIR}/tb_tma_ut.o"

BUILD_LOCK_DIR="${GEN_CPP_DIR}/.tb_build_lock"

CXX="${CXX:-g++}"
CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG -std=c++20}"
LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-128}"

mkdir -p "${GEN_CPP_DIR}" "${OBJ_DIR}" "${TB_OBJ_DIR}"

if [[ ! -f "${HARNESS_PY}" ]]; then
  echo "error: harness file missing: ${HARNESS_PY}" >&2
  exit 1
fi
if [[ ! -f "${TMA_DUT_PY}" ]]; then
  echo "error: tma dut file missing: ${TMA_DUT_PY}" >&2
  exit 1
fi
if [[ ! -f "${TB_SRC}" || ! -f "${TB_SCEN_HDR}" ]]; then
  echo "error: tb sources missing: ${TB_SRC} / ${TB_SCEN_HDR}" >&2
  exit 1
fi

cleanup_lock() {
  rm -rf "${BUILD_LOCK_DIR}" 2>/dev/null || true
}

acquire_lock() {
  local tries=0
  while ! mkdir "${BUILD_LOCK_DIR}" 2>/dev/null; do
    tries=$((tries + 1))
    if [[ ${tries} -gt 300 ]]; then
      return 1
    fi
    sleep 0.1
  done
  return 0
}

if ! acquire_lock; then
  echo "error: timeout waiting build lock: ${BUILD_LOCK_DIR}" >&2
  exit 1
fi
trap cleanup_lock EXIT

need_emit=0
if [[ ! -f "${GEN_PYC}" || "${HARNESS_PY}" -nt "${GEN_PYC}" || "${TMA_DUT_PY}" -nt "${GEN_PYC}" ]]; then
  need_emit=1
fi
if [[ ${need_emit} -eq 1 ]]; then
  echo "[tma-ut] emitting PYC: ${GEN_PYC}"
  PYTHONPATH="$(pyc_pythonpath):${ROOT_DIR}/src" \
    "${PYTHON_BIN}" -m pycircuit.cli emit "${HARNESS_PY}" -o "${GEN_PYC}"
fi

need_pycc=0
if [[ ! -f "${CPP_MANIFEST}" || "${GEN_PYC}" -nt "${CPP_MANIFEST}" ]]; then
  need_pycc=1
fi
if [[ ${need_pycc} -eq 1 ]]; then
  echo "[tma-ut] generating C++ model in ${GEN_CPP_DIR}"
  "${PYCC}" "${GEN_PYC}" \
    --emit=cpp \
    --out-dir="${GEN_CPP_DIR}" \
    --cpp-split=module \
    --logic-depth="${LOGIC_DEPTH}" >/dev/null
fi

mapfile -t GEN_SRCS < <("${PYTHON_BIN}" - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}"
import json
import pathlib
import sys
manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
if not manifest.exists():
    raise SystemExit("manifest missing")
data = json.loads(manifest.read_text(encoding="utf-8"))
for ent in data.get("sources", []):
    rel = ent.get("path", "")
    if not rel:
        continue
    p = pathlib.Path(rel)
    if not p.is_absolute():
        p = base / p
    print(str(p.resolve()))
PY
)

if [[ ${#GEN_SRCS[@]} -eq 0 ]]; then
  echo "error: no generated sources found in ${CPP_MANIFEST}" >&2
  exit 1
fi

compile_one() {
  local src="$1"
  local obj="$2"
  if [[ ! -f "${obj}" || "${src}" -nt "${obj}" || "${TB_SCEN_HDR}" -nt "${obj}" ]]; then
    "${CXX}" ${CXXFLAGS} -c "${src}" -o "${obj}" \
      -I"${GEN_CPP_DIR}" \
      -I"${PYC_ROOT_DIR}/runtime" \
      -I"${ROOT_DIR}/tb"
  fi
}

for src in "${GEN_SRCS[@]}"; do
  stem="$(printf '%s' "${src}" | sed 's#[/:]#_#g')"
  obj="${OBJ_DIR}/${stem%.cpp}.o"
  compile_one "${src}" "${obj}"
done

compile_one "${TB_SRC}" "${TB_OBJ}"

mapfile -t ALL_OBJS < <(
  for src in "${GEN_SRCS[@]}"; do
    stem="$(printf '%s' "${src}" | sed 's#[/:]#_#g')"
    echo "${OBJ_DIR}/${stem%.cpp}.o"
  done
  echo "${TB_OBJ}"
)

need_link=0
if [[ ! -x "${TB_EXE}" ]]; then
  need_link=1
else
  for obj in "${ALL_OBJS[@]}"; do
    if [[ "${obj}" -nt "${TB_EXE}" ]]; then
      need_link=1
      break
    fi
  done
fi

if [[ ${need_link} -eq 1 ]]; then
  echo "[tma-ut] linking ${TB_EXE}"
  "${CXX}" ${CXXFLAGS} -o "${TB_EXE}" "${ALL_OBJS[@]}"
fi

echo "[tma-ut] running ${TB_EXE}"
"${TB_EXE}"
