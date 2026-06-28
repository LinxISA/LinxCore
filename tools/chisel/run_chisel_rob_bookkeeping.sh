#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="reduced-rob"
REQUIRE_TOOLCHAIN="${LINX_CHISEL_REQUIRE_TOOLCHAIN:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --robid-only)
      MODE="robid"
      shift
      ;;
    --reduced-rob)
      MODE="reduced-rob"
      shift
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

python3 "${ROOT_DIR}/tools/chisel/robid_semantics_check.py" "${ROOT_DIR}"

run_chisel_if_available() {
  if [[ "${REQUIRE_TOOLCHAIN}" -eq 1 ]]; then
    "$@"
  elif (
    source "${ROOT_DIR}/tools/chisel/chisel_env.sh" >/dev/null 2>&1
  ); then
    "$@"
  else
    echo "Chisel compile/test skipped: local Java+sbt toolchain not available" >&2
  fi
}

if [[ "${MODE}" == "robid" ]]; then
  run_chisel_if_available bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only ROBID
  exit 0
fi

run_chisel_if_available bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only ROBID
run_chisel_if_available bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only CommitTrace
run_chisel_if_available bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only ReducedCommitROB
