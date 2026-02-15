# LinxCore Tooling Flow

## Generate Split Artifacts

```bash
bash /Users/zhoubot/LinxCore/tools/generate/update_generated_linxcore.sh
```

Outputs:
- `/Users/zhoubot/LinxCore/generated/cpp/linxcore_top`
- `/Users/zhoubot/LinxCore/generated/verilog/linxcore_top`

## Run C++ Testbench

```bash
bash /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh <program.memh>
```

## Build Benchmark Images

```bash
bash /Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh
```

## Run Benchmarks

```bash
bash /Users/zhoubot/LinxCore/tools/image/run_linxcore_benchmarks.sh
```

## Run QEMU Lockstep Co-Sim

```bash
bash /Users/zhoubot/LinxCore/tools/qemu/run_cosim_lockstep.sh --help
```
