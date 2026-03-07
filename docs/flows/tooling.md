# LinxCore Tooling Flow

## Generate Split Artifacts

```bash
bash /Users/zhoubot/LinxCore/tools/generate/update_generated_linxcore.sh
```

Build profile defaults:

- `LINXCORE_BUILD_PROFILE=dev-fast` (default): lower compile latency (`--noinline`, tighter sharding, reduced logic depth).
- `LINXCORE_BUILD_PROFILE=release`: canonical closure lane settings.

Compile-cost hard-gate knobs used by this flow:

- `PYC_MANIFEST_COST_MAX_TOP_TU` (default `250000`)
- `PYC_MANIFEST_COST_MAX_TOP_MODULE` (default `1500000`)
- `PYC_MANIFEST_COST_MAX_TOTAL` (default `2300000`)
- `PYC_MANIFEST_COST_BASELINE_DIR` (default `/Users/zhoubot/LinxCore/generated/perf/baselines`)
- `PYC_MANIFEST_COST_BASELINE_JSON` (default `<baseline_dir>/cpp_compile_manifest.<profile>.json`)
- `PYC_MANIFEST_COST_MAX_REGRESS_PCT` (default `25`)
- `PYC_MANIFEST_COST_AUTO_BOOTSTRAP` (default `1`, auto-create baseline on first run)
- `PYC_MANIFEST_COST_UPDATE_BASELINE=1` (force-refresh baseline from current manifest)
- `PYC_MANIFEST_COST_STRICT=1` (optional hard-fail mode; default is warn-only)

Outputs:
- `/Users/zhoubot/LinxCore/generated/cpp/linxcore_top`
- `/Users/zhoubot/LinxCore/generated/verilog/linxcore_top`

## LinxCoreSight Strict Mode

LinxCoreSight consumes LinxTrace v1 (`*.linxtrace (single-file with in-band META record)`) in this flow.
`run_linxtrace.sh` runs strict contract + trace lint gates before opening:

```bash
python3 /Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py \
  <trace.linxtrace> \
  --require-stages F0,F3,D1,D3,IQ,BROB,CMT \
  --single-stage-per-cycle
```

Any lifecycle violation is fatal (post-retire command, invalid lane/stage token, or missing occupancy records).

LinxCoreSight-side CLI diagnostics:

```bash
node /Users/zhoubot/LinxCoreSight/scripts/linxtrace_cli.js lint <trace.linxtrace>
node /Users/zhoubot/LinxCoreSight/scripts/linxtrace_cli.js stats <trace.linxtrace>
node /Users/zhoubot/LinxCoreSight/scripts/linxtrace_cli.js first-failure <trace.linxtrace>
node /Users/zhoubot/LinxCoreSight/scripts/linxtrace_cli.js schema-check <trace.linxtrace>
```

## Run C++ Testbench

```bash
bash /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh <program.memh>
```

Manifest-driven parallel C++ build knobs:

- `PYC_BUILD_JOBS=<n>` controls generated TU compile parallelism.
- `PYC_GEN_CXXFLAGS="<flags>"` sets C++ flags for generated pycc TUs only.
- `PYC_TB_CXXFLAGS="<flags>"` sets C++ flags for TB/host sources only.
- `PYC_TB_PCH=1|0` enables/disables TB precompiled-header acceleration (default: `1` on clang).
- `PYC_CXX_LAUNCHER=<tool>` prepends a compile launcher (for example `ccache`).
  If unset and `ccache` is installed, `run_linxcore_top_cpp.sh` auto-enables it.
- `PYC_MANIFEST_PATH=<path>` overrides manifest path (default:
  `/Users/zhoubot/LinxCore/generated/cpp/linxcore_top/cpp_compile_manifest.json`).

Instruction text trace (per input):

```bash
PYC_COMMIT_TRACE=/tmp/program.commit.jsonl \
PYC_COMMIT_TRACE_TEXT=/tmp/program.commit.txt \
bash /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh <program.memh>
```

`PYC_COMMIT_TRACE_TEXT` is optional. If omitted, the runner writes `<commit_trace>.txt` automatically.

Optional compatibility knob for template frame addend:

```bash
PYC_CALLFRAME_SIZE=0 bash /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh <program.memh>
```

## Build Benchmark Images

```bash
bash /Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh
```

Notes:

- Real benchmark source defaults to:
  - `/Users/zhoubot/linx-isa/workloads/generated/elf/coremark.elf`
  - `/Users/zhoubot/linx-isa/workloads/generated/elf/dhrystone.elf`
- Enforce real-only inputs:

```bash
LINX_BENCH_REQUIRE_REAL=1 bash /Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh
```

- Override ELF source directory (expects `coremark.elf` and `dhrystone.elf`):

```bash
LINXISA_ELF_DIR=/path/to/elf_dir \
bash /Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh
```

## Build Latest LLVM+musl Images

```bash
bash /Users/zhoubot/LinxCore/tools/image/build_latest_llvm_musl_images.sh \
  --out-dir /Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl
```

This compiles CoreMark/Dhrystone with the latest local LLVM toolchain under
`/Users/zhoubot/llvm-project`, links with musl static runtime, and emits:

- ELFs under `.../tests/benchmarks_latest_llvm_musl/elf`
- MEMH images under `.../tests/benchmarks_latest_llvm_musl/memh`

## Build + Verify + SimPoint-Style Slice

```bash
bash /Users/zhoubot/LinxCore/tools/image/build_verify_latest_llvm_musl.sh \
  --out-dir /Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl \
  --max-commits 300 \
  --xcheck-mode failfast
```

Artifacts per benchmark:

- `verify/<bench>/qemu_trace.jsonl`
- `verify/<bench>/dut_trace.jsonl`
- `verify/<bench>/qemu_trace.txt`
- `verify/<bench>/dut_trace.txt`
- `verify/<bench>/simpoint_slice.json`
- `verify/<bench>/simpoint_slice.md`

The slice selector (`tools/trace/select_simpoint_window.py`) uses a lightweight
SimPoint-style BBV approximation over committed `pc->next_pc` edges.

## Run Benchmarks

```bash
bash /Users/zhoubot/LinxCore/tools/image/run_linxcore_benchmarks.sh
```

## Generate LinxTrace for CoreMark + Dhrystone

```bash
# Optional: limit trace to the first N retired instructions (default: 1000).
bash /Users/zhoubot/LinxCore/tools/linxcoresight/run_linxtrace_benchmarks.sh 1000
```

## Run QEMU Lockstep Co-Sim for CoreMark + Dhrystone

```bash
# Samples the first N commits from each workload (default: 1000).
bash /Users/zhoubot/LinxCore/tools/qemu/run_cosim_benchmarks.sh
```

## Run Benchmark Trace + Co-Sim Gate (CoreMark + Dhrystone)

```bash
# Produces/validates LinxTrace+DFX artifacts and then runs QEMU lockstep co-sim.
bash /Users/zhoubot/LinxCore/tests/test_benchmarks_trace_cosim.sh
```

## Run QEMU Lockstep Co-Sim

```bash
bash /Users/zhoubot/LinxCore/tools/qemu/run_cosim_lockstep.sh --help
```

`run_cosim_lockstep.sh` no longer forces ET_REL low-BSS ranges by default. To force legacy behavior explicitly:

```bash
LINX_COSIM_FORCE_ET_REL=1 bash /Users/zhoubot/LinxCore/tools/qemu/run_cosim_lockstep.sh ...
```

## QEMU↔LinxCore Cross-Check

Generate QEMU commit trace:

```bash
bash /Users/zhoubot/LinxCore/tools/qemu/run_qemu_commit_trace.sh \
  --elf /Users/zhoubot/linx-isa/workloads/generated/elf/coremark.elf \
  --out /tmp/coremark_qemu_commit.jsonl
```

Run LinxCore with in-TB xcheck and LinxTrace mismatch markers:

```bash
PYC_QEMU_TRACE=/tmp/coremark_qemu_commit.jsonl \
PYC_XCHECK_MODE=diagnostic \
PYC_XCHECK_MAX_COMMITS=1000 \
PYC_XCHECK_REPORT=/tmp/coremark_crosscheck \
PYC_LINXTRACE=1 \
bash /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh <coremark.memh>
```

Mode semantics:

- `PYC_XCHECK_MODE=diagnostic`
  - collect/report mismatches and emit LinxTrace `XCHECK` markers
  - does not force TB process failure at end-of-run
- `PYC_XCHECK_MODE=failfast`
  - abort immediately on first mismatch with non-zero exit

Offline cross-check report:

```bash
python3 /Users/zhoubot/LinxCore/tools/trace/crosscheck_qemu_linxcore.py \
  --qemu-trace /tmp/coremark_qemu_commit.jsonl \
  --dut-trace /tmp/coremark_dut_commit.jsonl \
  --mode diagnostic \
  --max-commits 1000 \
  --report-dir /tmp
```

Offline checker mode semantics match TB:

- `--mode diagnostic`: emit reports, return success even when mismatches are present
- `--mode failfast`: return non-zero on first mismatch

## Stage/Stub Guardrails

```bash
python3 /Users/zhoubot/LinxCore/tools/generate/lint_stage_naming.py
python3 /Users/zhoubot/LinxCore/tools/generate/lint_no_stubs.py
bash /Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh
```

## Opcode Catalog + Decode Parity

```bash
python3 /Users/zhoubot/LinxCore/tools/generate/extract_qemu_opcode_matrix.py
python3 /Users/zhoubot/LinxCore/tools/generate/gen_opcode_tables.py
python3 /Users/zhoubot/LinxCore/tools/generate/check_decode_parity.py
bash /Users/zhoubot/LinxCore/tests/test_opcode_parity.sh
```

## Simulator Wall-Time Measurement

```bash
bash /Users/zhoubot/LinxCore/tools/perf/measure_sim_walltime.sh
```

The harness emits:

- raw samples: `/Users/zhoubot/LinxCore/tests/perf/walltime/walltime_samples.csv`
- summary report: `/Users/zhoubot/LinxCore/tests/perf/walltime/walltime_report.md`

## Optional PGO Build Flow

```bash
bash /Users/zhoubot/LinxCore/tools/perf/build_pgo_train.sh
bash /Users/zhoubot/LinxCore/tools/perf/build_pgo_use.sh
```
