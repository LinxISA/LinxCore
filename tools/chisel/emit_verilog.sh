#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"

bash "${ROOT_DIR}/tools/chisel/build_chisel.sh"
cd "${CHISEL_DIR}"
sbt --batch --no-colors "runMain linxcore.top.Elaborate"
