#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
QEMU_LINX_DIR="${QEMU_LINX_DIR:-${LINX_ROOT}/emulator/qemu/target/linx}"
ISA_PROFILE="${LINXCORE_ISA_PROFILE:-}"

extract_args=(
  --qemu-linx-dir "${QEMU_LINX_DIR}"
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"
)
parity_args=(
  --qemu-linx-dir "${QEMU_LINX_DIR}"
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
)
if [[ -n "${ISA_PROFILE}" ]]; then
  extract_args+=(--isa-profile "${ISA_PROFILE}")
  parity_args+=(--allow-source-profile "${ISA_PROFILE}")
fi

python3 "${ROOT_DIR}/tests/test_opcode_catalog_forms.py"

python3 "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" "${extract_args[@]}"

python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --no-qemu-output

python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" "${parity_args[@]}"
