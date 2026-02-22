# Latest LLVM+musl Benchmark Image Build

- clang: `/Users/zhoubot/llvm-project/build-linxisa-clang/bin/clang`
- target: `linx64-unknown-linux-musl`
- sysroot: `/Users/zhoubot/linx-isa/out/libc/musl/install/phase-b`
- runtime_lib: `/Users/zhoubot/linx-isa/out/libc/musl/runtime/phase-b/liblinx_builtin_rt.a`
- opt: `-O2`
- image_base: `0x10000`
- coremark_iterations: `10`
- dhrystone_runs: `1000`

## Outputs

- coremark_elf: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf`
- dhrystone_elf: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/dhrystone/dhrystone.elf`
- coremark_memh: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/memh/coremark_latest_llvm_musl.memh`
- dhrystone_memh: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/memh/dhrystone_latest_llvm_musl.memh`
