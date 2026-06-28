#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
sbt --batch --no-colors Test/compile
