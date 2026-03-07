#!/usr/bin/env bash

# Shared workspace path resolution for standalone LinxCore checkouts and
# linx-isa superproject submodule checkouts.

linxcore_realpath_dir() {
  local path="$1"
  if [[ -d "${path}" ]]; then
    (cd -- "${path}" && pwd)
  fi
}

linxcore_git_superproject_root() {
  local repo_root="$1"
  git -C "${repo_root}" rev-parse --show-superproject-working-tree 2>/dev/null || true
}

linxcore_resolve_linxisa_root() {
  local repo_root="$1"
  local cand=""
  local super=""

  for cand in "${LINXISA_ROOT:-}" "${LINXISA_DIR:-}"; do
    if [[ -n "${cand}" && -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  super="$(linxcore_git_superproject_root "${repo_root}")"
  if [[ -n "${super}" && -d "${super}" ]]; then
    linxcore_realpath_dir "${super}"
    return 0
  fi

  for cand in \
    "${repo_root}/../linx-isa" \
    "${repo_root}/../../linx-isa" \
    "${HOME}/linx-isa" \
    "/Users/zhoubot/linx-isa"
  do
    if [[ -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  return 1
}

linxcore_resolve_pyc_root() {
  local repo_root="$1"
  local cand=""
  local super=""
  local linxisa_root=""

  for cand in "${LINXCORE_PYC_ROOT:-}" "${PYC_ROOT:-}"; do
    if [[ -n "${cand}" && -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  super="$(linxcore_git_superproject_root "${repo_root}")"
  if [[ -n "${super}" && -d "${super}/tools/pyCircuit" ]]; then
    linxcore_realpath_dir "${super}/tools/pyCircuit"
    return 0
  fi

  linxisa_root="$(linxcore_resolve_linxisa_root "${repo_root}" || true)"
  if [[ -n "${linxisa_root}" && -d "${linxisa_root}/tools/pyCircuit" ]]; then
    linxcore_realpath_dir "${linxisa_root}/tools/pyCircuit"
    return 0
  fi

  for cand in \
    "${repo_root}/../pyCircuit" \
    "${repo_root}/../../tools/pyCircuit" \
    "${HOME}/pyCircuit" \
    "/Users/zhoubot/pyCircuit"
  do
    if [[ -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  return 1
}

linxcore_resolve_qemu_linx_dir() {
  local repo_root="$1"
  local cand=""
  local linxisa_root=""

  for cand in "${QEMU_LINX_DIR:-}"; do
    if [[ -n "${cand}" && -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  if [[ -n "${LINXCORE_QEMU_ROOT:-}" && -d "${LINXCORE_QEMU_ROOT}/target/linx" ]]; then
    linxcore_realpath_dir "${LINXCORE_QEMU_ROOT}/target/linx"
    return 0
  fi

  linxisa_root="$(linxcore_resolve_linxisa_root "${repo_root}" || true)"
  if [[ -n "${linxisa_root}" && -d "${linxisa_root}/emulator/qemu/target/linx" ]]; then
    linxcore_realpath_dir "${linxisa_root}/emulator/qemu/target/linx"
    return 0
  fi

  for cand in \
    "${repo_root}/../qemu/target/linx" \
    "${repo_root}/../../emulator/qemu/target/linx" \
    "${HOME}/qemu/target/linx" \
    "/Users/zhoubot/qemu/target/linx"
  do
    if [[ -d "${cand}" ]]; then
      linxcore_realpath_dir "${cand}"
      return 0
    fi
  done

  return 1
}

linxcore_resolve_qemu_bin() {
  local repo_root="$1"
  local cand=""
  local linxisa_root=""

  for cand in "${QEMU_BIN:-}"; do
    if [[ -n "${cand}" && -x "${cand}" ]]; then
      printf '%s\n' "${cand}"
      return 0
    fi
  done

  linxisa_root="$(linxcore_resolve_linxisa_root "${repo_root}" || true)"
  if [[ -n "${linxisa_root}" && -x "${linxisa_root}/emulator/qemu/build/qemu-system-linx64" ]]; then
    printf '%s\n' "${linxisa_root}/emulator/qemu/build/qemu-system-linx64"
    return 0
  fi

  for cand in \
    "${repo_root}/../qemu/build/qemu-system-linx64" \
    "${HOME}/linx-isa/emulator/qemu/build/qemu-system-linx64"
  do
    if [[ -x "${cand}" ]]; then
      printf '%s\n' "${cand}"
      return 0
    fi
  done

  return 1
}

linxcore_resolve_llvm_readelf() {
  local repo_root="$1"
  local cand=""

  for cand in "${LLVM_READELF:-}"; do
    if [[ -n "${cand}" && -x "${cand}" ]]; then
      printf '%s\n' "${cand}"
      return 0
    fi
  done

  for cand in \
    "${HOME}/llvm-project/build-linxisa-clang/bin/llvm-readelf" \
    "/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf"
  do
    if [[ -x "${cand}" ]]; then
      printf '%s\n' "${cand}"
      return 0
    fi
  done

  cand="$(command -v llvm-readelf 2>/dev/null || true)"
  if [[ -n "${cand}" && -x "${cand}" ]]; then
    printf '%s\n' "${cand}"
    return 0
  fi

  cand="$(command -v readelf 2>/dev/null || true)"
  if [[ -n "${cand}" && -x "${cand}" ]]; then
    printf '%s\n' "${cand}"
    return 0
  fi

  return 1
}
