#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/linxcore_top.hpp"
CPP_MANIFEST="${GEN_CPP_DIR}/cpp_compile_manifest.json"
SRC="${ROOT_DIR}/tests/test_rob_bookkeeping.cpp"
EXE="${GEN_CPP_DIR}/test_rob_bookkeeping"
MEMH="${PYC_TEST_MEMH:-/Users/zhoubot/pyCircuit/designs/examples/linx_cpu/programs/test_or.memh}"
TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -Wall -Wextra}"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
RUN_TOP_CPP="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
PYC_API_INCLUDE="${PYC_ROOT}/include"
if [[ ! -f "${PYC_API_INCLUDE}/pyc/cpp/pyc_sim.hpp" ]]; then
  cand="$(find "${PYC_ROOT}" -path '*/include/pyc/cpp/pyc_sim.hpp' -print -quit 2>/dev/null || true)"
  if [[ -n "${cand}" ]]; then
    PYC_API_INCLUDE="${cand%/pyc/cpp/pyc_sim.hpp}"
  fi
fi
PYC_COMPAT_INCLUDE="${ROOT_DIR}/generated/include_compat"
mkdir -p "${PYC_COMPAT_INCLUDE}/pyc"
ln -sfn "${PYC_ROOT}/runtime/cpp" "${PYC_COMPAT_INCLUDE}/pyc/cpp"

if [[ ! -f "${GEN_HDR}" ]]; then
  bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi
if [[ ! -f "${CPP_MANIFEST}" ]]; then
  bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi
if [[ ! -f "${MEMH}" ]]; then
  build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
  memh2="$(printf "%s\n" "${build_out}" | sed -n '2p')"
  if [[ -n "${memh2}" && -f "${memh2}" ]]; then
    MEMH="${memh2}"
  fi
fi

need_build=0
if [[ ! -x "${EXE}" ]]; then
  need_build=1
elif [[ "${SRC}" -nt "${EXE}" || "${GEN_HDR}" -nt "${EXE}" ]]; then
  need_build=1
fi

if [[ "${need_build}" -ne 0 ]]; then
  # Build/refresh generated model objects via canonical runner flow.
  # We only need a tiny run window here; this primes manifest objects.
  PYC_MAX_CYCLES=4 \
  PYC_TB_CXXFLAGS="${TB_CXXFLAGS}" \
    bash "${RUN_TOP_CPP}" "${MEMH}" >/dev/null 2>&1 || true

  gen_objects=()
  while IFS= read -r obj_path; do
    gen_objects+=("${obj_path}")
  done < <(
    python3 - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}" "${GEN_OBJ_DIR}"
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
obj_base = pathlib.Path(sys.argv[3])
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
    print(obj_base / f"{stem}.o")
PY
  )
  if [[ "${#gen_objects[@]}" -eq 0 ]]; then
    echo "error: empty generated object list from ${CPP_MANIFEST}" >&2
    exit 2
  fi
  for obj in "${gen_objects[@]}"; do
    if [[ ! -f "${obj}" ]]; then
      echo "error: missing generated object: ${obj}" >&2
      exit 2
    fi
  done
  "${CXX:-clang++}" -std=c++17 ${TB_CXXFLAGS} \
    -I "${PYC_COMPAT_INCLUDE}" \
    -I "${PYC_API_INCLUDE}" \
    -I "${PYC_ROOT}/runtime" \
    -I "${PYC_ROOT}/runtime/cpp" \
    -I "${GEN_CPP_DIR}" \
    -c "${SRC}" \
    -o "${GEN_CPP_DIR}/test_rob_bookkeeping.o"
  "${CXX:-clang++}" -std=c++17 ${TB_CXXFLAGS} \
    -o "${EXE}" \
    "${GEN_CPP_DIR}/test_rob_bookkeeping.o" \
    "${gen_objects[@]}"
fi

if [[ ! -x "${EXE}" ]]; then
  echo "error: test executable was not built: ${EXE}" >&2
  exit 2
fi

PYC_BOOT_PC=0x10000 \
PYC_BOOT_SP=0x00000000000ff000 \
PYC_MAX_CYCLES=12000 \
  "${EXE}" "${MEMH}"

echo "rob bookkeeping test: ok"
