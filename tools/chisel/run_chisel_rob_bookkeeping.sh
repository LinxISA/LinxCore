#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ROBID_ONLY=0
REQUIRE_TOOLCHAIN="${LINX_CHISEL_REQUIRE_TOOLCHAIN:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --robid-only)
      ROBID_ONLY=1
      shift
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

python3 "${ROOT_DIR}/tools/chisel/robid_semantics_check.py" "${ROOT_DIR}"

if [[ "${ROBID_ONLY}" -eq 1 ]]; then
  if [[ "${REQUIRE_TOOLCHAIN}" -eq 1 ]]; then
    bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only ROBID
  elif (
    source "${ROOT_DIR}/tools/chisel/chisel_env.sh" >/dev/null 2>&1
  ); then
    bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only ROBID
  else
    echo "ROBID Chisel compile/test skipped: local Java+sbt toolchain not available" >&2
  fi
  exit 0
fi

echo "error: full Chisel ROB bookkeeping harness is not implemented yet; use --robid-only for Packet A" >&2
exit 2
