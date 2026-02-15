#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/linxcore_top.hpp"
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

ELF=""
BOOT_PC=""
BOOT_SP="0x20000"
TRIGGER_PC=""
TERMINATE_PC=""
QEMU_BIN="/Users/zhoubot/qemu/build-linx/qemu-system-linx64"
SOCKET_PATH="${ROOT_DIR}/tests/linxcore_cosim.sock"
SNAPSHOT_PATH="${ROOT_DIR}/tests/linxcore_snapshot.bin"
RUNNER_BIN="${ROOT_DIR}/cosim/linxcore_lockstep_runner"
MAX_COMMITS="200000"
MAX_DUT_CYCLES="200000000"
DEADLOCK_CYCLES="${LINXCORE_DEADLOCK_CYCLES:-200000}"
ACCEPT_MAX_COMMITS_END=""
FORCE_MISMATCH="0"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") \
    --elf <program.elf> \
    --trigger-pc <hex> \
    --terminate-pc <hex> \
    [--boot-pc <hex>] \
    [--boot-sp <hex>] \
    [--qemu-bin <path>] \
    [--socket <path>] \
    [--snapshot <path>] \
    [--max-commits <int>] \
    [--max-dut-cycles <int>] \
    [--deadlock-cycles <int>] \
    [--accept-max-commits-end 0|1] \
    [--force-mismatch 0|1] \
    -- <qemu args>
USAGE
}

QEMU_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --boot-pc) BOOT_PC="$2"; shift 2 ;;
    --boot-sp) BOOT_SP="$2"; shift 2 ;;
    --trigger-pc) TRIGGER_PC="$2"; shift 2 ;;
    --terminate-pc) TERMINATE_PC="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --socket) SOCKET_PATH="$2"; shift 2 ;;
    --snapshot) SNAPSHOT_PATH="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    --max-dut-cycles) MAX_DUT_CYCLES="$2"; shift 2 ;;
    --deadlock-cycles) DEADLOCK_CYCLES="$2"; shift 2 ;;
    --accept-max-commits-end) ACCEPT_MAX_COMMITS_END="$2"; shift 2 ;;
    --force-mismatch) FORCE_MISMATCH="$2"; shift 2 ;;
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
      exit 1
      ;;
  esac
done

if [[ -z "${ELF}" || -z "${TRIGGER_PC}" || -z "${TERMINATE_PC}" ]]; then
  echo "error: missing required arguments" >&2
  usage
  exit 1
fi
if [[ ${#QEMU_ARGS[@]} -eq 0 ]]; then
  echo "error: missing qemu args after --" >&2
  usage
  exit 1
fi
if [[ -z "${BOOT_PC}" ]]; then
  BOOT_PC="${TRIGGER_PC}"
fi
if [[ -z "${ACCEPT_MAX_COMMITS_END}" ]]; then
  # Sampling windows commonly use a sentinel terminate PC and bounded max-commit runs.
  if [[ "${TERMINATE_PC}" == "0xffffffffffffffff" || "${TERMINATE_PC}" == "0xFFFFFFFFFFFFFFFF" ]]; then
    ACCEPT_MAX_COMMITS_END="1"
  else
    ACCEPT_MAX_COMMITS_END="0"
  fi
fi

if [[ "${BOOT_PC}" != "${TRIGGER_PC}" ]]; then
  echo "error: lockstep currently requires --boot-pc == --trigger-pc" >&2
  exit 2
fi

mkdir -p "$(dirname -- "${SOCKET_PATH}")" "$(dirname -- "${SNAPSHOT_PATH}")"

need_regen=0
if [[ ! -f "${GEN_HDR}" ]]; then
  need_regen=1
elif find "${ROOT_DIR}/src" -name '*.py' -newer "${GEN_HDR}" | grep -q .; then
  need_regen=1
fi
if [[ "${need_regen}" -ne 0 ]]; then
  echo "[cosim] generating LinxCore artifacts"
  bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi

need_runner_build=0
if [[ ! -x "${RUNNER_BIN}" ]]; then
  need_runner_build=1
elif [[ "${ROOT_DIR}/cosim/linxcore_lockstep_runner.cpp" -nt "${RUNNER_BIN}" || "${GEN_HDR}" -nt "${RUNNER_BIN}" ]]; then
  need_runner_build=1
fi
if [[ "${need_runner_build}" -ne 0 ]]; then
  echo "[cosim] building lockstep runner"
  "${CXX:-clang++}" -std=c++17 -O2 -Wall -Wextra \
    -I "${PYC_COMPAT_INCLUDE}" \
    -I "${PYC_API_INCLUDE}" \
    -I "${PYC_ROOT}/runtime" \
    -I "${PYC_ROOT}/runtime/cpp" \
    -I "${GEN_CPP_DIR}" \
    -o "${RUNNER_BIN}" \
    "${ROOT_DIR}/cosim/linxcore_lockstep_runner.cpp"
fi

echo "[cosim] building sparse memory ranges"
RANGES="$(${ROOT_DIR}/tools/qemu/build_sparse_ranges.py --elf "${ELF}" --boot-sp "${BOOT_SP}" --force-et-rel)"

cleanup() {
  local code=$?
  if [[ -n "${RUNNER_PID:-}" ]]; then
    kill "${RUNNER_PID}" >/dev/null 2>&1 || true
    wait "${RUNNER_PID}" >/dev/null 2>&1 || true
  fi
  rm -f "${SOCKET_PATH}"
  exit ${code}
}
trap cleanup EXIT INT TERM

wait_for_socket() {
  local sock_path="$1"
  for _ in $(seq 1 500); do
    if [[ -S "${sock_path}" ]]; then
      return 0
    fi
    sleep 0.01
  done
  return 1
}

echo "[cosim] starting lockstep runner"
RUNNER_ARGS=(--socket "${SOCKET_PATH}" --verbose)
if [[ "${ACCEPT_MAX_COMMITS_END}" != "0" ]]; then
  RUNNER_ARGS+=(--accept-max-commits-end)
fi
LINXCORE_BOOT_SP="${BOOT_SP}" \
LINXCORE_MAX_DUT_CYCLES="${MAX_DUT_CYCLES}" \
LINXCORE_DEADLOCK_CYCLES="${DEADLOCK_CYCLES}" \
LINXCORE_FORCE_MISMATCH="${FORCE_MISMATCH}" \
"${RUNNER_BIN}" "${RUNNER_ARGS[@]}" &
RUNNER_PID=$!
if ! wait_for_socket "${SOCKET_PATH}"; then
  echo "error: runner socket did not become ready: ${SOCKET_PATH}" >&2
  exit 3
fi

echo "[cosim] launching qemu"
LINX_COSIM_ENABLE=1 \
LINX_COSIM_TRIGGER_PC="${TRIGGER_PC}" \
LINX_COSIM_TERMINATE_PC="${TERMINATE_PC}" \
LINX_COSIM_SOCKET="${SOCKET_PATH}" \
LINX_COSIM_SNAPSHOT_PATH="${SNAPSHOT_PATH}" \
LINX_COSIM_MEM_RANGES="${RANGES}" \
LINX_COSIM_MAX_COMMITS="${MAX_COMMITS}" \
"${QEMU_BIN}" "${QEMU_ARGS[@]}"

for _ in $(seq 1 100); do
  if ! kill -0 "${RUNNER_PID}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.05
done
if kill -0 "${RUNNER_PID}" >/dev/null 2>&1; then
  echo "error: runner still active after QEMU exit (co-sim likely not engaged)" >&2
  kill "${RUNNER_PID}" >/dev/null 2>&1 || true
  wait "${RUNNER_PID}" >/dev/null 2>&1 || true
  RUNNER_PID=""
  exit 3
fi
if ! wait "${RUNNER_PID}"; then
  RUNNER_PID=""
  exit 4
fi
RUNNER_PID=""

echo "[cosim] success"
