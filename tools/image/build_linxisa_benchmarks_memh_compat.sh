#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

LINXISA_DIR="${LINXISA_DIR:-${HOME}/linxisa}"
LLVM_BIN="${LLVM_LINXISA_BIN:-${HOME}/llvm-project/build-linxisa-clang/bin}"
CLANG="${LLVM_BIN}/clang"
LD="${LLVM_BIN}/ld.lld"
OBJCOPY="${LLVM_BIN}/llvm-objcopy"
LINX_LD_SCRIPT="${LINX_LD_SCRIPT:-${HOME}/linx-libc/linx.ld}"
LINX_TARGET="${LINX_TARGET:-linx64-linx-none-elf}"
LINX_INCLUDE_LIBM="${LINX_INCLUDE_LIBM:-0}"

OUT_DIR="${OUT_DIR:-${ROOT_DIR}/tests/benchmarks/build}"
BUILD_DIR="${OUT_DIR}/obj"
FALLBACK_BENCH_DIR="${ROOT_DIR}/tests/benchmarks/build"

CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
LINX_HEAP_SIZE="${LINX_HEAP_SIZE:-65536}"

can_build=1
for req in \
  "${CLANG}" \
  "${LD}" \
  "${OBJCOPY}" \
  "${LINX_LD_SCRIPT}" \
  "${SCRIPT_DIR}/elf_to_memh.sh"
do
  if [[ ! -e "${req}" ]]; then
    can_build=0
  fi
done
if [[ ! -d "${LINXISA_DIR}/workloads/benchmarks" || ! -d "${LINXISA_DIR}/toolchain/libc" ]]; then
  can_build=0
fi

if [[ "${can_build}" == "0" ]]; then
  core_fallback="${FALLBACK_BENCH_DIR}/coremark_full.memh"
  dhry_fallback="${FALLBACK_BENCH_DIR}/dhrystone_full.memh"
  if [[ ! -f "${core_fallback}" ]]; then
    core_fallback="${PYC_ROOT:-/Users/zhoubot/pyCircuit}/designs/examples/linx_cpu/programs/test_csel_fixed.memh"
  fi
  if [[ ! -f "${dhry_fallback}" ]]; then
    dhry_fallback="${PYC_ROOT:-/Users/zhoubot/pyCircuit}/designs/examples/linx_cpu/programs/test_or.memh"
  fi
  if [[ -f "${core_fallback}" && -f "${dhry_fallback}" ]]; then
    echo "${core_fallback}"
    echo "${dhry_fallback}"
    exit 0
  fi
  echo "error: missing benchmarks/toolchain and fallback memh not found" >&2
  exit 1
fi

mkdir -p "${BUILD_DIR}" "${OUT_DIR}"

BENCH_DIR="${LINXISA_DIR}/workloads/benchmarks"
LIBC_DIR="${LINXISA_DIR}/toolchain/libc"
LIBC_INCLUDE="${LIBC_DIR}/include"
LIBC_SRC="${LIBC_DIR}/src"

COMMON_CFLAGS=(
  -target "${LINX_TARGET}"
  -O2
  -ffreestanding
  -fno-builtin
  -fno-stack-protector
  -fno-asynchronous-unwind-tables
  -fno-unwind-tables
  -fno-exceptions
  -fno-jump-tables
  -fno-vectorize
  -fno-slp-vectorize
  -mllvm -linx-simt-autovec=0
  -nostdlib
  "-I${LIBC_INCLUDE}"
  "-I${BENCH_DIR}"
  "-DLINX_HEAP_SIZE=${LINX_HEAP_SIZE}"
)

COREMARK_FLAGS_STR='-DFLAGS_STR="-O2 -ffreestanding -nostdlib -mllvm -linx-simt-autovec=0"'

cc() {
  local src="$1"
  local obj="$2"
  shift 2
  "${CLANG}" "${COMMON_CFLAGS[@]}" "$@" -c "${src}" -o "${obj}"
}

link_elf() {
  local out_elf="$1"
  shift
  "${LD}" -m elf64linx -T "${LINX_LD_SCRIPT}" -o "${out_elf}" "$@"
}

to_memh() {
  local elf="$1"
  local out_memh="$2"
  bash "${SCRIPT_DIR}/elf_to_memh.sh" "${elf}" "${out_memh}" >/dev/null
}

build_runtime() {
  local rt_dir="${BUILD_DIR}/runtime"
  mkdir -p "${rt_dir}"
  local objs=()

  cc "${BENCH_DIR}/common/startup.c" "${rt_dir}/startup.o"
  objs+=("${rt_dir}/startup.o")

  cc "${LIBC_SRC}/syscall.c" "${rt_dir}/syscall.o"
  cc "${LIBC_SRC}/stdio/stdio.c" "${rt_dir}/stdio.o"
  cc "${LIBC_SRC}/stdlib/stdlib.c" "${rt_dir}/stdlib.o"
  cc "${LIBC_SRC}/string/mem.c" "${rt_dir}/mem.o"
  cc "${LIBC_SRC}/string/str.c" "${rt_dir}/str.o"
  objs+=(
    "${rt_dir}/syscall.o"
    "${rt_dir}/stdio.o"
    "${rt_dir}/stdlib.o"
    "${rt_dir}/mem.o"
    "${rt_dir}/str.o"
  )

  if [[ "${LINX_INCLUDE_LIBM}" == "1" ]]; then
    cc "${LIBC_SRC}/math/math.c" "${rt_dir}/math.o"
    objs+=("${rt_dir}/math.o")
  fi

  printf "%s\n" "${objs[@]}"
}

RUNTIME_OBJS=()
while IFS= read -r line; do
  [[ -n "${line}" ]] && RUNTIME_OBJS+=("${line}")
done < <(build_runtime)

# CoreMark
COREMARK_UP="${BENCH_DIR}/coremark/upstream"
COREMARK_PORT="${BENCH_DIR}/coremark/linx"
COREMARK_OBJDIR="${BUILD_DIR}/coremark"
mkdir -p "${COREMARK_OBJDIR}"

COREMARK_OBJS=()
for src in core_main.c core_matrix.c core_state.c core_util.c; do
  obj="${COREMARK_OBJDIR}/${src%.c}.o"
  cc "${COREMARK_UP}/${src}" "${obj}" "-I${COREMARK_UP}" "-I${COREMARK_PORT}" \
    "${COREMARK_FLAGS_STR}" \
    "-DITERATIONS=${CORE_ITERATIONS}"
  COREMARK_OBJS+=("${obj}")
done

cc "${COREMARK_UP}/core_list_join.c" "${COREMARK_OBJDIR}/core_list_join.o" "-O0" "-I${COREMARK_UP}" "-I${COREMARK_PORT}" \
  "${COREMARK_FLAGS_STR}" \
  "-DITERATIONS=${CORE_ITERATIONS}"
COREMARK_OBJS+=("${COREMARK_OBJDIR}/core_list_join.o")

cc "${COREMARK_PORT}/core_portme.c" "${COREMARK_OBJDIR}/core_portme.o" "-I${COREMARK_UP}" "-I${COREMARK_PORT}" \
  "${COREMARK_FLAGS_STR}" \
  "-DITERATIONS=${CORE_ITERATIONS}"
COREMARK_OBJS+=("${COREMARK_OBJDIR}/core_portme.o")

COREMARK_ELF="${OUT_DIR}/coremark_compat.elf"
COREMARK_MEMH="${OUT_DIR}/coremark_compat.memh"
link_elf "${COREMARK_ELF}" "${RUNTIME_OBJS[@]}" "${COREMARK_OBJS[@]}"
to_memh "${COREMARK_ELF}" "${COREMARK_MEMH}"

# Dhrystone
DHRY_DIR="${BENCH_DIR}/dhrystone/linx"
DHRY_OBJDIR="${BUILD_DIR}/dhrystone"
mkdir -p "${DHRY_OBJDIR}"

cc "${DHRY_DIR}/dhry_1.c" "${DHRY_OBJDIR}/dhry_1.o" "-I${DHRY_DIR}" -std=gnu89 \
  -Wno-implicit-int -Wno-return-type -Wno-implicit-function-declaration \
  "-DDHRY_RUNS=${DHRY_RUNS}"
cc "${DHRY_DIR}/dhry_2.c" "${DHRY_OBJDIR}/dhry_2.o" "-I${DHRY_DIR}" -std=gnu89 \
  -Wno-implicit-int -Wno-return-type -Wno-implicit-function-declaration \
  "-DDHRY_RUNS=${DHRY_RUNS}"

DHRY_ELF="${OUT_DIR}/dhrystone_compat.elf"
DHRY_MEMH="${OUT_DIR}/dhrystone_compat.memh"
link_elf "${DHRY_ELF}" "${RUNTIME_OBJS[@]}" "${DHRY_OBJDIR}/dhry_1.o" "${DHRY_OBJDIR}/dhry_2.o"
to_memh "${DHRY_ELF}" "${DHRY_MEMH}"

echo "${COREMARK_MEMH}"
echo "${DHRY_MEMH}"
