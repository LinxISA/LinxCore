#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
QEMU_LINX_DIR="${QEMU_LINX_DIR:-${LINX_ROOT}/emulator/qemu/target/linx}"

python3 "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"

python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir "${QEMU_LINX_DIR}"

python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
