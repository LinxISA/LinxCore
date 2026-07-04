# LoadReplaySourceReturnStoreSnapshotRequestPayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestPayloadSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::pickL1`
    - `MtcLDQInfo::loadRepick`
    - `MtcLDQInfo::handleL1Lookup`
    - `MtcLDQInfo::handleSTQReceive`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/store_unit.cpp`
    - `MtcStoreUnit::handleLoadReq`
  - `model/LinxCoreModel/model/mtccore/lsu/store_unit/stq.cpp`
    - `MtcSTQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotAcceptedToken.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-REQUEST-PAYLOAD-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotRequestPayload` names the selected load
request shape that will enter the future local STQ lookup request queue.

The model flow is:

```text
MtcLDQInfo::pickL1
  -> MtcLDQInfo::loadRepick
  -> MtcLDQInfo::handleL1Lookup
  -> lookup_lu_su_q
  -> MtcStoreUnit::handleLoadReq
  -> MtcSTQ::lookupForLoad
  -> lookup_su_lu_q
  -> MtcLDQInfo::handleSTQReceive
```

R402 adds only the request payload owner. It does not add the live request
queue, raw store-unit sink, raw response source, or LIQ row mutation. The
current top still ties live request enable false.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Composite source-return path is active. |
| `flush` | Suppresses request-payload publication. |
| `queryIssued` | R392/R401 query issue accepted a selected replay row. |
| `selectedValid` | Selected replay identity is valid. |
| `selectedRepick` | Selected row is still model-equivalent `LDQ_REPICK`. |
| `selectedClusterId` / `selectedEntryId` | Accepted local LDQ row identity for future `MemReqBus.cID/eID`. |
| `selectedLoadId` | Reduced LIQ slot identity. |
| `selectedBid` / `selectedGid` / `selectedRid` | ROB identity carried with the request. |
| `selectedLoadLsId` | Model load/store ordering ID used by STQ older-store filtering. |
| `selectedPc` | Load PC for diagnostics and wait-store identity. |
| `selectedAddr` / `selectedSize` | Load address window consumed by STQ overlap checks. |
| `selectedRequestByteMask` | 64-byte request mask already shaped by `LoadInflightLaunchSelect`. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `captureCandidate` | A query issue is visible in an active cycle. |
| `requestValid` | Output payload names a valid repick request. |
| `request` | Typed request payload for the later local STQ lookup queue. |
| `blockedByDisabled` | Query issue appeared while disabled. |
| `blockedByFlush` | Query issue appeared during flush. |
| `blockedByNoIssue` | A selected repick row is present but query issue has not fired. |
| `blockedByNoSelected` | Query issue fired without a selected identity. |
| `blockedByStaleRow` | Query issue fired for a row no longer proven repick. |

## State

The owner is combinational. It does not queue requests or store accepted
identity; the accepted-query token remains the outstanding-identity owner.

## Logic Design

```text
active           = enable && !flush
captureCandidate = active && queryIssued
requestValid     = captureCandidate && selectedValid && selectedRepick
```

When `requestValid` is true, the module copies the selected row identity,
ordering IDs, PC, address, size, and byte mask into `request`. Otherwise the
payload is zero and invalid. This mirrors the model handoff where
`handleL1Lookup` pushes the selected row's `MemReqBus` into `lookup_lu_su_q`,
but keeps the actual queue and store-unit data lookup as later owners.

## Timing

R402 is same-cycle with query issue. A later request queue may register this
payload and expose a dequeue-ready boundary to the store unit.

## Flush/Recovery

Flush suppresses the payload and reports `blockedByFlush` if query issue is
visible. Precise queued-request flush pruning remains deferred to the real
request queue owner.

## Deferred Owners

- Live local STQ lookup request queue.
- Raw store-unit request sink and STQ data lookup.
- Raw `lookup_su_lu_q` response source.
- Precise queued-request and queued-response flush pruning.
- Wait-store row mutation and returned-data merge.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotRequestPayload
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r402x bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover accepted selected-row payload publication, no-issue
blocking, invalid/stale selected rows, disabled/flush suppression, and Chisel
elaboration.
