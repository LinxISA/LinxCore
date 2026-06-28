# Phase 5 Preparation Evidence

## 2026-06-28 ROB Entry Bank Skeleton

Scope:

- Added `ROBEntryBank` as the first status-backed integrated ROB/CMT skeleton.
- Preserved separate allocation, commit, and deallocation pointers.
- Kept `ReducedCommitROB` as the reduced trace harness.
- Preserved the LinxCoreModel `Completed -> Retired -> Free` status phase
  split through `ROBEntryStatus`.
- Added a focused Markdown module contract and Scala reference tests.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

Expected result:

- `ROBEntryBankSpec` passes.
- `ROBEntryStatusSpec` still locks the model status order.
- `ReducedCommitROB` gates remain green because the reduced harness is not
  retrofitted.
- Commit trace monitor and adapter gates remain green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 6 tests in
  `ROBEntryBankSpec`.
- `ROBEntryBankSpec` covers separate commit/dealloc pointer walks, incomplete
  head blocking, duplicate identity rejection until deallocation, deallocation
  backpressure, ignored completion for free/retired rows, and Chisel elaboration
  with status masks plus commit monitor outputs.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus` passed 6 tests
  in `ROBEntryStatusSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB` passed 5
  tests in `ReducedCommitROBSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only CommitTrace` passed 10 tests
  across `CommitTraceSpec` and `CommitTraceMonitorSpec`.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob` passed the
  ROBID semantic check, 3 ROBID tests, 10 CommitTrace/Monitor tests, and 5
  ReducedCommitROB tests.
- `python3 tools/chisel/trace_schema_adapter.py --self-test` passed.
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` selected
  `/Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64` and
  passed the adapter self-test.
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh` emitted the reduced ROB,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.

Skill evolve:

- `skill-evolve: update linx-core` because `ROBEntryBank` adds a reusable Phase
  5 integrated ROB/CMT gate and records that integrated banks must preserve
  separate `allocPtr`, `commitPtr`, and `deallocPtr` walks while keeping
  `ReducedCommitROB` as a reduced harness.
