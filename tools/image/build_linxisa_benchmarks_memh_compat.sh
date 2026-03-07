#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"

LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"
LINXISA_ELF_DIR="${LINXISA_ELF_DIR:-${LINXISA_ROOT:+${LINXISA_ROOT}/workloads/generated/elf}}"
PYC_ROOT="${LINXCORE_PYC_ROOT:-${PYC_ROOT:-$(linxcore_resolve_pyc_root "${ROOT_DIR}" || true)}}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/tests/benchmarks/build}"
REQUIRE_REAL="${LINX_BENCH_REQUIRE_REAL:-0}"

ELF_TO_MEMH="${SCRIPT_DIR}/elf_to_memh.sh"

CORE_ELF_REAL="${LINXISA_ELF_DIR}/coremark.elf"
DHRY_ELF_REAL="${LINXISA_ELF_DIR}/dhrystone.elf"
CORE_MEMH_REAL="${OUT_DIR}/coremark_real.memh"
DHRY_MEMH_REAL="${OUT_DIR}/dhrystone_real.memh"

mkdir -p "${OUT_DIR}"

emit_real() {
  if [[ ! -x "${ELF_TO_MEMH}" ]]; then
    echo "error: missing converter: ${ELF_TO_MEMH}" >&2
    exit 2
  fi
  bash "${ELF_TO_MEMH}" "${CORE_ELF_REAL}" "${CORE_MEMH_REAL}" >/dev/null
  bash "${ELF_TO_MEMH}" "${DHRY_ELF_REAL}" "${DHRY_MEMH_REAL}" >/dev/null
  echo "${CORE_MEMH_REAL}"
  echo "${DHRY_MEMH_REAL}"
}

if [[ -f "${CORE_ELF_REAL}" && -f "${DHRY_ELF_REAL}" ]]; then
  emit_real
  exit 0
fi

if [[ "${REQUIRE_REAL}" == "1" ]]; then
  echo "error: real benchmark ELF missing (set LINX_BENCH_REQUIRE_REAL=0 to allow fallback)" >&2
  echo "  expected: ${CORE_ELF_REAL}" >&2
  echo "  expected: ${DHRY_ELF_REAL}" >&2
  exit 3
fi

# Fallback path for local smoke when full benchmark artifacts are unavailable.
core_fallback="${ROOT_DIR}/tests/benchmarks/build/coremark_compat.memh"
dhry_fallback="${ROOT_DIR}/tests/benchmarks/build/dhrystone_compat.memh"
if [[ ! -f "${core_fallback}" && -n "${PYC_ROOT}" ]]; then
  core_fallback="${PYC_ROOT}/designs/examples/linx_cpu/programs/test_csel_fixed.memh"
fi
if [[ ! -f "${dhry_fallback}" && -n "${PYC_ROOT}" ]]; then
  dhry_fallback="${PYC_ROOT}/designs/examples/linx_cpu/programs/test_or.memh"
fi

if [[ ! -f "${core_fallback}" || ! -f "${dhry_fallback}" ]]; then
  echo "error: fallback benchmark memh not found" >&2
  echo "  coremark=${core_fallback}" >&2
  echo "  dhrystone=${dhry_fallback}" >&2
  echo "hint: set LINXCORE_PYC_ROOT=... or PYC_ROOT=..." >&2
  exit 4
fi

echo "${core_fallback}"
echo "${dhry_fallback}"
