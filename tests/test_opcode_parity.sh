#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ISA_PROFILE="${LINXCORE_ISA_PROFILE:-v0.58}"

if [[ -n "${LINXISA_ROOT:-}" ]]; then
  :
elif [[ -n "${LINXISA_DIR:-}" ]]; then
  LINXISA_ROOT="${LINXISA_DIR}"
else
  SUPERPROJECT_CANDIDATE="$(cd -- "${ROOT_DIR}/../.." && pwd)"
  if [[ -d "${SUPERPROJECT_CANDIDATE}/isa/${ISA_PROFILE}" ]]; then
    LINXISA_ROOT="${SUPERPROJECT_CANDIDATE}"
  else
    echo "error: LinxISA root was not found; set LINXISA_ROOT for a standalone LinxCore checkout" >&2
    exit 1
  fi
fi

if [[ ! -d "${LINXISA_ROOT}/isa/${ISA_PROFILE}" ]]; then
  echo "error: ${LINXISA_ROOT}/isa/${ISA_PROFILE} is missing" >&2
  exit 1
fi

QEMU_LINX_DIR="${QEMU_LINX_DIR:-${LINXISA_ROOT}/emulator/qemu/target/linx}"

extract_args=(
  --linxisa-root "${LINXISA_ROOT}"
  --isa-profile "${ISA_PROFILE}"
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"
)
parity_args=(
  --linxisa-root "${LINXISA_ROOT}"
  --isa-profile "${ISA_PROFILE}"
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
)

python3 "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" "${extract_args[@]}"

python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --no-qemu-output

python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" "${parity_args[@]}"

python3 "${ROOT_DIR}/tests/test_opcode_catalog_forms.py"

if [[ "${LINXCORE_CHECK_QEMU_CONSUMER:-0}" == "1" && -d "${QEMU_LINX_DIR}" ]]; then
  python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
    --qemu-linx-dir "${QEMU_LINX_DIR}" \
    --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
elif [[ "${LINXCORE_CHECK_QEMU_CONSUMER:-0}" == "1" ]]; then
  echo "error: requested QEMU consumer parity but decode tree is missing: ${QEMU_LINX_DIR}" >&2
  exit 1
else
  echo "info: QEMU consumer parity skipped; set LINXCORE_CHECK_QEMU_CONSUMER=1 to enable" >&2
fi
