# LinxCore Simulator Performance Playbook

## Goals

- Reduce pyCircuit C++ simulator wall time with no architectural drift.
- Keep fidelity mode as default.
- Use fast mode (`PYC_SIM_FAST=1`) only for opt-in experiments.

## Knobs

- Runtime:
  - `PYC_SIM_FAST=0|1`
  - `PYC_SIM_STATS=0|1`
  - `PYC_SIM_STATS_PATH=<path>`
- Build-time:
  - `-DPYC_DISABLE_INSTANCE_EVAL_CACHE`
  - `-DPYC_DISABLE_PRIMITIVE_EVAL_CACHE`
  - `-DPYC_DISABLE_SCC_WORKLIST_EVAL`
  - `-DPYC_DISABLE_VERSIONED_INPUT_CACHE`
  - `PYC_BUILD_JOBS=<n>` for manifest-driven parallel TU compilation
  - `PYC_SKIP_RUN=1` for compile/link-only timing

## Benchmark Flow

1. Build/regenerate:
   - `bash /Users/zhoubot/LinxCore/tools/generate/update_generated_linxcore.sh`
2. Measure baseline:
   - `bash /Users/zhoubot/LinxCore/tools/perf/measure_sim_walltime.sh`
3. Compare A/B:
   - Run once with default flags.
   - Re-run with one knob changed.
   - Compare `/Users/zhoubot/LinxCore/tests/perf/walltime/walltime_report.md`.

## Compile/Link Wall-Time Check

Use this when evaluating generated C++ split/sharding impact:

```bash
/usr/bin/time -p env CXX=clang++ PYC_BUILD_JOBS=8 PYC_SKIP_RUN=1 \
  /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh
```

## PGO (Optional)

1. Train profile:
   - `bash /Users/zhoubot/LinxCore/tools/perf/build_pgo_train.sh`
2. Build/use profile:
   - `bash /Users/zhoubot/LinxCore/tools/perf/build_pgo_use.sh`

## Correctness Gates (Must Stay Green)

- `/Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh`
- `/Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh`
- `/Users/zhoubot/LinxCore/tests/test_runner_protocol.sh`
- `/Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh`
- `/Users/zhoubot/LinxCore/tests/test_coremark_crosscheck_1000.sh`
