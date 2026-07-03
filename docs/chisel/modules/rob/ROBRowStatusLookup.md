# ROBRowStatusLookup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBRowStatusLookup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBRowStatusLookupSpec.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.h`
  - `model/LinxCoreModel/model/iex/iex.cpp`
    - `IEX::setMemData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDataCandidate.scala`
- Contract IDs: `LC-IF-CHISEL-ROB-ROW-STATUS-001`

## Purpose

`ROBRowStatusLookup` is the read-only current-ROB row status probe used by
future returned-load mutation owners. In LinxCoreModel, `IEX::setMemData`
first indexes `rob_current[mem.rid.val]` and returns immediately when that row
is `INST_NEEDFLUSH`; only after that check does it mutate `rob_next` with
`resolveData` and returned-load side effects.

R321 names that current-row status boundary in Chisel. The lookup accepts a
native `ROBID`, checks that the slot is occupied and that the stored row RID
matches the requested RID epoch, then returns the row status and a
`needFlush` predicate. It does not allocate, complete, retire, deallocate,
flush, or mutate ROB state.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `queryValid` | Enables one combinational lookup request. |
| input | `queryRid` | Native ROB RID to probe. Both `value` and `wrap` are checked. |
| input | `rowValidMask` | Occupied row mask from the owning ROB bank. |
| input | `rowRid` | Stored native RID for each ROB slot. |
| input | `rowStatus` | Stored `ROBEntryStatus` for each ROB slot. |
| output | `result.queryValid` | Copy of the request-valid input. |
| output | `result.rowValid` | True only when the requested RID is valid, occupied, and epoch-matched. |
| output | `result.ridMatch` | Occupied slot matched the requested native RID. |
| output | `result.status` | Row status when `rowValid`; otherwise `Free`. |
| output | `result.needFlush` | `rowValid && status == ROBEntryStatus.NeedFlush`. |
| output | `result.blockedByInvalidRid` | Request was valid but `queryRid.valid` was false. |
| output | `result.blockedByFree` | Request named an unoccupied/free slot. |
| output | `result.blockedByStaleRid` | Slot was occupied but the stored RID epoch did not match. |

## Logic Design

```text
index = queryRid.value
slotOccupied = rowValidMask[index]
slotRidMatch = slotOccupied && rowRid[index] == queryRid
rowValid = queryValid && queryRid.valid && slotRidMatch
status = rowValid ? rowStatus[index] : Free
needFlush = rowValid && status == NeedFlush
```

The wrap/epoch comparison is stricter than the C++ model's direct array index,
because the Chisel status probe can be consumed independently of the ROB owner.
Without the epoch check, a stale returned-load RID could accidentally observe a
newer row that reused the same slot value.

## Integration

`ROBEntryBank` instantiates the lookup and feeds it from the bank's pre-cycle
row valid mask, stored native RIDs, and status vector. `DispatchROBAllocator`
and `DecodeRenameROBPath` forward the query/result without interpretation.
`LinxCoreFrontendFetchRfAluTraceTop` queries the path with the LRET sink head
RID and feeds only `rowValid` and `needFlush` into
`LoadReplayReturnIexDataCandidate`.

The current top still keeps `LoadReplayReturnLretSink.drainReady` tied low and
the IEX return-pipe permit blocked, so the query cannot cause an LRET FIFO
drain or a ROB/RF/ready-table mutation.

## Deferred Owners

- Returned-load `rob_next.resolveData` mutation.
- Scalar load-pair lane accounting and vector/MEM_IEX request completion.
- Ready-table update, issue wakeup, and RF writeback side effects.
- Return-pipe E4 residency and LRET FIFO drain enable.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ROBRowStatusLookup
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r321-replay-rob-row-status-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover matching non-free rows, `NeedFlush` rows, invalid RID
blocking, free-slot blocking, stale RID blocking, and Chisel elaboration.
