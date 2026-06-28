# Phase 1 Evidence

Date: 2026-06-28

Scope:

- Added `CommitTraceMonitor` as the first Phase 1 commit-interface schema
  monitor.
- Added `docs/chisel/modules/commit/CommitTraceMonitor.md`.
- Updated `CommitTrace.md` and `module-index.md` so later top/trace agents use
  the monitor rather than duplicating commit-window checks.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Observed result:

- `CommitTraceMonitorSpec` ran 5 tests; all passed.
- `CommitTrace` targeted gate ran 10 tests across `CommitTraceSpec` and
  `CommitTraceMonitorSpec`; all passed.
- `build_chisel.sh` passed after the monitor source was added.
- `run_chisel_rob_bookkeeping.sh --reduced-rob` passed ROBID, CommitTrace, and
  ReducedCommitROB gates.
- `run_chisel_reduced_rob_xcheck.sh` built the generated-RTL Verilator harness
  and compared three normalized commits with zero mismatches.

Known issue reconfirmed:

- Running `build_chisel.sh` and `run_chisel_tests.sh --only CommitTrace` in
  parallel reproduced `CHISEL-ISSUE-002` with an SBT socket
  `Connection refused`; rerunning sequentially passed.

Skill evolve:

- `skill-evolve: no-update` because the run added a module-local Phase 1
  monitor under the existing Chisel gate sequence and only reconfirmed the
  already-documented no-parallel-SBT rule.
