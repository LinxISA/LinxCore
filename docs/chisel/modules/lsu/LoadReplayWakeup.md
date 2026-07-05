# LoadReplayWakeup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayWakeupSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-002`

## Purpose

`LoadReplayWakeup` is the first Chisel owner for store-unit and SCB replay
wakeups into resident LIQ rows. It scans the current `LoadInflightQueue` row
image, classifies which rows should clear wait-store diagnostics, merges
byte-valid wakeup data into eligible rows, and reports which rows now have all
requested load bytes.

This module intentionally stops before L1 refill ownership, ready-table
updates, consumer bypass routing, memory trace emission, and LHQ/ResolveQ
queue movement. Its output is a row-update mask surface consumed by
`LoadInflightQueue`; completed rows return to `Wait` for the normal relaunch
path instead of waking consumers directly.

## Interface

### Wakeup Request

| Signal | Description |
|---|---|
| `wakeValid` | Qualifies one replay wakeup request. |
| `wake.source` | `StoreUnit` or `StoreCoalescingBuffer`. |
| `wake.storeId` | Store BID used by store-unit ordering and wait-store clear. |
| `wake.storeLsId` | Store LSID used with `storeId` for same-BID ordering. |
| `wake.pc` | Store PC used with `(storeId, storeLsId)` to clear wait-store diagnostics. |
| `wake.lineAddr` | 64-byte line address/tag to match resident load rows. |
| `wake.validMask` | Byte lanes carried by `wake.data`. |
| `wake.data` | 64-byte line data; only `validMask` lanes are merged. |

### Row Inputs

| Signal | Description |
|---|---|
| `rows` | Current LIQ row image from `LoadInflightQueue`. |

The module does not allocate, clear, or relaunch rows. It only computes masks
and merged row payloads.

### Outputs

| Signal | Description |
|---|---|
| `waitStoreClearMask` | Rows whose wait-store blocker matches the store-unit wakeup. |
| `mergeMask` | Rows that should merge wakeup byte data. |
| `completedMask` | Merged rows whose requested load bytes are now all valid. |
| `requestByteMasks` | Recomputed requested byte mask per row from `addr` and `size`. |
| `mergedValidMasks` | `row.validMask | wake.validMask` per row. |
| `mergedLineData` | Per-byte merge of wakeup data over row line data. |

## State

`LoadReplayWakeup` is combinational and stateless. `LoadInflightQueue` owns the
registered row image and consumes the masks on the next clock edge.

## Logic Design

The C++ model provides three relevant wakeup flows:

1. `LDQInfo::handleSUWakeup` first clears `waitStore` when the waiting load's
   stored BID/LSID/TPC tuple matches the store-unit wakeup.
2. The same store-unit wakeup can merge `bus.reqData` into rows in
   `LDQ_L1_DC_MISS` or `LDQ_L2_WAIT` when the line tag matches and the store
   is older than or equal to the load's allocation snapshot.
3. `LDQInfo::handleSCBWakeup` merges SCB data into working rows with the same
   tag while excluding `LDQ_REPICK`; completion is detected with
   `checkDataPosionValid`.

The Chisel mapping is:

1. Recompute the load request byte mask from row address and size because
   `LoadInflightQueue` clears transient `loadByteMask` on incomplete-data
   replay.
2. A `StoreUnit` wakeup clears wait-store diagnostics when row
   `waitStoreInfo.storeId` and `waitStoreInfo.pc` match the wakeup and the row's
   stored LSID is either invalid or equal to the wakeup LSID. The invalid-LSID
   wildcard is required for MDB-origin waits published before native store LSID
   resolution.
3. A `StoreUnit` wakeup may merge data only into `L1DcMiss` or `L2Wait` rows
   on the same line, and only when `(wake.storeId, wake.storeLsId)` is older
   than or equal to the row's `(youngestStoreId, youngestStoreLsId)` snapshot
   using `STQCommitQueue.lessEqualBidLs`.
4. A `StoreCoalescingBuffer` wakeup may merge data into any working
   non-`Repick` row on the same line.
5. `completedMask` is asserted when the merged valid mask covers every byte in
   the recomputed load request mask.

## Timing

`LoadReplayWakeup` is a combinational sidecar. `LoadInflightQueue` applies the
computed masks after E4 row updates and resolved-row clears, then before
launch and allocation updates. A row completed by replay wakeup becomes
eligible for relaunch on the following cycle, not in the same cycle.

## Flush/Recovery

The helper has no internal flush state. `LoadInflightQueue` gates
`wakeValid` during global flush and owns row clearing. Precise load-queue
`FlushBus` pruning remains a later packet.

## Trace/Observability

The output masks are debug-visible through `LoadInflightQueue` as
`replayWakeWaitStoreClearMask`, `replayWakeMergeMask`, and
`replayWakeCompletedMask`. They are not architectural trace rows and are not
yet compared against QEMU or LinxCoreModel execution traces.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused tests cover exact wait-store clear, MDB wildcard-LSID wait-store clear,
store-unit miss-byte merge, younger-store suppression, SCB partial and complete
merges, SCB suppression for `Repick` and `Resolved` rows, and Chisel
elaboration.
