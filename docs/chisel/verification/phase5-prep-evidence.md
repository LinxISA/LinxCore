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

## 2026-06-28 ROB Flush-Prune Selector

Scope:

- Added `ROBFlushPrune` as a standalone integrated ROB/CMT flush-selection
  helper.
- Preserved the `SPEROB::flush` prune rule: scan from `deallocPtr`, find the
  first valid row covered by the flush request, and prune that row plus all
  later valid rows in scan order.
- Kept row mutation, pointer rebasing, rename cleanup, LSU/STQ side effects,
  precise traps, and frontend restart ownership deferred to the next integrated
  ROB/CMT packet.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

Expected result:

- `ROBFlushPruneSpec` passes.
- Existing `ROBEntryBank`, `ROBEntryStatus`, `FlushControl`, and reduced ROB
  gates stay green.
- QEMU dry-run remains a wrapper/adapter proof only.

Observed result:

- Initial `ROBFlushPrune` gate found a bad wraparound expected mask and an
  empty-`VecInit` elaboration bug in the commit-rebase one-hot helper; both were
  fixed before promotion.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
- `ROBFlushPruneSpec` covers base-on-BID pruning, BID/RID pruning, wraparound
  deallocation-order scan, invalid rows after pruning starts, resident versus
  outstanding decrement accounting, invalid flush requests, and Chisel
  elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 6 tests in
  `ROBEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus` passed 6 tests
  in `ROBEntryStatusSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests in
  `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB` passed 5
  tests in `ReducedCommitROBSpec`.
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

- `skill-evolve: update linx-core` because `ROBFlushPrune` adds a reusable
  Phase 5 integrated ROB/CMT gate and records the model-derived flush-prune
  selection invariant for later agents.

## 2026-06-28 ROB Entry-Bank Flush Application

Scope:

- Wired `ROBFlushPrune` into `ROBEntryBank`.
- Added a priority flush phase that suppresses allocation, completion, commit,
  and deallocation during an applied flush.
- Cleared pruned rows, decremented resident and outstanding counts with the
  selector's masks, rebased allocation to the first pruned row, and rebased
  commit when the model walk prunes before the old commit head or leaves no
  outstanding work.
- Kept rename/checkpoint restore, local ready-table cleanup, LSU/STQ cleanup,
  precise traps, and frontend restart publication deferred to future integrated
  ROB/CMT work.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `ROBEntryBankSpec` includes the new flush-apply reference vectors and passes.
- `ROBFlushPrune`, `ROBEntryStatus`, and `FlushControl` remain green.
- Full reduced ROB and QEMU-shaped gates remain the final promotion checks.

Observed result:

- First `ROBEntryBank` gate caught a Scala parser error in the temporary
  identity-to-`ROBID` bridge; adding parentheses around the compile-time
  `if` expression fixed the compile.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 8 tests in
  `ROBEntryBankSpec`.
- New `ROBEntryBankSpec` reference tests cover RID-based flush pruning through
  the bank, allocation reuse of the first pruned slot, and retired-row flush
  clearing without decrementing outstanding work.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus` passed 6 tests
  in `ROBEntryStatusSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests in
  `FlushControlSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB` passed 5
  tests in `ReducedCommitROBSpec`.
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

- `skill-evolve: update linx-core` because the bank now owns a reusable
  integrated ROB/CMT flush-apply priority rule: apply `ROBFlushPrune` masks in
  `ROBEntryBank`, suppress other bank phases during an applied flush, update
  resident/outstanding counts from selector accounting, and keep broader
  recovery cleanup out of the selector.

## 2026-06-28 ROB Entry-Bank Native Row IDs

Scope:

- Added native row BID/RID sidecars to `ROBEntryBank`.
- Stored backend/BROB BID from `allocBid` on allocation.
- Allocated row RID from `allocValue`/`allocWrap`, matching
  `SPEROB::allocROB` assigning RID from the allocation pointer.
- Fed `ROBFlushPrune` from the native sidecars instead of
  `CommitTraceRow.identity`.
- Kept `CommitTraceRow.identity` as the trace and duplicate-detection sideband.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `ROBEntryBankSpec` proves flush pruning uses native row IDs rather than
  commit trace identity sidebands.
- Existing flush selector, status, recovery, reduced ROB, adapter, QEMU
  dry-run, and reduced generated-RTL cross-check gates remain green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests in
  `ROBEntryBankSpec`.
- New `ROBEntryBankSpec` coverage includes a regression where trace identity
  RIDs are deliberately misleading, but native row RID sidecars select the
  correct prune point.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus` passed 6 tests
  in `ROBEntryStatusSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests in
  `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB` passed 5
  tests in `ReducedCommitROBSpec`.
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

- `skill-evolve: update linx-core` because native row BID/RID sidecars are now
  a reusable integrated ROB/CMT invariant: `ROBEntryBank` flush comparison uses
  `rowBid`/`rowRid`, `allocBid` comes from the backend/BROB owner, RID comes
  from the bank allocation pointer, and `CommitTraceRow.identity` remains trace
  plus duplicate-detection metadata only.
