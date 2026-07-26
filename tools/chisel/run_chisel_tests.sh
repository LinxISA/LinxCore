#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
ONLY=""
ALL=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      ONLY="${2:-}"
      shift 2
      ;;
    --all)
      ALL=true
      shift
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
if [[ "${ALL}" == true ]]; then
  sbt --server --batch --no-colors "testOnly *"
elif [[ -n "${ONLY}" ]]; then
  sbt --server --batch --no-colors "testOnly *${ONLY}*"
else
  sbt --server --batch --no-colors test
fi
