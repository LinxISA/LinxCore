#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
ONLY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      ONLY="${2:-}"
      shift 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
if [[ -n "${ONLY}" ]]; then
  sbt --batch --no-colors "testOnly *${ONLY}*"
else
  sbt --batch --no-colors test
fi
