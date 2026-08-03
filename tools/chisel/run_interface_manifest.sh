#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${ROOT_DIR}/chisel"
sbt --server --batch --no-colors --mem 4096 \
  "runMain linxcore.top.interface.EmitInterfaceManifest --json ${ROOT_DIR}/docs/chisel/generated/top-interface-manifest.json --markdown ${ROOT_DIR}/docs/chisel/generated/top-interface-manifest.md"
