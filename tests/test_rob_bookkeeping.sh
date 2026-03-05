#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/LinxcoreTop.hpp"
CPP_MANIFEST="${GEN_CPP_DIR}/cpp_compile_manifest.json"
SRC="${ROOT_DIR}/tests/test_rob_bookkeeping.cpp"
EXE="${GEN_CPP_DIR}/test_rob_bookkeeping"
TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -Wall -Wextra}"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
RUN_TOP_CPP="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
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
MEMH="${PYC_TEST_MEMH:-${PYC_ROOT_DIR}/designs/examples/linx_cpu/programs/test_or.memh}"

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
    -I "${PYC_ROOT_DIR}/runtime" \
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

BOOT_PC="$(
python3 - <<'PY' "${MEMH}"
from pathlib import Path
import sys

p = Path(sys.argv[1])
addr = None
for tok in p.read_text().split():
    if tok.startswith("@"):
        addr = int(tok[1:], 16)
        break
if addr is None:
    raise SystemExit(f"no @addr found in memh: {p}")
print(f"0x{addr:x}")
PY
)"

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP=0x00000000000ff000 \
PYC_MAX_CYCLES=12000 \
  "${EXE}" "${MEMH}"

echo "rob bookkeeping test: ok"
