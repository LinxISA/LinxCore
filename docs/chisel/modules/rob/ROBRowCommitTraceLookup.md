# ROBRowCommitTraceLookup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBRowCommitTraceLookup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBRowCommitTraceLookupSpec.scala`
- Integrated users:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex.cpp`: `IEX::setMemData`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`: `ROBState::resolveData`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`: return request metadata
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`: returned-load sidebands
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBRowStatusLookup.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowTraceSource.scala`
- Contract IDs: `LC-IF-CHISEL-ROB-ROW-COMMIT-TRACE-001`

## Purpose

`ROBRowCommitTraceLookup` is the read-only ROB row payload provider for future
replay-load W2 commit-row replacement. It mirrors the native RID/epoch safety
policy from `ROBRowStatusLookup`, then exposes only provider-shaped row fields:
instruction raw bits, instruction length, and optional source operand traces.

R373 wires the lookup through `ROBEntryBank`, `DispatchROBAllocator`, and
`DecodeRenameROBPath` into the reduced replay-W2 top. The top drives the query
from the resident W2 slot RID and feeds instruction raw/length into
`LoadReplayReturnPipeW2CommitRowTraceSource`. Source traces remain disabled
with `sourceTraceEnable=false`, so row fill still cannot become live.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `queryValid` / `queryRid` | Native ROB RID lookup request. Both value and wrap are checked. |
| input | `rowValidMask` / `rowRid` / `rowStatus` | ROB-owned occupancy, stored native RID, and status vectors. |
| input | `rows` | ROB-owned `CommitTraceRow` payload vector. |
| input | `sourceTraceEnable` | Policy gate for exposing `src0/src1` as a trace provider. |
| output | `result.rowValid` / `ridMatch` / `status` / `needFlush` | Current-row match and status diagnostics. |
| output | `result.row` | Zeroed unless the RID matches a resident row. |
| output | `instructionProviderValid` / `instructionRaw` / `instructionLen` | Instruction metadata provider for the W2 trace-source owner. |
| output | `sourceTraceProviderValid` / `source0` / `source1` | Optional source trace provider, held disabled in R373 top wiring. |
| output | blocker signals | Invalid RID, free slot, stale RID, NeedFlush, missing instruction, and source-trace-disabled diagnostics. |

## Logic Design

```text
index = queryRid.value
slotRidMatch = rowValidMask[index] && rowRid[index] == queryRid
rowValid = queryValid && queryRid.valid && slotRidMatch
needFlush = rowValid && rowStatus[index] == NeedFlush
liveRow = rowValid && rows[index].valid && !needFlush
instructionProviderValid = liveRow && rows[index].len != 0
sourceTraceProviderValid = liveRow && sourceTraceEnable
```

The lookup does not mutate ROB state, status, row payloads, RF state, ready
tables, or replay-LIQ rows. `NeedFlush` suppresses both provider paths. A
resident row with zero instruction length or an invalid row payload reports
`blockedByMissingInstruction` instead of forwarding stale row contents.

## Integration

`ROBEntryBank` instantiates the lookup beside `ROBRowStatusLookup`. The
backend allocator and decode/rename path forward the request and result without
interpretation.

`LinxCoreFrontendFetchRfAluTraceTop` uses the W2 slot RID as the query source:

- `instructionProviderValid/raw/len` feed `LoadReplayReturnPipeW2CommitRowTraceSource`;
- `sourceTraceEnable` is tied false because source data still needs a real
  source-trace owner;
- W2 row fill remains disabled by missing source trace and the existing R367
  row-fill enable control;
- replay ROB completion, RF writeback, ROB/PE resolve mutation, wakeup, W2
  clear/refill, and replay-row lifecycle clear remain dormant.

## Deferred Owners

- Source operand trace provider that can prove replay-time source data.
- ROB/PE replay resolve mutation and lane/TLOAD completion accounting.
- Live W2 row-fill enable promotion after source trace, side effects, clear,
  and replay-row lifecycle mutation are all atomic.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ROBRowCommitTraceLookup
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnPipeW2CommitRowTraceSource
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r373x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover live matched-row metadata, disabled source trace,
invalid RID, free slot, stale RID, NeedFlush suppression, missing instruction
metadata, and Chisel elaboration.
