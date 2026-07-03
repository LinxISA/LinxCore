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

This packet turns the standalone forwarding pipeline into reusable row state
without taking ownership of full LDQ/LIQ recovery, miss-queue ownership,
ready-table update, issue wakeup fanout, L2/CHI response queues, or
memory-event trace.

## Interface

### Allocation

| Signal | Description |
|---|---|
| `allocValid` | Requests allocation of one LIQ row. |
| `alloc` | Load identity, load LSID, PC, address, size, replay-return signedness, destination, tile flag, and `(youngestStoreId, youngestStoreLsId)` snapshot. |
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
| `e2ReturnReady` | Return/wakeup slot availability. |

The queue derives the forwarding query from the resident row address, size,
tile bit, and `(youngestStoreId, youngestStoreLsId)` snapshot.

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

### E4 Update And LHQ Record

| Signal | Description |
|---|---|
| `e4UpdateValid` | A launched row received an E4 outcome. |
| `e4UpdateIndex` | LIQ slot updated by E4. |
| `e4MissKind` | Local E4 outcome from `LoadForwardPipeline`. |
| `e4WakeupValid` | E4 can wake/return the load. |
| `lhqRecordValid` | The E4 outcome resolved as a hit and publishes an LHQ record. |
| `lhqRecord` | Load ID, row identity including load LSID and PC, line address, byte mask, final line data, and forwarded mask. |

Resolved rows remain resident until `clearResolvedValid` accepts the row. This
matches the model split where `LDQ_RESOLVED` rows later move into `ResolveQ`
and the active row is reset.

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
  states, including replay-return signedness and replay destination sidebands,
- E3/E4 row-index sideband registers aligned to `LoadForwardPipeline`,
- a combinational `LoadReplayWakeup` sidecar for store-unit/SCB replay masks,
- a combinational `LoadRefillWakeup` sidecar for read-refill row wake masks,
- a combinational LHQ hit-record output for E4 hits.

It does not own a separate LHQ queue, load-store conflict recovery, precise
flush pruning, miss queue state, SCB/STQ storage, ready-table state, consumer
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
   the active row.

`LoadInflightQueue` maps those rules into the current Chisel boundary:

1. Allocation writes a `Wait` row with a slot-plus-wrap `loadId`, preserves the
   load's own `loadLsId`, stores the compact replay destination sideband, and
   stores the `(youngestStoreId, youngestStoreLsId)` snapshot for store-forward
   filtering. R307 also stores the opcode-derived `returnSignExtend` bit beside
   address and size for the future scalar replay-return extractor.
2. Launch accepts only valid `Wait` rows with no wait-store block, changes the
   row to `Repick`, and sends the row-derived query into
   `LoadForwardPipeline`. R280 adds `LoadInflightLaunchSelect` as a separate
   pre-integration selector for replay rows that require model-style data-hit
   eligibility before driving this broader launch port.
3. E4 hit with `NoMiss` and `e4WakeupValid` changes the row to `Resolved` and
   publishes `lhqRecord` with the row PC preserved for future recovery/MDB
   reporting.
4. E4 `StoreDataNotReady` changes the row back to `Wait`, records
   `waitStoreInfo`, and clears transient byte-valid data like model
   `LUEntryInfo::rewait`.
5. E4 `DataNotComplete` changes the row to `L1DcMiss`, marks `l1Miss`, and
   asserts `missPending`.
6. E4 source-return or return-port blocking changes the row back to `Wait`
   without publishing an LHQ record.
7. Replay wakeups run through `LoadReplayWakeup`. Matching store-unit wakeups
   clear `waitStore` by BID/LSID/PC; store-unit/SCB byte merges update row data and valid
   masks; completed rows become `Wait` with `storeBypass` set so the normal
   launch path can recheck source and return-slot readiness.
8. Refill wakeups run through `LoadRefillWakeup`. Matching read-refill lines
   return rows to `Wait`, set `l1Hit`, store full-line data, and clear
   `missPending` by leaving the miss states.

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

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`

The R311 elaboration gate locks the destination sideband on the allocation and
row-observation surfaces.
- `bash tools/chisel/run_chisel_tests.sh --only LoadResolveQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover slot-plus-wrap allocation, E4 hit-to-resolved
transition and LHQ record publication including PC, not-ready store replay, incomplete-data
miss-pending behavior, source/return-port replay, store-wakeup wait-store
clear, store-wakeup miss completion, resolved-row clearing before wraparound
allocation, L1 refill wakeup, row-owned refill-data relaunch, and Chisel
elaboration with the child `LoadForwardPipeline`, `LoadReplayWakeup`, and
`LoadRefillWakeup`.
