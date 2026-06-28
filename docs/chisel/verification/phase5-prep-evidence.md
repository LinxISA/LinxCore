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

## 2026-06-28 Dispatch/BROB-To-ROB Allocation Bridge

Scope:

- Added `DispatchROBAllocator` as the first backend integration owner that
  composes `BrobMetaTracker` and `ROBEntryBank`.
- Generated the next full hardware BID from a block allocation cursor.
- Allocated BROB metadata and ROB row state atomically.
- Wrote the generated full BID into the ROB row `blockBid` trace sideband.
- Converted the generated BID into the native `ROBID` sidecar consumed by
  `ROBEntryBank.allocBid`; RID remains allocated inside `ROBEntryBank`.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `DispatchROBAllocatorSpec` passes and elaborates the composed module.
- Existing BROB, ROB bank, flush, recovery, reduced ROB, adapter, QEMU dry-run,
  and reduced generated-RTL cross-check gates remain green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator` passed 5
  tests in `DispatchROBAllocatorSpec`.
- New reference coverage includes atomic BROB/ROB allocation, BID cursor wrap
  through uniqueness bits, blocking without cursor advance when BROB is full,
  blocking without cursor advance when the ROB bank rejects a duplicate
  identity, and Chisel elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only BROB` passed 5 tests in
  `BROBSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests in
  `ROBEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
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

- `skill-evolve: update linx-core` because `DispatchROBAllocator` adds a
  reusable backend integration rule and gate: generated full BIDs must allocate
  BROB and ROB row state atomically, populate `CommitTraceRow.blockBid`, and
  drive `ROBEntryBank.allocBid` while leaving RID allocation inside the ROB
  bank.

## 2026-06-28 Recovery Full-BID Handoff

Scope:

- Added `FullBidRecoveryBridge` as the first explicit recovery handoff between
  full hardware block BID and ring `ROBID`.
- Preserved the full `blockBid` surface for BROB/block cleanup.
- Produced the ring `FlushBus.req.bid` sidecar for ROB row pruning by taking
  low BID slot bits as `ROBID.value` and the low uniqueness bit as
  `ROBID.wrap`.
- Moved `DispatchROBAllocator` onto the shared conversion helper so allocation
  and recovery use the same BID split.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `FullBidRecoveryBridgeSpec` passes and elaborates full-BID and ROB flush
  surfaces.
- Existing flush, allocator, BROB, ROB, reduced ROB, adapter, QEMU dry-run,
  and reduced generated-RTL cross-check gates remain green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge` passed
  4 tests in `FullBidRecoveryBridgeSpec`.
- New reference coverage includes full-BID slot/wrap mapping, full block BID
  preservation, non-BID RID sidecar separation, and Chisel elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator` passed 5
  tests in `DispatchROBAllocatorSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only BROB` passed 5 tests in
  `BROBSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
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

- `skill-evolve: update linx-core` because `FullBidRecoveryBridge` adds a
  reusable recovery invariant and gate: recovery must carry full hardware BID
  for BROB/block cleanup and ring `ROBID` for ROB row pruning through one
  shared conversion contract.

## 2026-06-28 Recovery Cleanup Intent Boundary

Scope:

- Added `RecoveryCleanupControl` as the first registered recovery cleanup
  intent owner after request selection.
- Reused `FullBidRecoveryBridge` so registered cleanup intent preserves the
  full block BID and ring ROB `FlushBus`.
- Exposed model-derived intent bits for BCTRL flush/replay, scalar rename
  flush/replay, backend cleanup, ROB pruning, report-queue filtering,
  frontend restart, PE fanout, vector/MTC replay, LSU/STQ cleanup, and tile
  cleanup.
- Kept actual rename, LSU/STQ, frontend, PE fanout, BROB pointer restoration,
  and trap side effects out of `ROBFlushPrune` and generic top-level glue.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only ReducedCommitROB
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `RecoveryCleanupControlSpec` passes and elaborates the registered cleanup
  intent interface.
- Existing bridge, flush, ROB, allocator, BROB, reduced ROB, adapter, QEMU
  dry-run, and reduced generated-RTL cross-check gates remain green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- New reference coverage includes global miss-predict flush, scalar fast
  replay, PE replay, SIMT inner flush, MTC replay, and Chisel elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge` passed
  4 tests in `FullBidRecoveryBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator` passed 5
  tests in `DispatchROBAllocatorSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only BROB` passed 5 tests in
  `BROBSpec`.
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

- `skill-evolve: update linx-core` because `RecoveryCleanupControl` adds a
  reusable recovery owner and gate: selected recovery requests must pass through
  a registered cleanup-intent boundary, and downstream rename/LSU/STQ/frontend
  side effects must not be implemented inside `ROBFlushPrune`.

## 2026-06-28 STQ Flush-Prune Consumer

Scope:

- Added `STQFlushPrune` as the first concrete LSU/STQ cleanup consumer for
  `RecoveryCleanupControl.intent.flush`.
- Mirrored `FlushBus::match(MemReqBus)` scope and age rules: `stid`, optional
  PE/thread filters, BID-only matching, group matching with the model BID fast
  path, and default BID+LSID matching.
- Preserved the model `STQ::flush` restriction that only valid `STQ_WAIT`
  entries are freed.
- Kept STQ RAM mutation, `storeCommitQ`, SCB/MDB state, memory queues,
  frontend restart, rename restore, and ROB pruning out of the mask generator.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `STQFlushPruneSpec` passes and elaborates the LSU/STQ mask interface.
- Existing recovery bridge/control, flush, ROB prune, entry-bank, reduced ROB,
  adapter, QEMU dry-run, and reduced generated-RTL cross-check gates remain
  green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- New reference coverage includes base-on-BID freeing, default BID+LSID
  comparison, group comparison with the model BID fast path, STID/PE/thread
  scoping, invalid flush suppression, non-`Wait` status preservation, and
  Chisel elaboration.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge` passed
  4 tests in `FullBidRecoveryBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune` passed 6 tests
  in `ROBFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
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

- `skill-evolve: update linx-core` because `STQFlushPrune` adds a reusable
  LSU recovery invariant and gate: STQ cleanup must consume the selected
  `FlushBus`, match the model `MemReqBus` predicate, free only valid
  `STQ_WAIT` entries, and leave full STQ side effects to the LSU owner.

## 2026-06-28 STQ Entry Bank State Owner

Scope:

- Added `STQEntryBank` as the first STQ row-state owner around
  `STQFlushPrune`.
- Implemented first-free allocation for model `ST_ALL`, `ST_ADDR`, and
  `ST_DATA` store requests.
- Implemented complementary split-store merge into `ST_ALL` without changing
  resident or WAIT/outstanding counts.
- Implemented local ready `STQ_WAIT -> STQ_COMMIT` marking and committed-row
  free with separate `size` and `osdSize` accounting.
- Applied `STQFlushPrune.freeMask` to clear matched WAIT rows while preserving
  matched committed/non-WAIT rows.
- Kept `storeCommitQ` ordering, SCB/MDB traffic, cacheline split handling,
  load forwarding, tile/TTrans side effects, and data-array banking deferred to
  later LSU owners.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
```

Expected result:

- `STQEntryBankSpec` passes and elaborates the bank with its internal
  `STQFlushPrune` child.
- Existing STQ flush-prune, recovery control, flush, ROB, reduced ROB, adapter,
  QEMU dry-run, and reduced generated-RTL cross-check gates remain green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 6 tests
  in `STQEntryBankSpec`.
- New reference coverage includes first-free allocation, split store merge,
  full-queue merge acceptance, full-queue allocation rejection, WAIT-to-COMMIT
  accounting, committed-row free, WAIT-only recovery free, committed-row
  preservation on flush, and Chisel elaboration with the `STQFlushPrune` child.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
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

- `skill-evolve: update linx-core` because `STQEntryBank` adds a reusable
  LSU/STQ row-state gate and owner boundary: future memory-side packets should
  build around this state owner instead of moving store queue mutation into
  recovery, ROB, or top-level glue.

## 2026-06-28 STQ Commit Queue Ordering Owner

Scope:

- Added `STQCommitQueue` as the first Chisel owner for model `storeCommitQ`
  ordering after `STQEntryBank` marks rows committed.
- Implemented sorted enqueue by model `(bid, lsId)` age using the shared
  wrap-aware `ROBID` convention.
- Implemented downstream-ready issue selection up to `issueWidth`, including
  model-like skipping of stalled rows and compaction of issued entries.
- Preserved the owner boundary: `STQEntryBank` owns row state, this module owns
  commit ordering and drain selection, and SCB/MDB, cacheline split handling,
  TTrans/tile side effects, BSB window-slide, and data-array banking remain
  future LSU owner work.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
```

Expected result:

- `STQCommitQueueSpec` passes and elaborates the queue interface.
- Existing STQ row-state, flush-prune, recovery, ROB, trace-adapter, QEMU
  dry-run, reduced RTL xcheck, and top-shell xcheck gates stay green.

Observed result:

- Initial `STQCommitQueue` gate caught missing `Mux1H` and `PriorityEncoder`
  imports; adding explicit `chisel3.util` imports fixed the compile before
  promotion.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- New reference coverage includes sorted enqueue, `ROBID` wrap ordering,
  downstream-stall skipping, same-cycle issue plus enqueue, duplicate/full
  rejection, issue gating, and Chisel elaboration.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 6 tests
  in `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.

Skill evolve:

- `skill-evolve: update linx-core` because `STQCommitQueue` adds a reusable
  LSU/STQ commit-ordering gate and owner boundary: future memory-side LSU
  packets must consume this queue for store drain ordering instead of embedding
  `storeCommitQ` scans into SCB, MDB, recovery, or top-level glue.

## 2026-06-28 STQ Entry Bank Multi-Free Target

Scope:

- Extended `STQEntryBank` with a committed-row free mask alongside the legacy
  single-index `commitFree` command.
- Preserved model `STQ::free` accounting: accepted committed rows decrement
  resident `size`; WAIT/outstanding `osdSize` is unchanged because these rows
  already left WAIT state at `STQ::retire`.
- De-duplicated single-index and mask free requests through one accepted mask
  so each committed row is cleared and counted once.
- Reported requested WAIT, invalid, or recovery-blocked rows through an ignored
  mask instead of freeing them.
- Kept memory-side success, SCB/MDB handoff, cacheline split handling, and
  `STQCommitQueue` wiring for the next LSU owner packet.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
```

Expected result:

- `STQEntryBankSpec` passes with the multi-free reference vector.
- Existing STQ commit queue, STQ flush-prune, recovery, ROB, trace-adapter,
  QEMU dry-run, reduced RTL xcheck, and top-shell xcheck gates stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests
  in `STQEntryBankSpec`.
- New reference coverage frees two committed rows with one mask, reports a WAIT
  row and an invalid row as ignored, decrements resident count by two, and
  leaves outstanding WAIT count unchanged.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.

Skill evolve:

- `skill-evolve: update linx-core` because `STQEntryBank` now has the reusable
  bank-side multi-free gate needed by future `STQCommitQueue` to memory-side
  drain integration: accepted committed rows clear by mask and decrement
  resident count once, while WAIT/outstanding count is preserved.

## 2026-06-28 STQ Commit Drain Boundary

Scope:

- Added `STQCommitDrain` as the first Chisel memory-side owner for committed
  scalar STQ rows.
- Composed `STQCommitQueue` with `STQEntryBank` row sidecars so committed rows
  are still selected in model `storeCommitQ` order, while memory-side readiness
  decides which rows can issue.
- Matched the scalar model split contract from `AddrCrossCacheline` and
  `GetCrossReq`: a split store needs both segment paths ready before issue and
  emits two descriptors with second data shifted by `first_size * 8`.
- Drove `STQEntryBank.commitFreeMask` only for rows selected by the queue after
  downstream segment readiness, matching model `STQ::commit` where `free(i)`
  follows successful `sendSimL1`.
- Kept SCB/MDB storage, TTrans/tile side effects, BSB window slide, CHI
  completion, load forwarding, and data-array banking in later LSU owner
  packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `STQCommitDrainSpec` covers single-line issue/free, split stores requiring
  both segment targets, younger-row progress around an older split-stalled row,
  issue gating, and Chisel elaboration.
- Existing STQ commit queue, STQ bank, STQ flush-prune, recovery, ROB,
  trace-adapter, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- Initial `STQCommitDrain` gate caught missing `Cat` and `Fill` imports; adding
  explicit `chisel3.util` imports fixed the compile before promotion.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain` passed 5 tests
  in `STQCommitDrainSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests
  in `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests
  in `ROBEntryBankSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `STQCommitDrain` adds the reusable
  memory-side committed-store drain invariant: free committed STQ rows only
  after single- or split-segment downstream acceptance, preserve queue
  skip-around-stall behavior, and keep SCB/MDB/CHI completion in later owners.

## 2026-06-28 SCB Commit Ingress

Scope:

- Added `SCBCommitIngress` as the first Chisel owner for scalar committed-store
  ingress into the store coalescing buffer.
- Mirrored the model `SCBuffer::handleInsert` line-coalescing contract:
  cacheline address is `addr & ~0x3f`, same-line stores merge by byte-valid
  mask, and scalar store data is treated as little-endian bytes.
- Published post-merge line wakeups for future load-side consumers, matching
  the model `sendLUwakeup` payload shape.
- Kept DCache lookup/update, not-full/full eviction, L2/CHI request generation,
  write-response matching, MDB conflict prediction, load forwarding, and SCB
  capacity feedback into `STQCommitDrain` in later LSU owner packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBCommitIngressSpec` covers first-line allocation, same-line merge,
  full-SCB blocking with same-line hit acceptance, split-fragment line
  allocation, and Chisel elaboration.
- Existing STQ drain, commit queue, STQ bank, STQ flush-prune, recovery, ROB,
  trace-adapter, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- Initial `SCBCommitIngress` elaboration caught a partial assignment to a
  read-only UInt slice inside the byte-merge helper; rebuilding the merged line
  from a byte `Vec` fixed the compile before promotion.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain` passed 5 tests
  in `STQCommitDrainSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests
  in `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests in
  `ROBEntryBankSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBCommitIngress` adds the reusable
  LSU/SCB line-coalescing gate: accepted committed-store fragments allocate or
  merge 64-byte SCB lines in lane order, publish post-merge byte wakeups, and
  report blocked fragments while capacity feedback, eviction, DCache/L2/CHI,
  MDB, and load-forwarding ownership remain future packets.

## 2026-06-28 SCB Commit Bridge

Scope:

- Added `SCBCommitBridge` as the first Chisel capacity-feedback boundary
  between `STQCommitDrain` descriptors and `SCBCommitIngress` storage.
- Mirrored the conservative model `SCBuffer::full()` admission rule:
  committed-store descriptors do not enter SCB unless current SCB free entries
  are at least the bridge request width.
- Converted accepted descriptors with `last=1` into the final committed-row
  free mask for `STQEntryBank`.
- Kept DCache lookup/update, SCB eviction, L2/CHI request/response handling,
  MDB conflict prediction, load forwarding, and full STQ-to-SCB composition in
  later LSU owner packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBCommitBridgeSpec` covers model-batch admission, split-store
  final-fragment freeing, conservative same-line-hit stall under model full,
  exact free-count admission, and Chisel elaboration.
- Existing SCB ingress, STQ drain, commit queue, STQ bank, STQ flush-prune,
  recovery, ROB, trace-adapter, QEMU dry-run, reduced RTL xcheck, top-shell
  xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge` passed 5 tests
  in `SCBCommitBridgeSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain` passed 5 tests
  in `STQCommitDrainSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests
  in `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune` passed 6 tests
  in `STQFlushPruneSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl` passed
  6 tests in `RecoveryCleanupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests
  in `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank` passed 9 tests in
  `ROBEntryBankSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBCommitBridge` adds the reusable
  LSU/SCB capacity-feedback invariant: final committed-row frees must come
  after model-batch SCB admission and accepted `last` fragments, while
  structural ingress hit handling alone is not enough to free STQ rows.

## 2026-06-28 SCB Egress Select

Scope:

- Added `SCBEntryState` to mirror model `S_EMPTY`, `S_VALID`, `S_LOOKUP`, and
  `S_MISS` at the Chisel SCB line-entry boundary.
- Updated `SCBCommitIngress` to initialize accepted rows as `Valid` and merge
  same-line stores only into rows still in `Valid` state.
- Added `SCBEgressSelect` as the first SCB egress selection owner. It consumes
  SCB entries, exposes valid/full/not-full candidate masks, prefers full valid
  lines, and uses a deterministic first-valid not-full fallback for the model's
  random not-full eviction path.
- Kept DCache lookup/update, L2/CHI request/response handling, SCB row free,
  MDB conflict prediction, load forwarding, and final STQ free composition in
  later LSU owner packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBEgressSelectSpec` covers full-line priority, deterministic not-full
  fallback, disabled-eviction masking, ignoring `Lookup`/`Miss` rows,
  no-candidate reporting, and Chisel elaboration.
- Existing SCB bridge, SCB ingress, STQ drain, ROB/cross-check, QEMU dry-run,
  reduced RTL xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect` passed 6 tests
  in `SCBEgressSelectSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge` passed 5 tests
  in `SCBCommitBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain` passed 5 tests
  in `STQCommitDrainSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBEgressSelect` adds the reusable
  LSU/SCB egress invariant: only model-valid SCB rows can issue lookup
  descriptors, full rows have priority, and the model's random not-full
  eviction path must be made deterministic before it becomes RTL-visible.

## 2026-06-28 SCB Lookup Control

Scope:

- Added `SCBLookupControl` as the first abstract DCache/L2 outcome owner after
  `SCBEgressSelect`.
- Mirrored model `SCBuffer::handleLookup` and `L1DCache::lookup` behavior:
  writable DCache hits emit byte-update/free intent, tag hits without write
  permission emit upgrade ownership requests, and tag misses emit write
  ownership requests.
- Preserved model request metadata: 64-byte request size and transaction tag
  encoding `(entryIndex << 2) | 2`.
- Kept actual DCache RAM mutation, L2/CHI queue ownership, WriteResp matching,
  registered SCB row mutation, MDB conflict prediction, load forwarding, and
  final STQ free composition in later LSU owner packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBLookupControlSpec` covers writable-hit update/free, tag-hit upgrade
  request, tag-miss write request, L2 backpressure, DCache unavailability,
  empty-byte hit free without broadcast, and Chisel elaboration.
- Existing SCB egress, bridge, ingress, ROB/cross-check, QEMU dry-run, reduced
  RTL xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl` passed 7
  tests in `SCBLookupControlSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect` passed 6 tests
  in `SCBEgressSelectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge` passed 5 tests
  in `SCBCommitBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBLookupControl` adds the reusable
  LSU/SCB DCache/L2 invariant: writable DCache hits free/update locally, tag
  hits without write permission request upgrade ownership, tag misses request
  write ownership, and miss-path rows must remain resident until a later
  WriteResp/UpgradeResp owner returns them to lookup.

## 2026-06-28 SCB State Update

Scope:

- Added `SCBStateUpdate` as the first row-state transition owner after
  `SCBLookupControl`.
- Preserved model `SCBuffer` row transitions around lookup and response:
  selected `Valid -> Lookup`, writable-hit free, non-writable lookup to `Miss`,
  and response-driven `Miss -> Lookup`.
- Exposed illegal transition masks for future registered composition bugs such
  as WriteResp/UpgradeResp to non-`Miss` rows.
- Kept raw CHI TxnID decode, registered row-bank storage, ingress/egress
  arbitration, DCache RAM mutation, L2/CHI queues, MDB, forwarding, and full
  STQ-to-SCB composition in later LSU owner packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBStateUpdateSpec` covers valid lookup start, same-cycle miss, writable-hit
  free, registered lookup miss, memory response return, illegal response
  target, free-priority behavior, illegal miss/free visibility, and Chisel
  elaboration.
- Existing SCB lookup, egress, bridge, ingress, ROB/cross-check, QEMU dry-run,
  reduced RTL xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate` passed 9 tests
  in `SCBStateUpdateSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl` passed 7
  tests in `SCBLookupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect` passed 6
  tests in `SCBEgressSelectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge` passed 5
  tests in `SCBCommitBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBStateUpdate` adds the reusable
  LSU/SCB row-state invariant: same-cycle accepted hit/miss masks are legal
  for a current `Valid` row, writable hits clear rows, misses hold rows in
  `Miss`, and WriteResp/UpgradeResp targets must be valid `Miss` rows before
  returning to lookup.

## 2026-06-28 SCB Row Bank

Scope:

- Added `SCBRowBank` as the first registered SCB composition owner around one
  row image.
- Preserved pre-cycle model-batch admission, lane-ordered committed-store
  ingress, final STQ free masks for accepted `last` fragments, egress
  selection, abstract DCache/L2 lookup classification, and row-state
  registration after hit, miss, or memory response.
- Kept outstanding rows closed to new store coalescing: same-line writes do not
  merge into `Lookup` or `Miss` rows.
- Kept raw CHI TxnID decode, L2/CHI queues, DCache RAM mutation, MDB,
  forwarding, full STQ-to-SCB wiring, and memory-event trace in later owner
  packets.

Evidence:

```bash
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBRowBankSpec` covers model-batch admission, pre-cycle free-count gating,
  same-cycle ingress merge plus writable hit, miss ownership request, response
  return, outstanding-row non-merge, illegal response reporting, and Chisel
  elaboration with the egress/lookup/state-update children.
- Existing SCB state update, lookup, egress, bridge, ingress, ROB/cross-check,
  QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and Verilator lint gates
  stay green.

Observed result:

- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank` passed 8 tests in
  `SCBRowBankSpec`.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate` passed 9 tests
  in `SCBStateUpdateSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl` passed 7
  tests in `SCBLookupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect` passed 6
  tests in `SCBEgressSelectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge` passed 5
  tests in `SCBCommitBridgeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress` passed 5
  tests in `SCBCommitIngressSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBRowBank` adds the reusable
  registered SCB composition invariant: pre-cycle free count controls model
  batch admission, accepted ingress may feed the same-cycle lookup payload, and
  `Lookup`/`Miss` rows remain closed to same-line coalescing.

## 2026-06-28 STQ-to-SCB Commit Path

Scope:

- Added `STQSCBCommitPath` as the first full STQ-to-SCB composition owner.
- Wired `STQEntryBank`, `STQCommitDrain`, and `SCBRowBank` so accepted
  `SCBRowBank` `last` fragments are the only committed-row free source back
  into the STQ bank.
- Preserved the model `STQ::retire` then `STQ::commit` split: commit marks
  enqueue row identities, drain issue observes the registered committed row
  image, and older committed rows may drain while a younger row is marked for a
  later cycle.
- Gated drain issue with the registered SCB model-batch condition and
  suppressed drain issue during STQ flush-prune cycles so the bank cannot
  ignore a free command after SCB accepts a store.
- Kept raw CHI TxnID decode, L2/CHI queues, DCache RAM mutation, MDB,
  forwarding, BSB window-slide side effects, and memory-event trace in later
  owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `STQSCBCommitPathSpec` covers final `last`-fragment free ownership, closed
  SCB model-batch backpressure, split-store final free, concurrent older drain
  plus younger enqueue, and Chisel elaboration with `STQEntryBank`,
  `STQCommitDrain`, and `SCBRowBank` children.
- Existing SCB row-bank, STQ drain, STQ bank, STQ queue, ROB/cross-check, QEMU
  dry-run, reduced RTL xcheck, top-shell xcheck, and Verilator lint gates stay
  green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath` passed 5
  tests in `STQSCBCommitPathSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank` passed 8 tests in
  `SCBRowBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain` passed 5 tests
  in `STQCommitDrainSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests in
  `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `STQSCBCommitPath` adds the reusable
  full STQ-to-SCB free-path invariant: final committed-row frees come from
  accepted `SCBRowBank` `last` fragments, while the standalone
  `STQCommitDrain` free mask is debug-only in the full composition.

## 2026-06-28 SCB Response Decode

Scope:

- Added `SCBResponseDecode` as the raw SCB WriteResp/UpgradeResp tag owner.
- Decoded model transaction ids from `(entryIndex << 2) | 2` into
  `SCBStateUpdate.memRespEntryIndex`.
- Reported absent/ambiguous response types, wrong low-bit tag namespace,
  out-of-range entry indices, and stale responses to non-`Miss` rows before
  state update.
- Integrated the decoder into `SCBRowBank` and propagated raw response inputs
  plus decode observability through `STQSCBCommitPath`.
- Kept response queue ordering, CHI/L2 storage, MDB, forwarding, DCache RAM
  mutation, BSB window-slide side effects, and memory-event trace in later
  owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBResponseDecodeSpec` covers legal WriteResp, legal UpgradeResp, wrong
  tag, invalid response type, out-of-range index, stale target, and elaboration
  with raw plus decoded response signals.
- Existing SCB row-bank, state-update, lookup-control, STQ-to-SCB commit path,
  ROB/cross-check, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode` passed 7
  tests in `SCBResponseDecodeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank` passed 8 tests in
  `SCBRowBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate` passed 9 tests
  in `SCBStateUpdateSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl` passed 7
  tests in `SCBLookupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath` passed 5
  tests in `STQSCBCommitPathSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBResponseDecode` adds the
  reusable SCB completion invariant: raw WriteResp/UpgradeResp transaction ids
  must match `(entryIndex << 2) | 2`, name an implemented row, and target a
  valid `Miss` entry before `SCBStateUpdate` may move the row back to
  `Lookup`.

## 2026-06-28 MDB Conflict Detect

Scope:

- Added `MDBConflictDetect` as the first Chisel owner for the model
  store-arrival load/store conflict classifier behind `detect_su_lu_q`.
- Learned the model path from `StoreUnit::insertStq`,
  `LDQInfo::handleDetect`, `ResolveQ::detect`, and `LDQInfo::handleFlush`.
- Implemented scalar address-overlap plus `(bid, lsID)` age classification,
  current tile-conflict suppression, `ST_ADDR` wait-store masks for unresolved
  active loads, oldest resolved-load selection across active LDQ rows and
  `ResolveQ`, and same-BID inner versus cross-BID nuke classification.
- Kept the MDB SSIT table, lookup/result queues, store wakeup, byte
  forwarding, BCTRL `bmdb`, IEX-local MDB, ROB nuke retirement, and final
  `FlushReq` publication in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `MDBConflictDetectSpec` covers same-BID inner conflict, oldest selection
  across active LDQ rows and `ResolveQ`, younger-store rejection, `ST_ADDR`
  wait-store marking, tile suppression, zero-size non-overlap, and Chisel
  elaboration with ROB-facing conflict outputs.
- Existing STQ commit-ordering, STQ state-bank, STQ-to-SCB composition,
  ROB/cross-check, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBank` passed 7 tests
  in `STQEntryBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath` passed 5
  tests in `STQSCBCommitPathSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `MDBConflictDetect` adds the
  reusable LSU recovery invariant: store-arrival conflict detection selects the
  oldest resolved scalar load by `(bid, lsID)`, marks unresolved loads waiting
  only for `ST_ADDR`, suppresses tile conflicts until the tile owner exists,
  and classifies the selected pair as same-BID inner flush or cross-BID
  load-attributed nuke flush.

## 2026-06-28 MDB SSIT

Scope:

- Added `MDBSSIT` as the first Chisel owner for the model Memory Dependence
  Buffer Store Set ID Table.
- Learned the model path from `MDB::Work`, `MDB::ld_lookup`, `MDB::insert`,
  `MDB::dec`, and the LSU producers/consumers that enqueue lookup, record, and
  delete work.
- Implemented a finite fully associative table keyed by load PC, preserving the
  model command order of lookup, delete, then record in one cycle.
- Preserved first-after-nuke lookup suppression, confidence and weight stall
  gating, same-store reinforcement, different-store closer replacement versus
  confidence decrement, delete decay, and release only when weight is already
  zero.
- Deterministically initialized the first-insert LSID offset that the C++ model
  leaves implicit on a miss, and reported finite-table overflow instead of
  silently inventing a replacement policy.
- Kept queue wrappers, `StoreUnit::mdbCheck` wakeup, LDQ `updateMDBInfo`,
  BCTRL `bmdb`, IEX-local MDB, byte forwarding, and ROB nuke publication in
  later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only MDBSSIT
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `MDBSSITSpec` covers first-after-nuke suppression, confidence and weight
  lookup gates, reinforcement to the stall threshold, closer-store replacement,
  farther-store confidence decrement, delete decay and release, nuke-marker
  clearing on nonmatching BID lookup, illegal younger-store rejection, finite
  table overflow reporting, and Chisel elaboration.
- Existing MDB conflict detection, STQ commit ordering, ROB/cross-check, QEMU
  dry-run, reduced RTL xcheck, top-shell xcheck, and Verilator lint gates stay
  green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT` passed 7 tests in
  `MDBSSITSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `MDBSSIT` adds the reusable LSU
  dependence-prediction invariant: SSIT lookup, delete, and record commands
  must apply in model order; first-after-nuke suppresses only the first lookup
  on the recorded load BID; lookup stalls require confidence and weight gates;
  and learning must distinguish same-store reinforcement from different-store
  closer replacement and confidence decay.

## 2026-06-28 MDB Queue Fanout

Scope:

- Added `MDBQueueFanout` as the first Chisel owner for MDB queue topology
  around `MDBSSIT`.
- Learned the model path from `MDB::lookupMDB`, `MDB::handleMDBLookup`,
  `MDB::handleMDBRecord`, `MDB::handleMDBDelete`,
  `LDQInfo.updateMDBInfo`, `StoreUnit::mdbCheck`, and `STQ::mdbCheck`.
- Implemented finite lookup, delete, and record command queues plus LU and SU
  lookup-result queues.
- Preserved atomic lookup fanout: a lookup result is enqueued to both LU and
  SU outputs together, and a blocked lookup freezes delete and record phases so
  table mutation does not pass an un-fanned-out lookup.
- Exposed BMDB report intent on accepted records without mutating the BCTRL
  table in this packet.
- Added a store-side `mdbCheck` wakeup boundary: the SU side scans an abstract
  STQ row view in order, ignores tile rows, matches predicted `(bid, pc)`, and
  emits a wakeup only when the matching row has both address and data ready.
- Kept LDQ row mutation, STQ row PC sidecar integration, BCTRL/IEX MDB table
  mutation, forwarding, ROB nuke retirement, and final recovery publication in
  later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout
bash tools/chisel/run_chisel_tests.sh --only MDBSSIT
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `MDBQueueFanoutSpec` covers atomic LU/SU lookup fanout, phase freezing when a
  pending lookup cannot fan out, SU wakeup from a ready matching store row,
  pending/no-wakeup for not-ready stores, tile-row suppression, BMDB report
  intent only on accepted records, and Chisel elaboration with queue/wakeup IO.
- Existing MDB SSIT, conflict detection, STQ commit ordering, ROB/cross-check,
  QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and Verilator lint gates
  stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout` passed 5 tests
  in `MDBQueueFanoutSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT` passed 7 tests in
  `MDBSSITSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `MDBQueueFanout` adds the reusable
  LSU queue-boundary invariant: lookup results must fan out atomically to LU
  and SU, finite-output backpressure freezes later MDB phases behind a pending
  lookup, and store-side MDB wakeup uses the first matching non-tile predicted
  store row only when address and data are both ready.

## 2026-06-28 Load/Store Forwarding Selector

Scope:

- Added `LoadStoreForwarding` as the first Chisel owner for scalar
  store-to-load byte selection behind `STQ::lookupForLoad`.
- Learned the model path from `STQ::lookupForLoad`, `UpdateSTValid`,
  `UpdateData`, `ReqData::merge`, `LDQInfo::updateWaitInfo`,
  `LDQInfo::handleSTQReceive`, and `LDQInfo::checkDataPosionValid`.
- Implemented clipped 64-byte load masks, same-line scalar candidate filtering,
  load-snapshot age filtering, per-byte nearest older store selection,
  ready-byte forwarding, not-ready wait/replay masks, cache-data merge, and
  wait-store diagnostics.
- Kept STQ row mutation, store data-array banking, LDQ wait/store state
  updates, SCB/L1 hit qualification, MDB learning, recovery publication, and
  memory-event trace in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout
bash tools/chisel/run_chisel_tests.sh --only MDBSSIT
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `LoadStoreForwardingSpec` covers ready-byte forwarding, younger-than-snapshot
  suppression, per-byte newest older selection, not-ready replay masks, tile
  and different-line suppression, wrap-aware age ordering, and Chisel
  elaboration with byte masks, merge data, and wait diagnostics.
- Existing MDB fanout, SSIT, conflict detection, STQ commit ordering,
  ROB/cross-check, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding` passed 6
  tests in `LoadStoreForwardingSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout` passed 5 tests
  in `MDBQueueFanoutSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBSSIT` passed 7 tests in
  `MDBSSITSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue` passed 7 tests
  in `STQCommitQueueSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `LoadStoreForwarding` adds the
  reusable LSU forwarding invariant: Chisel must select the nearest older
  eligible store per byte, forward only selected data-ready bytes, and report
  not-ready selected bytes as replay/wait masks without mutating LDQ/STQ/SCB or
  MDB owner state.

## 2026-06-28 Load Forward Pipeline

Scope:

- Added `LoadForwardPipeline` as the first registered E2/E3/E4 wrapper around
  `LoadStoreForwarding`.
- Learned the model path from `LDQInfo::handleL1Lookup`,
  `LDQInfo::handleSCBReceive`, `LDQInfo::handleSTQReceive`,
  `LDQInfo::handleBypass`, `LDQInfo::checkDataPosionValid`, the return loop,
  and store-unit wakeup handling.
- Implemented E3/E4 register slices, final valid-mask construction, source
  return gating, return-port gating, wait-store replay classification, and
  wakeup eligibility.
- Kept LIQ/LHQ row mutation, LDQ state updates, ready-table updates, issue
  wakeup fanout, L1/SCB response queues, MDB learning, recovery publication,
  and memory-event trace in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `LoadForwardPipelineSpec` covers ready forwarded bytes registering through
  E3 and waking at E4, not-ready selected stores, incomplete baseline data,
  source-return gating, return-port gating, flush clearing resident work, and
  Chisel elaboration with the child `LoadStoreForwarding` selector.
- Existing forwarding selector, MDB fanout/conflict gates, ROB/cross-check,
  QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and Verilator lint gates
  stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline` passed 6
  tests in `LoadForwardPipelineSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding` passed 6
  tests in `LoadStoreForwardingSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout` passed 5 tests
  in `MDBQueueFanoutSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `LoadForwardPipeline` adds the
  reusable LSU E2/E3/E4 forwarding boundary: Chisel should instantiate
  `LoadStoreForwarding` in E2, register the merge and masks to E3, classify
  final byte/source/return-port readiness in E4, and avoid mutating
  LIQ/LHQ/LDQ/STQ/SCB/MDB or trace owner state in this packet.

## 2026-06-28 Load Inflight Queue

Scope:

- Added `LoadInflightQueue` as the first registered LIQ/LHQ row-state owner
  around `LoadForwardPipeline`.
- Learned the model path from `LUEntryInfo::insert`, `LDQInfo::handleInsert`,
  `LDQInfo::pickL1`, `LDQInfo::loadRepick`,
  `LDQInfo::receiveData`, `LDQInfo::waitStore`,
  `LDQInfo::returnData`, and `LDQInfo::CheckMovRslvQ`.
- Implemented slot-plus-wrap `LID` allocation, row `Wait -> Repick` launch,
  E4 hit-to-`Resolved` updates, LHQ hit-record publication, wait-store replay,
  incomplete-data `L1DcMiss` state, resolved-row clearing, and `missPending`.
- Kept precise `FlushBus` pruning, store/SCB wakeup replay, L2 miss/refill
  queues, ready-table updates, consumer bypass routing, a separate LHQ/ResolveQ
  queue, recovery publication, and memory-event trace in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `LoadInflightQueueSpec` covers slot-plus-wrap allocation, E4
  hit-to-resolved row updates, LHQ hit-record publication, wait-store replay,
  incomplete-data miss-pending behavior, source/return-port replay,
  resolved-row clearing before wraparound allocation, and Chisel elaboration
  with the child `LoadForwardPipeline`.
- Existing load-forward pipeline, forwarding selector, conflict detection,
  ROB/cross-check, QEMU dry-run, reduced RTL xcheck, top-shell xcheck, and
  Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue` passed 7
  tests in `LoadInflightQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline` passed 6
  tests in `LoadForwardPipelineSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding` passed 6
  tests in `LoadStoreForwardingSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `LoadInflightQueue` adds the
  reusable LIQ/LHQ row-owner invariant: Chisel should allocate slot-plus-wrap
  LIDs, launch only non-wait-store WAIT rows through `LoadForwardPipeline`,
  apply E4 outcomes back to row state, publish LHQ records only on E4 hits,
  and keep precise flush, refill/replay queues, ready-table updates, consumer
  bypass routing, and trace emission in later owners.

## 2026-06-28 Load Replay Wakeup

Scope:

- Added `LoadReplayWakeup` as the first store-unit/SCB replay wakeup sidecar
  for `LoadInflightQueue`.
- Learned the model path from `LDQInfo::handleSUWakeup`,
  `LDQInfo::handleSCBWakeup`, and `LDQInfo::checkDataPosionValid`.
- Implemented store-unit wait-store clear, store-unit miss-row byte merge with
  wrap-aware store ordering, SCB working-row byte merge excluding `Repick`,
  requested-byte completion masks, and LIQ row updates that return completed
  replay rows to `Wait`.
- Kept L1 refill, L2/CHI response queues, ready-table updates, consumer bypass
  routing, precise flush pruning, separate ResolveQ/LHQ movement, and memory
  trace emission in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `LoadReplayWakeupSpec` covers wait-store clear, store-unit miss-byte merge,
  younger-store suppression, SCB partial and complete merges, SCB suppression
  for `Repick` and `Resolved`, and Chisel elaboration.
- `LoadInflightQueueSpec` covers integrated store wakeup clear and completed
  miss-row return to `Wait`.
- Existing forwarding, conflict, ROB/cross-check, QEMU dry-run, reduced RTL
  xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup` passed 6
  tests in `LoadReplayWakeupSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue` passed 8
  tests in `LoadInflightQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline` passed 6
  tests in `LoadForwardPipelineSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding` passed 6
  tests in `LoadStoreForwardingSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `LoadReplayWakeup` adds the
  reusable LIQ replay-wakeup invariant: Chisel should model store-unit
  wakeups as both wait-store clear and ordered miss-byte merge, model SCB
  wakeups as working-row same-line byte merge excluding `Repick`, and keep L1
  refill, ready-table, bypass, ResolveQ/LHQ queueing, precise flush, and trace
  ownership in later packets.

## 2026-06-28 Load Refill Wakeup

Scope:

- Added `LoadRefillWakeup` as the first read-refill wakeup sidecar for
  `LoadInflightQueue`.
- Learned the model path from `LDQInfo::handleL1Wakeup`,
  `LDQInfo::pickL1`, `LDQInfo::handleL1Lookup`, and
  `clusterData::merge`.
- Implemented read-only refill acceptance, same-line working-row wake masks,
  tile/already-hit/resolved/idle suppression, local `l1Hit` row sideband,
  full-line valid-mask storage, and row-owned base-data selection for later
  relaunch through `LoadForwardPipeline`.
- Kept miss queue/prefetch-set ownership, full L1D/LDQ data-buffer ownership,
  L2/CHI response queues, ready-table updates, consumer bypass routing,
  precise flush pruning, ResolveQ/LHQ queue movement, and memory trace
  emission in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `LoadRefillWakeupSpec` covers read refill wakeup, suppression for non-read,
  tile, already-hit, resolved, idle, and different-line rows, model working-row
  coverage for `Wait`/`Repick`/`L2Wait`, and Chisel elaboration.
- `LoadInflightQueueSpec` covers integrated L1 refill wakeup and relaunch from
  row-owned line data.
- Existing replay, forwarding, conflict, ROB/cross-check, QEMU dry-run,
  reduced RTL xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup` passed 4
  tests in `LoadRefillWakeupSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue` passed 9
  tests in `LoadInflightQueueSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup` passed 6
  tests in `LoadReplayWakeupSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline` passed 6
  tests in `LoadForwardPipelineSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding` passed 6
  tests in `LoadStoreForwardingSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect` passed 7
  tests in `MDBConflictDetectSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `LoadRefillWakeup` adds the
  reusable LIQ refill/relaunch invariant: Chisel should model read refills as
  same-line working-row wake masks with local `l1Hit` and row-owned line data,
  and should keep miss queue, prefetch set, full L1D/LDQ data buffer,
  ready-table, bypass, precise flush, ResolveQ/LHQ queueing, and trace
  ownership in later packets.

## 2026-06-28 SCB Response Buffer

Scope:

- Added `SCBResponseBuffer` as the raw L2/CHI response FIFO boundary in front
  of `SCBResponseDecode`.
- Learned the model path from `SCBuffer::setMemResp`, `SCBuffer::Xfer`,
  `SCBuffer::handleMemResp`, and `SCBuffer::Work`.
- Preserved FIFO response order, ready/valid backpressure, and one-head-at-a
  time decode.
- Consumed a FIFO head only when `SCBResponseDecode` reports a legal response
  for a valid `Miss` target; wrong type, wrong tag, out-of-range, duplicate, or
  stale non-`Miss` heads stay visible instead of being silently dropped.
- Wired buffer backpressure and observability through `SCBRowBank` and
  `STQSCBCommitPath`.
- Kept the model `resp_list` priority path, DCache RAM mutation, full L2/CHI
  queue storage, MDB, forwarding, BSB window-slide side effects, and
  memory-event trace in later owner packets.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer
bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Expected result:

- `SCBResponseBufferSpec` covers FIFO order, simultaneous legal-head dequeue
  plus enqueue, illegal/stale head retention, and Chisel elaboration.
- Existing raw response decode, row-bank composition, STQ-to-SCB composition,
  state-update, lookup-control, egress, ROB/cross-check, QEMU dry-run, reduced
  RTL xcheck, top-shell xcheck, and Verilator lint gates stay green.

Observed result:

- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer` passed 4
  tests in `SCBResponseBufferSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode` passed 7
  tests in `SCBResponseDecodeSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank` passed 8 tests in
  `SCBRowBankSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath` passed 5
  tests in `STQSCBCommitPathSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate` passed 9 tests
  in `SCBStateUpdateSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl` passed 7
  tests in `SCBLookupControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect` passed 6
  tests in `SCBEgressSelectSpec`.
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
- `bash tools/chisel/run_chisel_top_xcheck.sh` emitted the reduced top shell,
  built a Verilator harness, normalized three QEMU-shaped and DUT rows, and
  compared three commits with zero mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the top shell and
  passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBResponseBuffer` adds the
  reusable SCB completion invariant: raw response queues must preserve FIFO
  order and backpressure in front of decode, and must retain illegal or stale
  heads so `SCBResponseDecode` continues to report the failing target instead
  of silently dropping it.

### R36 SCB Response Retry Selector

Implementation:

- Added `SCBResponseRetrySelect` as the model `resp_list` priority owner at the
  SCB egress boundary.
- Wired `SCBRowBank` so valid `Lookup` rows retry before ordinary `Valid`
  egress descriptors from `SCBEgressSelect`.
- Narrowed `SCBStateUpdate.acceptedIllegalMask` so retrying `Lookup` rows may
  legally finish through `freeMask` or `missMask`, while accepted-only `Lookup`
  rows remain illegal.

Verification:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetrySelect
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode
bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
```

Evidence:

- `SCBResponseRetrySelectSpec` passed 5 tests covering retry priority, first
  retry selection, normal fallback, no-candidate reporting, and elaboration.
- `SCBStateUpdateSpec` passed 11 tests including retry hit completion and
  accepted-only `Lookup` rejection.
- `SCBRowBankSpec` passed 9 tests including response-returned retry priority
  before ordinary valid-row eviction.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBResponseRetrySelect` adds the
  reusable SCB completion invariant that response-returned `Lookup` rows must
  retry before ordinary valid-row eviction, and retry `Lookup` rows are legal
  `SCBStateUpdate` finish targets only when paired with hit/free or miss masks.

### R37 SCB Response Retry Queue

Implementation:

- Added `SCBResponseRetryQueue` as the ordered row-id FIFO for model
  `SCBuffer::resp_list`.
- Wired `SCBRowBank` so legal decoded responses consume the raw response head,
  enqueue the retry row id, and apply `Miss -> Lookup` only when the retry
  queue can accept the row id.
- Updated `SCBResponseRetrySelect` to select the queued retry head, block
  normal egress on a stale head, and preserve queue order across row indices.

Verification:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetryQueue
bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetrySelect
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode
bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
```

Evidence:

- `SCBResponseRetryQueueSpec` passed 4 tests covering FIFO row-id ordering,
  simultaneous pop/enqueue space, full-state backpressure, and elaboration.
- `SCBResponseRetrySelectSpec` passed 6 tests covering queued-head priority,
  cross-index retry selection, stale-head blocking, normal fallback,
  no-candidate reporting, and elaboration.
- `SCBRowBankSpec` passed 10 tests including ordered response retry queue
  behavior across row indices.

Skill evolve:

- `skill-evolve: update linx-core` because `SCBResponseRetryQueue` closes the
  previously deferred exact `resp_list` row-id ordering invariant and adds the
  accepted-response handshake: raw response consumption, retry enqueue, and
  `Miss -> Lookup` state update must succeed together.

### R38 GPR Rename Checkpoint Cleanup

Implementation:

- Added `GPRRenameCheckpoint` as the first scalar GPR rename cleanup consumer
  behind `RecoveryCleanupControl`.
- Modeled `GPRRename` `smap`, `cmap`, per-BID checkpoints, `renamePtr`,
  physical-tag free mask, and finite mapQ rows for one scalar STID0 owner.
- Implemented model-derived `GPRRename::Flush` behavior: `restoreBid =
  flush.bid - 1`, restore from checkpoint or `cmap`, prune matching mapQ rows,
  and re-apply surviving same-BID mapQ rows for non-BID flushes.
- Kept ClockHands/T/U, SGPR replay, multi-thread rename, and full dispatch/ROB
  integration in later owner packets.

Verification:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only ROBID
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Evidence:

- `bash tools/chisel/build_chisel.sh` passed.
- `GPRRenameCheckpointSpec` passed 6 tests covering identity reset state,
  first-free rename allocation and mapQ insertion, ordered same-arch block
  commit, base-on-BID checkpoint restore/prune, non-BID same-BID survivor
  re-application, and Chisel elaboration.
- `RecoveryCleanupControlSpec` passed 6 tests.
- `FullBidRecoveryBridgeSpec` passed 4 tests.
- `ROBIDSpec` passed 3 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob` passed the
  ROBID semantic check, 3 ROBID tests, 10 CommitTrace/Monitor tests, and 5
  ReducedCommitROB tests.
- `python3 tools/chisel/trace_schema_adapter.py --self-test` passed.
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` selected
  `/Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64` and
  passed the adapter self-test.
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh` compared 3 commits with
  zero mismatches.
- `bash tools/chisel/run_chisel_top_xcheck.sh` compared 3 commits with zero
  mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the current top
  shell and passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `GPRRenameCheckpoint` adds the first
  reusable scalar rename cleanup invariant: recovery restore must use
  `flush.bid - 1`, choose checkpoint-or-`cmap` source by checkpoint validity,
  prune mapQ by model `FlushBus` ordering, and re-apply surviving same-BID
  mapQ rows for non-BID flushes.

### R39 Frontend Decode Stage

Implementation:

- Added `FrontendDecodeStage` as the first Chisel D1 decode-shape owner after
  `FrontendDecodeIngress`.
- Added generated `FrontendOpcodeDecodeTable` from pyCircuit
  `src/common/opcode_meta_gen.py`.
- Preserved the pyCircuit metadata helper rule used by `decode16_meta`,
  `decode32_meta`, `decode48_meta`, and `decode64_meta`: choose the matching
  opcode rule with the largest mask bit count, preserving source order for
  equal specificity.
- Emitted `DecodedUop` skeletons with slot PC, raw instruction, instruction
  length, opcode catalog ID, UID, parent fetch UID, checkpoint ID, and basic
  dispatch/block/load/store sidebands.
- Left operand extraction, immediate construction, LSID allocation, D2
  queueing, block header mutation, and rename/ROB admission to later owners.

Verification:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Evidence:

- `bash tools/chisel/build_chisel.sh` passed.
- `FrontendDecodeStageSpec` passed 5 tests covering generated catalog rule
  count and selected opcode IDs, most-specific mask selection, dispatch and
  block sideband classification, IO shape, and Chisel elaboration.
- `F4DecodeWindowSpec` passed 9 tests.
- `FrontendDecodeIngressSpec` passed 7 tests.
- `InterfaceBundlesSpec` passed 6 tests.
- `bash tools/chisel/run_chisel_top_xcheck.sh` compared 3 rows with zero
  mismatches.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the current top
  shell and passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `FrontendDecodeStage` adds the
  reusable frontend decode-table invariant: Chisel opcode classification must
  be generated from pyCircuit opcode metadata and must preserve the
  most-specific mask/match selection rule before operand extraction or
  decode-to-rename integration is broadened.

### R42 Decode Rename ROB Path

Implementation:

- Added `DecodeRenameROBPath` as the first reduced composition of
  `FrontendDecodeStage`, `ScalarDecodeRenameBridge`, and
  `DispatchROBAllocator`.
- Added `ScalarDecodeRenameBridge.robAllocAttemptValid` so composition owners
  can drive allocator `allocValid` from a pre-ready request qualifier while
  keeping rename mutation tied to accepted allocation.
- Stamped the selected decoded uop with temporary backend identity from the
  allocator cursors: current full block BID converted to ring `ROBID`,
  current ROB allocation value as `rid.value`, and block BID sideband.
- Left registered D2/D3 queueing, width-wide rename, LSID/SID allocation,
  store split, ready-table initialization, full block retire, and live
  top-level commit rows to later packets.

Verification:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Evidence:

- `sbt --client --error 'Test / compile'` passed.
- `DecodeRenameROBPathSpec` passed 4 tests covering first-valid slot
  selection, allocator-ready-independent allocation attempt qualification, IO
  shape, and CIRCT elaboration through frontend decode, scalar rename, and
  backend allocation.
- `ScalarDecodeRenameBridgeSpec` passed 6 tests with the new
  `robAllocAttemptValid` IO/elaboration checks.
- `FrontendDecodeStageSpec` passed 6 tests.
- `DispatchROBAllocatorSpec` passed 5 tests.
- `GPRRenameCheckpointSpec` passed 6 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob` passed the
  ROBID semantic check, 3 ROBID tests, 10 CommitTrace/Monitor tests, and 5
  ReducedCommitROB tests.
- `bash tools/chisel/run_chisel_top_xcheck.sh` compared 3 rows with zero
  mismatches.
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` selected
  `/Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64` and
  passed the trace schema adapter self-test.
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_verilator_lint.sh` emitted the current top
  shell and passed Verilator lint.

Skill evolve:

- `skill-evolve: update linx-core` because `DecodeRenameROBPath` adds the
  reusable composition invariant that allocator `allocValid` must be driven
  from a pre-ready bridge attempt signal, while rename mutation and
  `robAllocValid` remain tied to accepted allocation, avoiding ready feedback
  through ROB duplicate detection.
