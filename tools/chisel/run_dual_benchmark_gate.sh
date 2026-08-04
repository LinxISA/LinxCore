#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_ROOT="${ROOT_DIR}/generated/top-mainline/dual"
COREMARK_ELF="${ROOT_DIR}/../../workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/coremark/coremark.elf"
DHRYSTONE_ELF="${ROOT_DIR}/../../workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/dhrystone/dhrystone.elf"
MAX_CYCLES="3000000"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-root) BUILD_ROOT="${2:-}"; shift 2 ;;
    --coremark-elf) COREMARK_ELF="${2:-}"; shift 2 ;;
    --dhrystone-elf) DHRYSTONE_ELF="${2:-}"; shift 2 ;;
    --max-cycles) MAX_CYCLES="${2:-}"; shift 2 ;;
    *) echo "error: unknown argument: $1" >&2; exit 2 ;;
  esac
done

for benchmark in coremark dhrystone; do
  if [[ "${benchmark}" == coremark ]]; then elf="${COREMARK_ELF}"; else elf="${DHRYSTONE_ELF}"; fi
  bash "${ROOT_DIR}/tools/chisel/run_top_natural.sh" --elf "${elf}" \
    --build-dir "${BUILD_ROOT}/${benchmark}" --max-cycles "${MAX_CYCLES}"
done
