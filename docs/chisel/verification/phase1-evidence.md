# Phase 1 Evidence

Date: 2026-06-28

Scope:

- Added `CommitTraceMonitor` as the first Phase 1 commit-interface schema
  monitor.
- Wired `CommitTraceMonitor` into `ReducedCommitROB` and exposed monitor flags
  on the reduced ROB IO.
- Added `docs/chisel/modules/commit/CommitTraceMonitor.md`.
- Updated `CommitTrace.md`, `ReducedCommitROB.md`, and `module-index.md` so
  later top/trace agents use the monitor rather than duplicating commit-window
  checks.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_verilator_lint.sh
```

Observed result:

- `CommitTraceMonitorSpec` ran 5 tests; all passed.
- `ReducedCommitROBSpec` ran 5 tests; all passed with generated SystemVerilog
  containing `commitContractError`.
- `CommitTrace` targeted gate ran 10 tests across `CommitTraceSpec` and
  `CommitTraceMonitorSpec`; all passed.
- `build_chisel.sh` passed after the monitor source was added.
- `run_chisel_rob_bookkeeping.sh --reduced-rob` passed ROBID, CommitTrace, and
  ReducedCommitROB gates.
- `run_chisel_reduced_rob_xcheck.sh` built the generated-RTL Verilator harness,
  included both emitted SystemVerilog modules, asserted clean monitor outputs
  for valid masks `0x3`, `0x1`, and `0x0`, and compared three normalized commits
  with zero mismatches.
- `trace_schema_adapter.py --self-test` passed.
- `run_chisel_verilator_lint.sh` emitted the Chisel top and passed Verilator
  lint.

Known issue reconfirmed:

- Running `build_chisel.sh` and `run_chisel_tests.sh --only CommitTrace` in
  parallel reproduced `CHISEL-ISSUE-002` with an SBT socket
  `Connection refused`; rerunning sequentially passed.

Skill evolve:

- `skill-evolve: no-update` because the existing reduced ROB xcheck command now
  carries the monitor assertion path; no new operator command or cross-module
  invariant needs a skill update.
