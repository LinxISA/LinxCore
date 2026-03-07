#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"

MAX_COMMITS="${LINXCORE_COSIM_MAX_COMMITS:-1000}"
MAX_DUT_CYCLES="${LINXCORE_COSIM_MAX_DUT_CYCLES:-200000000}"

LLVM_READELF="${LLVM_READELF:-$(linxcore_resolve_llvm_readelf "${ROOT_DIR}" || true)}"
if [[ ! -x "${LLVM_READELF}" ]]; then
  echo "error: llvm-readelf not found (set LLVM_READELF=...)" >&2
  exit 2
fi

LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"
LINXISA_ELF_DIR="${LINXISA_ELF_DIR:-${LINXISA_ROOT:+${LINXISA_ROOT}/workloads/generated/elf}}"

CORE_ELF="${COREMARK_ELF:-${LINXISA_ELF_DIR:+${LINXISA_ELF_DIR}/coremark.elf}}"
DHRY_ELF="${DHRYSTONE_ELF:-${LINXISA_ELF_DIR:+${LINXISA_ELF_DIR}/dhrystone.elf}}"

if [[ ! -f "${CORE_ELF}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" ]]; then
  CORE_ELF="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf"
fi
if [[ ! -f "${DHRY_ELF}" && -f "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf" ]]; then
  DHRY_ELF="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf"
fi

if [[ ! -f "${CORE_ELF}" || ! -f "${DHRY_ELF}" ]]; then
  echo "error: missing benchmark ELF(s):" >&2
  echo "  coremark=${CORE_ELF}" >&2
  echo "  dhrystone=${DHRY_ELF}" >&2
  if [[ -n "${LINXISA_ROOT}" ]]; then
    echo "  linxisa_root=${LINXISA_ROOT}" >&2
  fi
  echo "hint: set LINXISA_ELF_DIR=... (expects coremark.elf and dhrystone.elf)" >&2
  echo "hint: or set COREMARK_ELF=... and DHRYSTONE_ELF=..." >&2
  exit 2
fi

elf_entry() {
  local elf="$1"
  "${LLVM_READELF}" -h "${elf}" | awk '/Entry point address:/ {print $4; exit}'
}

run_one() {
  local name="$1"
  local elf="$2"
  local entry
  entry="$(elf_entry "${elf}" || true)"
  if [[ -z "${entry}" ]]; then
    echo "error: could not read entry point for ${elf}" >&2
    exit 3
  fi

  local out_dir="${ROOT_DIR}/generated/cosim"
  mkdir -p "${out_dir}"
  local sock="${out_dir}/${name}.sock"
  local snap="${out_dir}/${name}.snapshot.bin"
  local log="${out_dir}/${name}.log"

  echo "[cosim] ${name} elf=${elf} entry=${entry} max_commits=${MAX_COMMITS}" >&2
  bash "${ROOT_DIR}/tools/qemu/run_cosim_lockstep.sh" \
    --elf "${elf}" \
    --boot-pc "${entry}" \
    --trigger-pc "${entry}" \
    --terminate-pc 0xffffffffffffffff \
    --max-commits "${MAX_COMMITS}" \
    --max-dut-cycles "${MAX_DUT_CYCLES}" \
    --socket "${sock}" \
    --snapshot "${snap}" \
    -- \
    -nographic -monitor none -machine virt -kernel "${elf}" > "${log}" 2>&1

  echo "[cosim] ok: ${name} log=${log}" >&2
}

run_one "coremark" "${CORE_ELF}"
run_one "dhrystone" "${DHRY_ELF}"
