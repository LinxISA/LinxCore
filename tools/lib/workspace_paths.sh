#!/usr/bin/env bash

linxcore_resolve_linxisa_root() {
  local root_dir="${1:-}"
  local super=""

  if [[ -n "${LINXISA_ROOT:-}" && -d "${LINXISA_ROOT}" ]]; then
    printf '%s\n' "${LINXISA_ROOT}"
    return 0
  fi

  if [[ -n "${root_dir}" ]]; then
    super="$(git -C "${root_dir}" rev-parse --show-superproject-working-tree 2>/dev/null || true)"
    if [[ -n "${super}" && -d "${super}/emulator/qemu" && -d "${super}/tools/pyCircuit" ]]; then
      printf '%s\n' "${super}"
      return 0
    fi

    if [[ -d "${root_dir}/../../emulator/qemu" && -d "${root_dir}/../../tools/pyCircuit" ]]; then
      (cd "${root_dir}/../.." && pwd)
      return 0
    fi
  fi

  if [[ -d "/Users/zhoubot/linx-isa/emulator/qemu" && -d "/Users/zhoubot/linx-isa/tools/pyCircuit" ]]; then
    printf '%s\n' "/Users/zhoubot/linx-isa"
    return 0
  fi

  return 1
}

linxcore_resolve_qemu_linx_dir() {
  local root_dir="${1:-}"
  local qemu_root="${LINXCORE_QEMU_ROOT:-}"
  local linxisa_root=""

  if [[ -n "${QEMU_LINX_DIR:-}" && -d "${QEMU_LINX_DIR}" ]]; then
    printf '%s\n' "${QEMU_LINX_DIR}"
    return 0
  fi

  if [[ -n "${qemu_root}" && -d "${qemu_root}/target/linx" ]]; then
    printf '%s\n' "${qemu_root}/target/linx"
    return 0
  fi

  linxisa_root="$(linxcore_resolve_linxisa_root "${root_dir}" || true)"
  if [[ -n "${linxisa_root}" && -d "${linxisa_root}/emulator/qemu/target/linx" ]]; then
    printf '%s\n' "${linxisa_root}/emulator/qemu/target/linx"
    return 0
  fi

  return 1
}

linxcore_resolve_pyc_root() {
  local root_dir="${1:-}"
  local linxisa_root=""

  if [[ -n "${LINXCORE_PYC_ROOT:-}" && -d "${LINXCORE_PYC_ROOT}" ]]; then
    printf '%s\n' "${LINXCORE_PYC_ROOT}"
    return 0
  fi

  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    printf '%s\n' "${PYC_ROOT}"
    return 0
  fi

  linxisa_root="$(linxcore_resolve_linxisa_root "${root_dir}" || true)"
  if [[ -n "${linxisa_root}" && -d "${linxisa_root}/tools/pyCircuit" ]]; then
    printf '%s\n' "${linxisa_root}/tools/pyCircuit"
    return 0
  fi

  return 1
}
