# Latest LLVM+musl Bench Build + QEMU/LinxCore Verification

## Build

ok: wrote /Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/report.md
clang=/Users/zhoubot/llvm-project/build-linxisa-clang/bin/clang
core_elf=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf
dhry_elf=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/dhrystone/dhrystone.elf
core_memh=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/memh/coremark_latest_llvm_musl.memh
dhry_memh=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/memh/dhrystone_latest_llvm_musl.memh
summary=/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/report/latest_llvm_musl_build.md

## Verify Window

- mode: `failfast`
- max_commits: `300`
- tb_max_cycles: `50000000`
- simpoint_interval: `1000`

## Status

- coremark: `PASS`
- dhrystone: `PASS`

## Paths

- coremark verify dir: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/verify/coremark`
- dhrystone verify dir: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/verify/dhrystone`
- full report: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/report/latest_llvm_musl_verify.md`
