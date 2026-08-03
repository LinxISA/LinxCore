#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
ONLY=()
ALL=false

HEARTBEAT_SECONDS="${LINX_CHISEL_HEARTBEAT_SECONDS:-30}"
STALL_SECONDS="${LINX_CHISEL_STALL_SECONDS:-600}"
WALL_SECONDS="${LINX_CHISEL_WALL_SECONDS:-0}"
TEST_JOBS="${LINX_CHISEL_TEST_JOBS:-2}"
ARTIFACT_BUDGET_BYTES="${LINX_CHISEL_ARTIFACT_BUDGET_BYTES:-0}"
ARTIFACT_ROOT="${LINX_CHISEL_ARTIFACT_ROOT:-${CHISEL_DIR}/build}"
LOW_CPU_PERCENT="${LINX_CHISEL_LOW_CPU_PERCENT:-1.0}"

require_value() {
  local option="$1"
  local value="${2:-}"
  if [[ -z "${value}" ]]; then
    echo "error: ${option} requires a value" >&2
    exit 2
  fi
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: ${name} must be a positive integer" >&2
    exit 2
  fi
}

require_non_negative_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "error: ${name} must be a non-negative integer" >&2
    exit 2
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      require_value "$1" "${2:-}"
      if [[ ! "${2}" =~ ^[A-Za-z0-9_.$]+$ ]]; then
        echo "error: --only selector contains unsupported characters: ${2}" >&2
        exit 2
      fi
      ONLY+=("${2}")
      shift 2
      ;;
    --all)
      ALL=true
      shift
      ;;
    --heartbeat-seconds)
      require_value "$1" "${2:-}"
      HEARTBEAT_SECONDS="${2}"
      shift 2
      ;;
    --stall-seconds)
      require_value "$1" "${2:-}"
      STALL_SECONDS="${2}"
      shift 2
      ;;
    --wall-seconds)
      require_value "$1" "${2:-}"
      WALL_SECONDS="${2}"
      shift 2
      ;;
    --jobs)
      require_value "$1" "${2:-}"
      TEST_JOBS="${2}"
      shift 2
      ;;
    --artifact-budget-bytes)
      require_value "$1" "${2:-}"
      ARTIFACT_BUDGET_BYTES="${2}"
      shift 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${ALL}" == true && "${#ONLY[@]}" -ne 0 ]]; then
  echo "error: --all and --only are mutually exclusive" >&2
  exit 2
fi

require_positive_integer "heartbeat seconds" "${HEARTBEAT_SECONDS}"
require_non_negative_integer "stall seconds" "${STALL_SECONDS}"
require_non_negative_integer "wall seconds" "${WALL_SECONDS}"
require_positive_integer "test jobs" "${TEST_JOBS}"
require_non_negative_integer "artifact budget bytes" "${ARTIFACT_BUDGET_BYTES}"

export LINX_CHISEL_TEST_JOBS="${TEST_JOBS}"
# ROOT_DIR is resolved from this script at runtime.
# shellcheck disable=SC1091
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

if [[ "${ALL}" == true ]]; then
  TEST_COMMAND="testOnly *"
  SELECTOR_SUMMARY="all"
elif [[ "${#ONLY[@]}" -ne 0 ]]; then
  TEST_COMMAND="testOnly"
  SELECTOR_SUMMARY=""
  for selector in "${ONLY[@]}"; do
    TEST_COMMAND+=" *${selector}*"
    if [[ -n "${SELECTOR_SUMMARY}" ]]; then
      SELECTOR_SUMMARY+=","
    fi
    SELECTOR_SUMMARY+="${selector}"
  done
else
  TEST_COMMAND="test"
  SELECTOR_SUMMARY="default"
fi

SBT_COMMAND=(
  sbt
  --server
  --batch
  --no-colors
  --mem
  "${LINX_CHISEL_SBT_MEM_MB}"
  "${TEST_COMMAND}"
)

SUPERVISOR_COMMAND=(
  python3
  "${ROOT_DIR}/tools/chisel/chisel_test_supervisor.py"
  --selector
  "${SELECTOR_SUMMARY}"
  --jobs
  "${TEST_JOBS}"
  --heartbeat-seconds
  "${HEARTBEAT_SECONDS}"
  --stall-seconds
  "${STALL_SECONDS}"
  --wall-seconds
  "${WALL_SECONDS}"
  --low-cpu-percent
  "${LOW_CPU_PERCENT}"
  --artifact-root
  "${ARTIFACT_ROOT}"
  --artifact-budget-bytes
  "${ARTIFACT_BUDGET_BYTES}"
)

if [[ -n "${LINX_CHISEL_LOG_PATH:-}" ]]; then
  SUPERVISOR_COMMAND+=(--log "${LINX_CHISEL_LOG_PATH}")
fi

cd "${CHISEL_DIR}"
exec "${SUPERVISOR_COMMAND[@]}" -- "${SBT_COMMAND[@]}"
