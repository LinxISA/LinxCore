#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"

QEMU_LINX_DIR="${QEMU_LINX_DIR:-$(linxcore_resolve_qemu_linx_dir "${ROOT_DIR}" || true)}"
if [[ -z "${QEMU_LINX_DIR}" || ! -d "${QEMU_LINX_DIR}" ]]; then
  echo "error: QEMU Linx decode tree not found" >&2
  echo "hint: set QEMU_LINX_DIR=... or LINXISA_ROOT=..." >&2
  exit 2
fi

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
