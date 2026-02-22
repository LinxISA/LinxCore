#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

python3 "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx \
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"

python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx

python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
