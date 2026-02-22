#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINXISA_DIR="${LINXISA_DIR:-${HOME}/linx-isa}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/tests/benchmarks_latest_llvm_musl}"
BUILD_DIR="${BUILD_DIR:-${OUT_DIR}/elf}"
MEMH_DIR="${MEMH_DIR:-${OUT_DIR}/memh}"
REPORT_DIR="${REPORT_DIR:-${OUT_DIR}/report}"

TARGET="${TARGET:-linx64-unknown-linux-musl}"
OPT="${OPT:--O2}"
CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
IMAGE_BASE="${IMAGE_BASE:-0x10000}"
COREMARK_PORT="${COREMARK_PORT:-simple}"

SYSROOT="${SYSROOT:-${LINXISA_DIR}/out/libc/musl/install/phase-b}"
RUNTIME_LIB="${RUNTIME_LIB:-${LINXISA_DIR}/out/libc/musl/runtime/phase-b/liblinx_builtin_rt.a}"
LLVM_CLANG="${LLVM_CLANG:-}"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [options]

Options:
  --out-dir <dir>       Root output directory (default: ${OUT_DIR})
  --opt <flag>          Optimization flag (default: ${OPT})
  --core-iters <int>    CoreMark iterations (default: ${CORE_ITERATIONS})
  --dhry-runs <int>     Dhrystone runs (default: ${DHRY_RUNS})
  --clang <path>        Explicit clang path (default: auto-detect latest)
  --sysroot <path>      musl sysroot (default: ${SYSROOT})
  --runtime-lib <path>  liblinx_builtin_rt.a (default: ${RUNTIME_LIB})
  --image-base <hex>    Link image base (default: ${IMAGE_BASE})
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out-dir) OUT_DIR="$2"; BUILD_DIR="${OUT_DIR}/elf"; MEMH_DIR="${OUT_DIR}/memh"; REPORT_DIR="${OUT_DIR}/report"; shift 2 ;;
    --opt) OPT="$2"; shift 2 ;;
    --core-iters) CORE_ITERATIONS="$2"; shift 2 ;;
    --dhry-runs) DHRY_RUNS="$2"; shift 2 ;;
    --clang) LLVM_CLANG="$2"; shift 2 ;;
    --sysroot) SYSROOT="$2"; shift 2 ;;
    --runtime-lib) RUNTIME_LIB="$2"; shift 2 ;;
    --image-base) IMAGE_BASE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

find_latest_clang() {
  if [[ -n "${LLVM_CLANG}" ]]; then
    if [[ ! -x "${LLVM_CLANG}" ]]; then
      echo "error: clang not executable: ${LLVM_CLANG}" >&2
      exit 2
    fi
    echo "${LLVM_CLANG}"
    return 0
  fi
  local cands=()
  while IFS= read -r p; do
    if [[ -x "${p}" ]]; then
      cands+=("${p}")
    fi
  done < <(find /Users/zhoubot/llvm-project -maxdepth 4 \( -type f -o -type l \) -path "*/bin/clang" 2>/dev/null | sort)
  if [[ ${#cands[@]} -eq 0 ]]; then
    echo "error: no clang candidates found under /Users/zhoubot/llvm-project" >&2
    exit 2
  fi
  local newest=""
  local newest_ts=0
  for p in "${cands[@]}"; do
    local ts
    ts="$(stat -f %m "${p}" 2>/dev/null || echo 0)"
    if [[ "${ts}" -gt "${newest_ts}" ]]; then
      newest_ts="${ts}"
      newest="${p}"
    fi
  done
  if [[ -z "${newest}" ]]; then
    echo "error: failed to pick latest clang candidate" >&2
    exit 2
  fi
  echo "${newest}"
}

CLANG_BIN="$(find_latest_clang)"
RUN_BENCH_PY="${LINXISA_DIR}/workloads/run_benchmarks.py"
ELF_TO_MEMH="${ROOT_DIR}/tools/image/elf_to_memh.sh"

if [[ ! -f "${RUN_BENCH_PY}" ]]; then
  echo "error: missing benchmark builder: ${RUN_BENCH_PY}" >&2
  exit 2
fi
if [[ ! -x "${CLANG_BIN}" ]]; then
  echo "error: missing clang: ${CLANG_BIN}" >&2
  exit 2
fi
if [[ ! -d "${SYSROOT}" ]]; then
  echo "error: missing musl sysroot: ${SYSROOT}" >&2
  exit 2
fi
if [[ ! -f "${RUNTIME_LIB}" ]]; then
  echo "error: missing runtime lib: ${RUNTIME_LIB}" >&2
  exit 2
fi
if [[ ! -x "${ELF_TO_MEMH}" ]]; then
  echo "error: missing elf_to_memh converter: ${ELF_TO_MEMH}" >&2
  exit 2
fi

mkdir -p "${BUILD_DIR}" "${MEMH_DIR}" "${REPORT_DIR}"

python3 "${RUN_BENCH_PY}" \
  --cc "${CLANG_BIN}" \
  --target "${TARGET}" \
  --sysroot "${SYSROOT}" \
  --link-mode musl-static \
  --runtime-lib "${RUNTIME_LIB}" \
  --coremark-port "${COREMARK_PORT}" \
  --coremark-iterations "${CORE_ITERATIONS}" \
  --dhrystone-runs "${DHRY_RUNS}" \
  --opt="${OPT}" \
  --image-base "${IMAGE_BASE}" \
  --out-dir "${BUILD_DIR}"

CORE_ELF="${BUILD_DIR}/coremark/coremark.elf"
DHRY_ELF="${BUILD_DIR}/dhrystone/dhrystone.elf"
CORE_MEMH="${MEMH_DIR}/coremark_latest_llvm_musl.memh"
DHRY_MEMH="${MEMH_DIR}/dhrystone_latest_llvm_musl.memh"

if [[ ! -f "${CORE_ELF}" || ! -f "${DHRY_ELF}" ]]; then
  echo "error: benchmark ELF generation failed" >&2
  exit 3
fi

bash "${ELF_TO_MEMH}" "${CORE_ELF}" "${CORE_MEMH}" >/dev/null
bash "${ELF_TO_MEMH}" "${DHRY_ELF}" "${DHRY_MEMH}" >/dev/null

SUMMARY="${REPORT_DIR}/latest_llvm_musl_build.md"
cat > "${SUMMARY}" <<MD
# Latest LLVM+musl Benchmark Image Build

- clang: \`${CLANG_BIN}\`
- target: \`${TARGET}\`
- sysroot: \`${SYSROOT}\`
- runtime_lib: \`${RUNTIME_LIB}\`
- opt: \`${OPT}\`
- image_base: \`${IMAGE_BASE}\`
- coremark_iterations: \`${CORE_ITERATIONS}\`
- dhrystone_runs: \`${DHRY_RUNS}\`

## Outputs

- coremark_elf: \`${CORE_ELF}\`
- dhrystone_elf: \`${DHRY_ELF}\`
- coremark_memh: \`${CORE_MEMH}\`
- dhrystone_memh: \`${DHRY_MEMH}\`
MD

echo "clang=${CLANG_BIN}"
echo "core_elf=${CORE_ELF}"
echo "dhry_elf=${DHRY_ELF}"
echo "core_memh=${CORE_MEMH}"
echo "dhry_memh=${DHRY_MEMH}"
echo "summary=${SUMMARY}"
