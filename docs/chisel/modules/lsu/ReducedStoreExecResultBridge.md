# ReducedStoreExecResultBridge

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreExecResultBridge.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreExecResultBridgeSpec.scala`
- Related Chisel:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/StoreSplitPayload.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::store`
    - `StoreUnit::insertStq`
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQueueEntryInfo::init`
    - `STQ::mergeStore`
    - `STQ::retire`
    - `STQ::commit`

## Purpose

`ReducedStoreExecResultBridge` is a reduced-top adapter between
`ReducedScalarAluExecute` store commit sidebands and the queue-head execution
result contract required by `StoreDispatchSTQPath`.

The existing reduced ALU computes store address, data, and size as part of the
QEMU-shaped commit trace row. `StoreDispatchSTQPath` deliberately does not
consume commit trace rows directly; it waits for explicit STA/STD execution
results matching the visible store-dispatch queue heads. This bridge buffers
reduced ALU store completions and presents a `StoreDispatchExecResult` only
when the current STA or STD queue head has the same `(bid,rid,stid)` identity.

This module does not mark stores committed, free STQ rows, issue memory
requests, mutate memory, publish MDB conflicts, or implement load forwarding.
Those remain separate LSU owners.

## Interface

Inputs:

- `flushValid`: clears all buffered reduced store results.
- `completeValid`, `completeRow`, `completeBid`, `completeRid`,
  `completeStid`: reduced ALU completion sideband. A result is captured only
  when `completeRow.mem.valid && completeRow.mem.isStore`.
- `staQueueValid`, `staQueue`: visible STA-side queue head from
  `StoreDispatchSTQPath`.
- `stdQueueValid`, `stdQueue`: visible STD-side queue head from
  `StoreDispatchSTQPath`.
- `staConsumed`, `stdConsumed`: selected/dequeue pulses from
  `StoreDispatchSTQPath`.

Outputs:

- `staExec`, `stdExec`: explicit execution results for matching queue heads.
- `completeStoreValid`: a store completion is being considered for capture.
- `captureFire`: the store completion entered the bridge buffer.
- `captureBlocked`: the store completion could not enter because the buffer
  was full.
- `captureDuplicate`: a live buffered result already has the same identity.
- `staMatch`, `stdMatch`: the current queue head has a buffered result.
- `validMask`, `bufferCount`: bridge occupancy diagnostics.

## Logic Design

The bridge stores only the data needed to satisfy `StoreDispatchExecResult`:
ROB block ID, ROB row ID, STID, address, write data, and size. PE ID, TID, and
STID on the output are taken from the matching queue payload so the STQ request
inherits the same dispatch-side thread identity as the payload.

A queue head matches a buffered result when:

1. the queue head is valid,
2. the buffered result is valid,
3. `bid` and `rid` match with full `ROBID` wrap/value comparison,
4. the buffered `stid` matches the queue payload's thread ID.

STA and STD matches are computed independently. If both halves of a split store
are present, `StoreDispatchToSTQ` still owns the one-request-per-cycle choice
and gives STA priority. The bridge therefore keeps a split-store result after
STA consumption and clears it after STD consumption. For an unsplit store, STA
consumption clears the result immediately because `StoreSplitStoreType.All`
has no STD half.

Capture is first-free-slot. Duplicate completions are reported and not
recaptured. Overflow is reported as `captureBlocked`; the completion is not
silently converted into a queue-head result.

## Model Alignment

The C++ model separates store dispatch and STQ mutation:

- `StoreUnit::store` drains STA work and STD work from execution queues.
- `STQ::mergeStore` allows a split half to merge with an existing row by store
  identity.
- `STQueueEntryInfo::init` marks address/data readiness according to
  `ST_ADDR`, `ST_DATA`, or `ST_ALL`.
- `STQ::retire` and `STQ::commit` are later lifecycle steps.

The bridge preserves that separation for the reduced top. It only supplies the
execution-result side of the dispatch queue contract; it does not collapse
retire/commit/free into ALU completion.

## Deferred Owners

- A commit-index owner mapping ROB commit/store rows to STQ indices.
- A store commit drain owner in the reduced top using `STQCommitDrain` or the
  registered SCB path.
- SCB/MDB integration and memory-side request acceptance.
- Load-store forwarding and replay wakeup from resident STQ rows.
- Stack-valid and non-scalar execution metadata beyond the current reduced
  scalar sideband.

## Verification

Focused gate:

```bash
sbt "testOnly linxcore.lsu.ReducedStoreExecResultBridgeSpec"
```

Integrated gate used for R239:

```bash
sbt "testOnly linxcore.lsu.ReducedStoreExecResultBridgeSpec linxcore.top.LinxCoreFrontendFetchRfAluTraceTopSpec"
```

The tests cover store capture, queue-head mismatch, split-store retention
after STA consumption, unsplit clearing after STA consumption, overflow
reporting, bridge elaboration, and top-level diagnostic elaboration.
