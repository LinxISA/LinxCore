# LinxCore Simulator Performance Baseline

This file tracks wall-time baselines for pyCircuit C++ simulation on LinxCore workloads.

## Measurement Method

- Harness: `/Users/zhoubot/LinxCore/tools/perf/measure_sim_walltime.sh`
- Warmup runs: `1`
- Measured runs: `5`
- Commit window: `100000`
- Workloads: CoreMark + Dhrystone
- Metric: median of `real/user/sys` from `/usr/bin/time -p`

## Current Baseline

Run the harness to refresh:

```bash
bash /Users/zhoubot/LinxCore/tools/perf/measure_sim_walltime.sh
```

Latest generated report path:

- `/Users/zhoubot/LinxCore/tests/perf/walltime/walltime_report.md`

Historical pre-SCC/reference numbers (100k commit window):

| Case | Workload | real (s) | user (s) | sys (s) |
| --- | --- | ---: | ---: | ---: |
| instance-cache ON | CoreMark | 22.55 | 22.07 | 0.21 |
| instance-cache OFF (`-DPYC_DISABLE_INSTANCE_EVAL_CACHE`) | CoreMark | 26.05 | 25.50 | 0.25 |

## C++ Build Baseline (Split TU Manifest)

Measured on February 17, 2026 with clean object dirs and clang:

```bash
/usr/bin/time -p env CXX=clang++ PYC_BUILD_JOBS=<N> PYC_SKIP_RUN=1 \
  /Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh
```

| Case | real (s) | user (s) | sys (s) |
| --- | ---: | ---: | ---: |
| full rebuild, `PYC_BUILD_JOBS=1` | 186.54 | 176.15 | 5.87 |
| full rebuild, `PYC_BUILD_JOBS=8` | 95.73 | 267.24 | 10.91 |

Observed parallel compile gain (`jobs=8` vs `jobs=1`):

- speedup: `1.95x`
- wall-time reduction: `48.68%`
