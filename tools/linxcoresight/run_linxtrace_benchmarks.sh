#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

MAX_COMMITS="${1:-${PYC_LINXTRACE_MAX_COMMITS:-1000}}"

CORE_MEMH="${COREMARK_MEMH:-${ROOT_DIR}/tests/benchmarks/build/coremark_real.memh}"
DHRY_MEMH="${DHRYSTONE_MEMH:-${ROOT_DIR}/tests/benchmarks/build/dhrystone_real.memh}"

resolve_memh_pair() {
  if [[ -f "${CORE_MEMH}" && -f "${DHRY_MEMH}" ]]; then
    printf '%s\n' "${CORE_MEMH}"
    printf '%s\n' "${DHRY_MEMH}"
    return 0
  fi

  if [[ -x "${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh" ]]; then
    echo "[linxtrace] building benchmark memh images" >&2
    build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
    local core
    local dhry
    core="$(printf "%s\n" "${build_out}" | sed -n '1p')"
    dhry="$(printf "%s\n" "${build_out}" | sed -n '2p')"
    if [[ -f "${core}" && -f "${dhry}" ]]; then
      printf '%s\n' "${core}"
      printf '%s\n' "${dhry}"
      return 0
    fi
  fi

  echo "error: could not resolve CoreMark/Dhrystone memh images" >&2
  echo "hint: set COREMARK_MEMH=... and DHRYSTONE_MEMH=..." >&2
  exit 2
}

pair=()
while IFS= read -r line; do
  pair+=("${line}")
done < <(resolve_memh_pair)

core_memh="${pair[0]}"
dhry_memh="${pair[1]}"

args=()
if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  args+=("${MAX_COMMITS}")
fi

echo "[linxtrace] coremark memh: ${core_memh}" >&2
bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${core_memh}" "${args[@]}"

echo "[linxtrace] dhrystone memh: ${dhry_memh}" >&2
bash "${ROOT_DIR}/tools/linxcoresight/run_linxtrace.sh" "${dhry_memh}" "${args[@]}"

