#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

QEMU_BIN="${QEMU_BIN:-/Users/zhoubot/qemu/build-linx/qemu-system-linx64}"
ELF=""
OUT=""
MAX_SECONDS="${MAX_SECONDS:-0}"
PC_LO=""
PC_HI=""

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --elf <program.elf> --out <trace.jsonl> [options] -- [qemu args]

Options:
  --qemu-bin <path>      QEMU binary (default: ${QEMU_BIN})
  --max-seconds <int>    Optional timeout for QEMU execution (0 disables)
  --pc-lo <hex>          Optional commit-trace PC filter low bound
  --pc-hi <hex>          Optional commit-trace PC filter high bound

If no trailing qemu args are provided, defaults to:
  -nographic -monitor none -machine virt -kernel <elf>
USAGE
}

QEMU_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --pc-lo) PC_LO="$2"; shift 2 ;;
    --pc-hi) PC_HI="$2"; shift 2 ;;
    --)
      shift
      QEMU_ARGS=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${ELF}" || -z "${OUT}" ]]; then
  echo "error: --elf and --out are required" >&2
  usage
  exit 2
fi
if [[ ! -x "${QEMU_BIN}" ]]; then
  echo "error: qemu binary not found: ${QEMU_BIN}" >&2
  exit 2
fi
if [[ ! -f "${ELF}" ]]; then
  echo "error: ELF not found: ${ELF}" >&2
  exit 2
fi

if [[ ${#QEMU_ARGS[@]} -eq 0 ]]; then
  QEMU_ARGS=(-nographic -monitor none -machine virt -kernel "${ELF}")
fi

OUT_IS_FIFO=0
if [[ -p "${OUT}" ]]; then
  OUT_IS_FIFO=1
else
  mkdir -p "$(dirname -- "${OUT}")"
  rm -f "${OUT}"
fi

TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"
run_cmd=("${QEMU_BIN}" "${QEMU_ARGS[@]}")
if [[ "${MAX_SECONDS}" =~ ^[0-9]+$ ]] && [[ "${MAX_SECONDS}" -gt 0 ]] && [[ -n "${TIMEOUT_BIN}" ]]; then
  run_cmd=("${TIMEOUT_BIN}" "${MAX_SECONDS}" "${run_cmd[@]}")
fi

env_args=(
  "LINX_COMMIT_TRACE=${OUT}"
)
if [[ -n "${PC_LO}" ]]; then
  env_args+=("LINX_COMMIT_TRACE_FILTER_PC_LO=${PC_LO}")
fi
if [[ -n "${PC_HI}" ]]; then
  env_args+=("LINX_COMMIT_TRACE_FILTER_PC_HI=${PC_HI}")
fi

(
  export "${env_args[@]}"
  "${run_cmd[@]}"
)

if [[ "${OUT_IS_FIFO}" -eq 0 && ! -s "${OUT}" ]]; then
  echo "error: QEMU commit trace was not produced: ${OUT}" >&2
  exit 3
fi

echo "${OUT}"
