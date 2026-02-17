# Latest LLVM+musl Bench Build + QEMU/LinxCore Verification

## Build

ok: wrote /Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/report.md
clang=/Users/zhoubot/llvm-project/build-linxisa-clang/bin/clang
core_elf=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf
dhry_elf=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/dhrystone/dhrystone.elf
core_memh=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/memh/coremark_latest_llvm_musl.memh
dhry_memh=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/memh/dhrystone_latest_llvm_musl.memh
summary=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/report/latest_llvm_musl_build.md

## Verify Window

- mode: `failfast`
- max_commits: `1000`
- tb_max_cycles: `50000000`
- simpoint_interval: `1000`

## Status

- coremark: `FAIL(1)`
- dhrystone: `FAIL(1)`

## Paths

- coremark verify dir: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/verify/coremark`
- dhrystone verify dir: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/verify/dhrystone`
- full report: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/report/latest_llvm_musl_verify.md`
