#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/tests/benchmarks_latest_llvm_musl}"
MAX_COMMITS="${MAX_COMMITS:-1000}"
TB_MAX_CYCLES="${TB_MAX_CYCLES:-50000000}"
XCHECK_MODE="${XCHECK_MODE:-failfast}"
SIMPOINT_INTERVAL="${SIMPOINT_INTERVAL:-1000}"
QEMU_MAX_SECONDS="${QEMU_MAX_SECONDS:-0}"

BUILD_SCRIPT="${ROOT_DIR}/tools/image/build_latest_llvm_musl_images.sh"
XCHECK_SCRIPT="${ROOT_DIR}/tools/trace/run_crosscheck_fifo.sh"
SIMPOINT_PICKER="${ROOT_DIR}/tools/trace/select_simpoint_window.py"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [options]

Options:
  --out-dir <dir>           Root output (default: ${OUT_DIR})
  --max-commits <int>       Cross-check commit window (default: ${MAX_COMMITS})
  --tb-max-cycles <int>     LinxCore cycle cap for window run (default: ${TB_MAX_CYCLES})
  --xcheck-mode <mode>      diagnostic|failfast (default: ${XCHECK_MODE})
  --simpoint-interval <n>   SimPoint-style interval size (default: ${SIMPOINT_INTERVAL})
  --qemu-max-seconds <int>  Optional QEMU timeout for trace runner (default: ${QEMU_MAX_SECONDS})
  --build-arg <arg>         Forward one argument to build script (repeatable)
USAGE
}

BUILD_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    --tb-max-cycles) TB_MAX_CYCLES="$2"; shift 2 ;;
    --xcheck-mode) XCHECK_MODE="$2"; shift 2 ;;
    --simpoint-interval) SIMPOINT_INTERVAL="$2"; shift 2 ;;
    --qemu-max-seconds) QEMU_MAX_SECONDS="$2"; shift 2 ;;
    --build-arg) BUILD_ARGS+=("$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

mkdir -p "${OUT_DIR}"
VERIFY_DIR="${OUT_DIR}/verify"
CORE_DIR="${VERIFY_DIR}/coremark"
DHRY_DIR="${VERIFY_DIR}/dhrystone"
REPORT_DIR="${OUT_DIR}/report"
mkdir -p "${CORE_DIR}" "${DHRY_DIR}" "${REPORT_DIR}"

if [[ ! -x "${BUILD_SCRIPT}" ]]; then
  echo "error: missing build script: ${BUILD_SCRIPT}" >&2
  exit 2
fi
if [[ ! -x "${XCHECK_SCRIPT}" ]]; then
  echo "error: missing cross-check script: ${XCHECK_SCRIPT}" >&2
  exit 2
fi
if [[ ! -x "${SIMPOINT_PICKER}" ]]; then
  echo "error: missing simpoint picker: ${SIMPOINT_PICKER}" >&2
  exit 2
fi

if [[ ${#BUILD_ARGS[@]} -gt 0 ]]; then
  build_out="$(
    OUT_DIR="${OUT_DIR}" \
    bash "${BUILD_SCRIPT}" "${BUILD_ARGS[@]}"
  )"
else
  build_out="$(
    OUT_DIR="${OUT_DIR}" \
    bash "${BUILD_SCRIPT}"
  )"
fi
core_elf="$(printf "%s\n" "${build_out}" | awk -F= '/^core_elf=/{print substr($0,index($0,"=")+1)}')"
dhry_elf="$(printf "%s\n" "${build_out}" | awk -F= '/^dhry_elf=/{print substr($0,index($0,"=")+1)}')"
core_memh="$(printf "%s\n" "${build_out}" | awk -F= '/^core_memh=/{print substr($0,index($0,"=")+1)}')"
dhry_memh="$(printf "%s\n" "${build_out}" | awk -F= '/^dhry_memh=/{print substr($0,index($0,"=")+1)}')"

if [[ -z "${core_elf}" || -z "${dhry_elf}" || -z "${core_memh}" || -z "${dhry_memh}" ]]; then
  echo "error: failed to parse build outputs" >&2
  printf "%s\n" "${build_out}" >&2
  exit 3
fi

run_verify() {
  local name="$1"
  local elf="$2"
  local memh="$3"
  local out_dir="$4"
  local rc=0
  set +e
  bash "${XCHECK_SCRIPT}" \
    --elf "${elf}" \
    --memh "${memh}" \
    --out-dir "${out_dir}" \
    --mode "${XCHECK_MODE}" \
    --max-commits "${MAX_COMMITS}" \
    --tb-max-cycles "${TB_MAX_CYCLES}" \
    --qemu-max-seconds "${QEMU_MAX_SECONDS}" > "${out_dir}/xcheck.log" 2>&1
  rc=$?
  set -e
  local status="PASS"
  if [[ "${rc}" -ne 0 ]]; then
    status="FAIL(${rc})"
  fi

  local qtrace="${out_dir}/qemu_trace.jsonl"
  local slice_json="${out_dir}/simpoint_slice.json"
  local slice_md="${out_dir}/simpoint_slice.md"
  if [[ -f "${qtrace}" ]]; then
    python3 "${SIMPOINT_PICKER}" \
      --trace "${qtrace}" \
      --interval "${SIMPOINT_INTERVAL}" \
      --pick-count 1 \
      --max-commits "${MAX_COMMITS}" \
      --out "${slice_json}" \
      --report "${slice_md}" > "${out_dir}/simpoint.log" 2>&1 || true
  fi

  echo "${name}_status=${status}"
  echo "${name}_xcheck_log=${out_dir}/xcheck.log"
  echo "${name}_slice_json=${slice_json}"
}

verify_out="$(
  run_verify "coremark" "${core_elf}" "${core_memh}" "${CORE_DIR}"
  run_verify "dhrystone" "${dhry_elf}" "${dhry_memh}" "${DHRY_DIR}"
)"

core_status="$(printf "%s\n" "${verify_out}" | awk -F= '/^coremark_status=/{print $2}')"
dhry_status="$(printf "%s\n" "${verify_out}" | awk -F= '/^dhrystone_status=/{print $2}')"

FINAL_REPORT="${REPORT_DIR}/latest_llvm_musl_verify.md"
cat > "${FINAL_REPORT}" <<MD
# Latest LLVM+musl Bench Build + QEMU/LinxCore Verification

## Build

${build_out}

## Verify Window

- mode: \`${XCHECK_MODE}\`
- max_commits: \`${MAX_COMMITS}\`
- tb_max_cycles: \`${TB_MAX_CYCLES}\`
- simpoint_interval: \`${SIMPOINT_INTERVAL}\`

## Status

- coremark: \`${core_status}\`
- dhrystone: \`${dhry_status}\`

## Paths

- coremark verify dir: \`${CORE_DIR}\`
- dhrystone verify dir: \`${DHRY_DIR}\`
- full report: \`${FINAL_REPORT}\`
MD

echo "${build_out}"
printf "%s\n" "${verify_out}"
echo "final_report=${FINAL_REPORT}"

if [[ "${core_status}" != "PASS" || "${dhry_status}" != "PASS" ]]; then
  exit 1
fi
