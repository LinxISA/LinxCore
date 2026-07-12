# LoadInflightQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightQueueSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
  - `model/LinxCoreModel/model/core/Packet.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadRefillWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationWriteControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-001`

## Purpose

`LoadInflightQueue` is the first registered LIQ/LHQ row-state owner in the
Chisel LSU lane. It allocates load-inflight rows with slot-plus-wrap load IDs
and the compact replay destination sideband, launches selected WAIT rows
through `LoadForwardPipeline`, applies E4
hit/miss/replay outcomes back to the row image, and publishes an LHQ-style
resolved-load record when E4 can wake and return the load. It also consumes
`LoadReplayWakeup` masks to clear store-unit wait-store blockers and to merge
store-unit/SCB replay bytes into resident rows. It consumes
`LoadRefillWakeup` masks to return refilled miss rows to `Wait` with a local
`l1Hit` sideband and row-owned cacheline data.

R375 adds row-owned source-trace fields to allocation and residency. These
fields preserve original RF-returned load source operands through replay
residency for later W2 commit-row reconstruction; they are not used for
forwarding, hit/miss classification, or returned-load data extraction.

R411 adds row-carried split source-return diagnostics `scbReturned` and
`stqReturned`. They are visible in `rows` for the future local STQ
row-mutation writer, but `sourcesReturned` remains the active launch/return
summary bit until row-owned STQ response application is enabled.

R416 adds a native row-mutation storage boundary inside the queue. It uses the
R415 `LoadInflightRowMutationPath` composition with the queue's native
`storeEntries` wait-store shape, computes same-row conflicts against the
registered LIQ writers, and writes the previewed `nextRow` only when the path
admits the mutation. R417 connects this native port through
`ReducedLoadReplayLiqAllocPath` and `LoadInflightRowMutationRequestBridge`,
but the source-shaped request owner remains live-disabled in the top.

R418 threads `e2StqReturned` through the launch pipeline and stores the
delayed E4 value into the row's `stqReturned` bit. This preserves the existing
coarse `sourcesReturned` behavior as the conjunction of load-data, SCB, and
STQ/store-source return bits while making the row-carried split diagnostics
consistent before replay-STQ mutation is live-enabled.

R487 adds a direct SCB-return proof port for rows already in `Repick`.
LinxCoreModel handles SCB receive before STQ receive and records `scbRnt` on
`LDQ_REPICK` rows even when no cache data is carried. The Chisel port lets the
source-return accepted-token owner mark that proof without pretending the row
received a replay-wakeup byte merge or an E2 forwarding result.

R489 widens the existing `clearResolved` input to accept a completed
source-returned `Repick` row as well as an explicit `Resolved` row. This
matches the model return loop where a complete `LDQ_REPICK` entry can call
`returnData`, become resolved, move through `CheckMovRslvQ`, and reset the
active LDQ entry without requiring a second wait-row relaunch.

R634 adds row-owned PE/STID/TID and a typed `preciseFlush` input. The queue
uses the same Linx scope and BID/group/LSID comparison as ResolveQ, prunes only
matching rows, cancels E3/E4 residency, returns surviving pipeline rows to
`Wait`, and rebases allocation to the first pruned slot with a changed load-ID
generation. `ScalarLSULoadPath` is now the canonical parent for LIQ-to-ResolveQ
transfer and source-row clear.

This packet turns the standalone forwarding pipeline into reusable row state
without taking ownership of full LDQ/LIQ recovery, miss-queue ownership,
ready-table update, issue wakeup fanout, L2/CHI response queues, or
memory-event trace.

## Interface

### Allocation

| Signal | Description |
|---|---|
| `allocValid` | Requests allocation of one LIQ row. |
| `alloc` | Load identity, load LSID, PC, address, size, replay-return signedness, destination, source traces, tile flag, and `(youngestStoreId, youngestStoreLsId)` snapshot. |
| `allocReady` | The allocation pointer names a free row. |
| `allocAccepted` | The request allocated the current pointer row. |
| `allocIndex` | Current LIQ slot. |
| `allocLoadId` | Slot-plus-wrap `LID` assigned to the row. |

Allocation is ring-based, not first-free. This preserves the hardware
`LID = slot + wrap` contract and stalls if the allocation pointer names a
resident row, even when a later slot is free.

### Launch And Forwarding

| Signal | Description |
|---|---|
| `launchValid` | Requests an E2 launch for an existing row. |
| `launchIndex` | LIQ slot to launch. |
| `launchReady` | The row is valid, WAIT, and not wait-store blocked. |
| `launchAccepted` | The row entered E2 and changed to `Repick`. |
| `e2Stores` | Abstract STQ candidate rows consumed by `LoadForwardPipeline`. |
| `e2BaseData` | Baseline 64-byte line data from LDQ/L1/SCB response paths. |
| `e2BaseValidMask` | Baseline byte-valid mask. |
| `e2LoadDataReturned` | Model `(ldqRnt || l1Rnt)` equivalent. |
| `e2ScbReturned` | Model `scbRnt` equivalent. |
| `e2StqReturned` | Model `stqRnt` or local store-source equivalent. |
| `e2ReturnReady` | Return/wakeup slot availability. |

The queue derives the forwarding query from the resident row address, size,
tile bit, and `(youngestStoreId, youngestStoreLsId)` snapshot.

### Pick Without Forwarding

| Signal | Description |
|---|---|
| `pickValid` | Requests the model `pickL1`/`loadRepick` state transition for an existing row without entering the E2 forwarding pipeline. |
| `pickIndex` | LIQ slot to mark `Repick`. |
| `pickReady` | The row is valid, `Wait`, and not wait-store blocked. |
| `pickAccepted` | The row changed to `Repick` through the pick-only path. |

R486 adds this split because LinxCoreModel marks a selected `LDQ_WAIT` row
`LDQ_REPICK` before store-source responses are consumed, while the original
Chisel `launchAccepted` path also started `LoadForwardPipeline`. The pick-only
path gives source-return owners a model-equivalent `Repick` row without
running E4 early with missing STQ/SCB evidence.

### SCB Return Proof

| Signal | Description |
|---|---|
| `scbReturnValid` | Requests a model `handleSCBReceive` proof for an already picked replay row. |
| `scbReturnIndex` | LIQ slot to mark SCB-returned. |
| `scbReturnReady` | The target row is valid, `Repick`, not already SCB-returned, and no flush is active. |
| `scbReturnAccepted` | The row's `scbReturned` bit will be set this cycle. |

This port is intentionally narrower than `replayWakeValid`: it does not merge
bytes, does not clear wait-store state, and does not complete a miss row. It
only records the SCB ordering proof required before an ordered STQ response can
mutate a `Repick` row.

### Replay Wakeup

| Signal | Description |
|---|---|
| `replayWakeValid` | Qualifies one store-unit or SCB replay wakeup. |
| `replayWake` | Source, store BID/LSID, PC, line address, byte-valid mask, and line data. |
| `replayWakeWaitStoreClearMask` | Rows whose wait-store diagnostics were matched. |
| `replayWakeMergeMask` | Rows selected for wakeup byte merge. |
| `replayWakeCompletedMask` | Rows whose requested load bytes became complete. |

Store-unit wakeups can clear wait-store diagnostics and can merge data into
`L1DcMiss` or `L2Wait` rows when `(storeId, storeLsId)` is no newer than the
row's `(youngestStoreId, youngestStoreLsId)` snapshot. SCB wakeups can merge data into working
non-`Repick` rows on the same line. Completed rows return to `Wait` and relaunch
through `LoadForwardPipeline`; they do not publish an LHQ record directly.

### Refill Wakeup

| Signal | Description |
|---|---|
| `refillValid` | Qualifies one L1 read-refill packet. |
| `refill` | Read flag, line address, line data, and L2-miss metadata. |
| `refillAccepted` | The refill is valid and read-typed. |
| `refillWakeMask` | Rows returned to `Wait` by the refill line. |

Refill wakeups match working, non-tile rows on line address, suppress rows
that already have `l1Hit`, and store the full refill line plus full valid mask
in the LIQ row. The next launch uses row-owned data when `validMask` is
nonzero; otherwise it uses the external `e2BaseData`/`e2BaseValidMask` inputs.

### Row Mutation

| Signal | Description |
|---|---|
| `rowMutationValid` | Native replay-STQ row mutation request. |
| `rowMutationTargetIndex` | LIQ slot selected by the future request owner. |
| `rowMutationSetWaitStatus` / `rowMutationKeepRepickStatus` | Status intent for wait-store rewait or continued repick. |
| `rowMutationClearReturnState` | Clears coarse and split source-return state for wait-store rewait. |
| `rowMutationLineWrite` / `rowMutationWaitStoreWrite` | Enables line-image and wait-store updates in the row-image apply path. |
| `rowMutationNextWaitStore` / `rowMutationNextWaitStoreInfo` | Native LIQ wait-store payload. |
| `rowMutationNextLineData` / `rowMutationNextValidMask` / `rowMutationNextDataComplete` | Future row line image and completion state. |
| `rowMutationNextScbReturned` / `rowMutationNextStqReturned` / `rowMutationNextStoreSourceReturned` | Split and coarse source-return state for data-merge or no-data return. |
| `rowMutationBridgeValid` | Request survived the queue-internal native bridge. |
| `rowMutationTargetEvidenceValid` | Target row is valid, `Repick`, and SCB-returned. |
| `rowMutationWriteConflict` | Same-row E4, clear-resolved, replay wakeup, refill, launch, or allocation conflict. |
| `rowMutationWriteEnable` / `rowMutationApplyValid` | The queue will write the previewed next row this cycle. |
| `rowMutationBlockedBy*` | Stage-level and per-conflict diagnostics from the R415 path. |

The port is native to `LoadInflightQueue`: `rowMutationNextWaitStoreInfo`
already uses the queue's `storeEntries` domain. The R410 source-shaped request
owner still needs an upstream bridge/live-enable packet before the reduced top
can drive this port.

### E4 Update And LHQ Record

| Signal | Description |
|---|---|
| `e4UpdateValid` | A launched row received an E4 outcome. |
| `e4UpdateIndex` | LIQ slot updated by E4. |
| `e4MissKind` | Local E4 outcome from `LoadForwardPipeline`. |
| `e4WakeupValid` | E4 can wake/return the load. |
| `lhqRecordValid` | The E4 outcome resolved as a hit and publishes an LHQ record. |
| `lhqRecord` | Load ID, row identity including load LSID and PC, line address, byte mask, final line data, and forwarded mask. |

An E4 hit remains `Repick` while it publishes `lhqRecord`; this prevents the
active row from appearing terminal before the return owner accepts its side
effects. `markResolvedValid` may explicitly mark it `Resolved`, while
`clearResolvedValid` may directly clear a complete `Repick` row whose coarse,
SCB, and STQ source-return bits are all set. This models `returnData` followed
by `CheckMovRslvQ` without adding a false intermediate ownership point.

### Observability

| Signal | Description |
|---|---|
| `rows` | Full LIQ row image for later composition and tests. |
| `occupiedMask` | Resident rows. |
| `waitMask` | Rows in `Wait`. |
| `repickMask` | Rows in `Repick`. |
| `missMask` | Rows in `L1DcMiss` or `L2Wait`. |
| `resolvedMask` | Rows in `Resolved`. |
| `waitStoreMask` | Rows waiting for a not-ready store. |
| `residentCount` | Number of resident rows. |
| `missPending` | Any resident miss row, including the current E4 data-incomplete outcome. |

## State

The module owns:

- a ring allocation pointer and wrap bit,
- a resident-count register,
- LIQ row records with `Wait`, `Repick`, `L1DcMiss`, `L2Wait`, and `Resolved`
  states, including replay-return signedness, replay destination, and R375
  source-trace sidebands plus R411 split SCB/STQ return diagnostics,
- E3/E4 row-index sideband registers aligned to `LoadForwardPipeline`,
- a combinational `LoadReplayWakeup` sidecar for store-unit/SCB replay masks,
- a combinational `LoadRefillWakeup` sidecar for read-refill row wake masks,
- a combinational `LoadInflightRowMutationPath` sidecar for native replay-STQ
  row mutation admission and next-row preview,
- a one-cycle SCB-return proof writer for `Repick` rows selected by the
  source-return accepted-token path,
- a combinational LHQ hit-record output for E4 hits.

It does not own a separate LHQ queue, load-store conflict recovery, miss queue
state, SCB/STQ storage, ready-table state, consumer
bypass data routing, or trace emission.

## Logic Design

The C++ model provides the owner split:

1. `LUEntryInfo::insert` creates a working `LDQ_WAIT` row and clears `ReqData`.
2. `pickL1` selects eligible WAIT rows that are not wait-store blocked and
   `loadRepick` changes them to `LDQ_REPICK`.
3. `handleL1Lookup`, `handleSCBReceive`, `handleSTQReceive`, and
   `handleMerge` merge byte-position-valid data into the row's `ReqData`.
4. The return loop checks `checkDataPosionValid` and source-return state.
   Complete data returns through `returnData` and changes the row to
   `LDQ_RESOLVED`; incomplete bytes become `LDQ_L1_DC_MISS`; not-ready store
   data calls `waitStore` and returns the row to `LDQ_WAIT`.
5. `handleSUWakeup` clears matching wait-store rows and can merge older
   store-unit data into `LDQ_L1_DC_MISS` or `LDQ_L2_WAIT` rows.
6. `handleSCBWakeup` merges SCB data into working same-line rows that are not
   currently `LDQ_REPICK`.
7. `handleL1Wakeup` erases the refill line from miss/prefetch tracking,
   merges the cacheline into cluster data, and returns matching unresolved
   scalar rows to `LDQ_WAIT` with `l1Hit=true`.
8. `CheckMovRslvQ` later moves `LDQ_RESOLVED` rows into `ResolveQ` and resets
   the active row. R489 permits the Chisel clear port to consume an already
   complete source-returned `Repick` row directly when the top-level replay
   return path has published the returned load but no E4 relaunch is needed.
9. Replay-STQ response mutation uses the R415 path to avoid same-cycle
   ambiguity with the current registered row writers before applying the R412
   row-image update.

`LoadInflightQueue` maps those rules into the current Chisel boundary:

1. Allocation writes a `Wait` row with a slot-plus-wrap `loadId`, preserves the
   load's own `loadLsId`, stores the compact replay destination sideband, and
   stores the `(youngestStoreId, youngestStoreLsId)` snapshot for store-forward
   filtering. R307 also stores the opcode-derived `returnSignExtend` bit beside
   address and size for the future scalar replay-return extractor. R375 stores
   the RF-derived `sourceTraceValid/source0/source1` sideband beside that
   identity; the queue carries it as provenance only and does not synthesize
   source data from row metadata.
2. Launch accepts only valid `Wait` rows with no wait-store block, changes the
   row to `Repick`, and sends the row-derived query into
   `LoadForwardPipeline`. R280 adds `LoadInflightLaunchSelect` as a separate
   pre-integration selector for replay rows that require model-style data-hit
   eligibility before driving this broader launch port. R486 adds a separate
   pick-only port for the same `Wait` to `Repick` transition when source-return
   owners need a selected `Repick` row before final replay launch/readiness.
3. R487 accepts `scbReturnValid` only for valid `Repick` rows that have not
   already recorded `scbReturned`. The write conflicts with same-row native
   row mutation so the model `scbRnt` proof cannot race an STQ response apply.
4. E4 hit with `NoMiss` and `e4WakeupValid` keeps the row in `Repick` and
   publishes `lhqRecord` with the row PC preserved for recovery/MDB reporting.
   R418 stores the delayed pipeline SCB and STQ/store source-return bits in
   `scbReturned` and `stqReturned`, while `sourcesReturned` remains the combined
   active readiness bit. `markResolved` or the accepted ResolveQ transfer/clear
   owner performs the terminal transition.
5. E4 `StoreDataNotReady` changes the row back to `Wait`, records
   `waitStoreInfo`, and clears transient byte-valid data like model
   `LUEntryInfo::rewait`. R411 also clears split return diagnostics on this
   rewait path.
6. E4 `DataNotComplete` changes the row to `L1DcMiss`, marks `l1Miss`, and
   asserts `missPending`.
7. E4 source-return or return-port blocking changes the row back to `Wait`
   without publishing an LHQ record.
8. Replay wakeups run through `LoadReplayWakeup`. Matching store-unit wakeups
   clear `waitStore` by BID/LSID/PC; store-unit/SCB byte merges update row data and valid
   masks; completed rows become `Wait` with `storeBypass` set so the normal
   launch path can recheck source and return-slot readiness. R411 records
   source-specific diagnostic completion in `stqReturned` for store-unit
   wakeups and `scbReturned` for SCB wakeups without changing the coarse
   `sourcesReturned` behavior.
9. Refill wakeups run through `LoadRefillWakeup`. Matching read-refill lines
   return rows to `Wait`, set `l1Hit`, store full-line data, and clear
   `missPending` by leaving the miss states.
10. Native row mutations run through `LoadInflightRowMutationPath`. The queue
   synthesizes the target one-hot mask from `rowMutationTargetIndex`, feeds the
   current row image into the path, computes conflicts against E4,
   clear-resolved, replay wakeup, refill, launch, SCB-return, and allocation
   writers, and writes `nextRow` only when `rowMutationWriteEnable` is true.
11. `clearResolvedValid` resets either a `Resolved` row or an R489 complete
    `Repick` row with `dataComplete`, coarse source-return, SCB-return, and
    STQ-return proofs set and no wait-store blocker. Incomplete or
    wait-store-blocked `Repick` rows remain resident.
12. R675 marks scalar rows whose original address and size cross one line.
    `secondSegmentActive` selects the next aligned line and a phase-local
    offset/size for forwarding, replay, refill, and miss ownership. A complete
    first phase is retained in `firstLineData` plus byte-valid/forward masks,
    returns the row to `Wait`, and cannot publish `lhqRecord`. Only a complete
    second phase publishes the one architectural hit record. Hard flush clears
    both phases; a precise-flush survivor retains its completed first phase and
    retries any canceled active phase.

## Timing

- Cycle N: `launchAccepted` marks a row `Repick` and presents its query to E2.
- Cycle N+1: `LoadForwardPipeline` carries the row's masks and merged line in
  E3.
- Cycle N+2: `e4UpdateValid` updates the row and may publish `lhqRecord`.
- Replay wakeups are consumed after E4 row update/clear handling and before
  new launch/allocation state updates. Completed replay rows are not launched
  until the next cycle.
- Refill wakeups are consumed after replay wakeups and before new
  launch/allocation state updates. A refilled row is not launched until the
  next cycle.
- Native row mutation writes after launch/allocation scheduling in the
  sequential block, but only when conflict checks prove no same-row E4,
  clear-resolved, replay wakeup, refill, launch, or allocation writer fired.
  The write does not change `residentCount`.

The module accepts at most one launch per cycle. Later owners may add internal
pick arbitration, multiple load pipes, and true response queues.

## Flush/Recovery

`flush` currently clears all LIQ rows and the local E3/E4 row-index sidebands.
This is a bring-up global clear, not the final precise load-queue flush owner.
Precise `FlushBus` pruning, LSID rebasing, LHQ/ResolveQ pruning, nuke
publication, and recovery checkpoint integration remain future packets.

## Trace/Observability

`lhqRecord` is the first resolved-load observation surface for future
load/store conflict checks and now carries load PC for the R284
`LoadResolveQueue` conflict-row view. It is not yet a QEMU/DUT memory trace. Full
replacement evidence still requires later top-level load-result trace rows and
memory comparison against QEMU/model behavior.
The R375 source-trace fields are visible in `rows` and through the launch
selector, but are deliberately not a replay W2 source provider until the W2
slot/LRET payload carries them to `LoadReplayReturnPipeW2CommitRowTraceSource`.

## R672 Full-LSID Contract

Allocation and every resident LIQ row carry projected LSID only as a legacy
sideband plus explicit full-LSID validity/value fields. LHQ publication and
native row mutation preserve the full fields. Typed same-BID cleanup requires
full authority and uses modular `LSIDOrder`; it does not reconstruct from the
projection.

The E2/E3/E4 forwarding path preserves the selected not-ready store's full
LSID through `LoadStoreForwardStore` and `LoadStoreForwardWait`. Therefore
`StoreDataNotReady` cannot create a canonical LIQ wait row that lacks the
authority later consumed by timeout delete or recovery. The LIQ launch also
drives its resident youngest-store full snapshot into forwarding; same-BID age
and nearest-store selection use that value and fail closed without authority.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightRowMutationPath`

The R311 elaboration gate locks the destination sideband on the allocation and
row-observation surfaces.
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover slot-plus-wrap allocation, E4 hit publication
transition and LHQ record publication including PC, not-ready store replay, incomplete-data
miss-pending behavior, source/return-port replay, store-wakeup wait-store
clear, store-wakeup miss completion, direct SCB-return proof for a `Repick`
row, resolved-row clearing before wraparound, completed-`Repick` row clearing,
allocation, L1 refill wakeup, row-owned refill-data relaunch, native
row-mutation writes and blockers, and Chisel elaboration with the child
`LoadForwardPipeline`, `LoadReplayWakeup`, `LoadRefillWakeup`, and
`LoadInflightRowMutationPath`.
