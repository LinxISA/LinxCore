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
