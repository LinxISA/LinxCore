# LoadForwardPipeline

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadForwardPipelineSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.h`
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
  - `model/LinxCoreModel/model/core/Packet.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadStoreForwarding.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBQueueFanout.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- Contract IDs: `LC-CHISEL-LSU-LD-FWD-PIPE-001`

## Purpose

`LoadForwardPipeline` is the first registered Chisel owner for the scalar
load-forwarding E2/E3/E4 timing boundary. It instantiates
`LoadStoreForwarding` in E2, registers the byte-selection result into E3, and
registers the final E4 line data, valid mask, wakeup eligibility, and
replay/miss classification.

This packet starts the integration path from the standalone byte selector
toward LIQ/LHQ/STQ load execution. It still stops before LIQ allocation, LHQ
hit recording, LDQ row mutation, ready-table updates, issue-queue wakeup
fanout, L1/SCB response queues, and memory-event trace.

## Interface

### E2 Inputs

| Signal | Description |
|---|---|
| `flush` | Clears resident E3/E4 work. Future recovery owners drive this from registered flush state. |
| `e2Valid` | A load forwarding query enters E2. |
| `e2Query` | `LoadStoreForwardQuery` passed to the byte selector. |
| `e2Stores` | Abstract STQ candidate rows passed to the byte selector. |
| `e2BaseData` | Baseline 64-byte line data from LDQ/L1/SCB response paths. |
| `e2BaseValidMask` | Position-valid bytes already present in the baseline data. |
| `e2LoadDataReturned` | Model `(ldqRnt || l1Rnt)` equivalent for the load-data source. |
| `e2ScbReturned` | Model `scbRnt` equivalent. |
| `e2ReturnReady` | Return/wakeup slot availability for the E4 result. |

### E3 Outputs

| Signal | Description |
|---|---|
| `e3Valid` | Registered E2 query is resident in E3. |
| `e3LoadByteMask` | Requested byte lanes from the forwarding selector. |
| `e3ForwardMask` | Ready store-forward bytes selected in E2. |
| `e3WaitMask` | Selected store bytes blocked by not-ready store data. |
| `e3MergedData` | Baseline line data with forwarded bytes overlaid. |

### E4 Outputs

| Signal | Description |
|---|---|
| `e4Valid` | Registered E3 work is resident in E4. |
| `e4LineData` | Final 64-byte line image after store-forward merge. |
| `e4ValidMask` | `e2BaseValidMask | e3ForwardMask`. |
| `e4LoadByteMask` | Requested load bytes checked by `checkDataPosionValid` equivalent logic. |
| `e4ForwardMask` | Forwarded bytes contributing to E4. |
| `e4WaitMask` | Not-ready selected store bytes contributing to replay. |
| `e4DataComplete` | All requested bytes are position-valid. |
| `e4LoadDataReturned` | Delayed model `(ldqRnt || l1Rnt)` equivalent for row-carried diagnostics. |
| `e4ScbReturned` | Delayed model `scbRnt` equivalent for row-carried diagnostics. |
| `e4SourcesReturned` | Load-data source and SCB source have both returned. |
| `e4WakeupValid` | E4 can wake/return this load result. |
| `e4WaitStore` | Blocking not-ready store diagnostic from `LoadStoreForwarding`. |
| `e4MissKind` | Local classification: no miss, store-data replay, incomplete data, awaiting source response, or return-port block. |

## State

The module owns one E3 register slice and one E4 register slice. It does not
own LIQ/LHQ row arrays, STQ rows, SCB rows, load replay queues, or wakeup
fabric state.

## Logic Design

The model `LDQInfo` path has three relevant rules:

1. `handleL1Lookup` sends the load to SCB/STQ lookup and either inserts LDQ/L1
   data into `ReqData` or waits for L1.
2. `handleSCBReceive`, `handleSTQReceive`, `handleBypass`, and store wakeups
   merge `ReqData` byte-position information.
3. The return loop calls `checkDataPosionValid`; if requested bytes are not
   complete the load becomes an L1/DC miss, if STQ reports `wait_store` the row
   waits for store data, and only after load-data, SCB, and STQ paths have
   returned can `returnData` wake the consumer.

`LoadForwardPipeline` preserves the hardware timing form of those rules:

1. E2 runs `LoadStoreForwarding` over the current query, STQ candidate view,
   `(BID, LSID)` allocation snapshot, and baseline line data.
2. E3 registers byte masks, merged data, source-return flags, and return-slot
   readiness.
3. E4 computes final valid bytes as baseline-valid bytes plus ready forwarded
   bytes.
4. E4 republishes delayed load-data and SCB source-return bits separately, then
   asserts `e4WakeupValid` only when no wait-store bytes exist, all requested
   bytes are valid, load-data and SCB sources have returned, and the return
   slot is available.
5. E4 classifies the local block reason in priority order:
   `StoreDataNotReady`, `DataNotComplete`, `AwaitingSources`,
   `ReturnPortBlocked`, then `NoMiss`.

## Timing

The visible latency is two registered steps:

- cycle N: `e2Valid` and query/candidates enter the combinational selector,
- cycle N+1: E3 exposes registered masks and merged line data,
- cycle N+2: E4 exposes final completion, wakeup, and miss/replay
  classification.

Flush clears resident work before it can advance to E4.

## Flush/Recovery

`flush` clears the E3 and E4 valid bits. The module assumes upstream STQ/LIQ
recovery owners have already removed wrong-path candidate rows from
`e2Stores`; it does not prune rows internally.

## Trace/Observability

E3 exposes the raw forwarding masks, while E4 exposes final valid masks,
wakeup eligibility, wait-store diagnostics, and miss classification. Full
QEMU/DUT memory comparison still requires a future top-level memory trace
payload carrying live load result events.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover E2/E3/E4 latency, ready forwarded-byte wakeup,
store-data replay classification, incomplete baseline data, source-return and
return-port gating, flush clearing, and Chisel elaboration with the child
`LoadStoreForwarding` instance.
