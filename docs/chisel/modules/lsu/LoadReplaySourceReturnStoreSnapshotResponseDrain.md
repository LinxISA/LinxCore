# LoadReplaySourceReturnStoreSnapshotResponseDrain

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseDrain.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotResponseDrainSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplaySourceReturnStoreSnapshotPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
    - `MtcLDQInfo::Work`
    - `lookup_su_lu_q->front()`
    - `lookup_su_lu_q->pop_front()`
    - `MtcLDQInfo::handleSTQReceive`
    - `entry.fsm != MTC_LDQ_REPICK`
    - `entry.stqRnt`
    - `bus.wait_store`
    - `bus.data_vld`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-STQ-SNAPSHOT-RESPONSE-DRAIN-001`

## Purpose

`LoadReplaySourceReturnStoreSnapshotResponseDrain` owns the dequeue decision
for the raw local STQ response FIFO.

The model drains `lookup_su_lu_q` in FIFO order before calling
`handleSTQReceive`. `handleSTQReceive` then either accepts the response for a
still-`MTC_LDQ_REPICK` row or returns immediately when the indexed row is no
longer repick. That means the queue head must be consumed for two distinct
reasons:

- the response is ordered, matched, and SCB-safe, so the accepted token can be
  cleared;
- the response targets a row that has become stale, so the raw queue can drop
  the head without clearing the currently accepted token.

R399 adds this owner inside `LoadReplaySourceReturnStoreSnapshotPath`. R400
feeds its stale input from a reduced single-cluster row-state proof based on
the current `repickMask`; the current top still ties raw response inputs false,
so stale drops are not yet observable in live top behavior.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-LIQ wrapper is active. |
| `flush` | Suppresses all dequeue decisions. |
| `headValid` | The raw response queue exposes a resident or bypass head. |
| `orderedResponse` | Downstream response-match owner accepted the head as ordered for the accepted token. |
| `headStale` | Row-state owner proves the head targets a row that is no longer repick. |

### Outputs

| Signal | Description |
|---|---|
| `active` | `enable && !flush`. |
| `dequeueReady` | Pop request for `LoadReplaySourceReturnStoreSnapshotResponseQueue`. |
| `orderedConsumed` | Ordered response consumed; clears the accepted token. |
| `staleDropped` | Stale response head dropped without clearing the accepted token. |
| `blockedByNoHead` | Active drain has no visible queue head. |
| `blockedByNoAction` | Head is visible but neither ordered nor proven stale. |
| `blockedByDisabled` | Head evidence appears while disabled. |
| `blockedByFlush` | Head evidence appears during flush. |
| `invalidStaleWithOrdered` | Row-state and response-match evidence both claim ownership of the same head. |

## State

The module is combinational. It does not store queue entries, selected-query
tokens, row fsm state, wait-store state, or returned data.

## Logic Design

The owner separates ordered consumption from stale discard:

```text
orderedConsumed = active && headValid && orderedResponse
staleDropped    = active && headValid && !orderedResponse && headStale
dequeueReady    = orderedConsumed || staleDropped
```

Ordered response acceptance has priority over stale discard. If future
row-state evidence marks a head stale in the same cycle that the response
matcher accepts it as ordered, `orderedConsumed` wins and
`invalidStaleWithOrdered` records the inconsistent evidence.

The owner intentionally does not infer stale from a simple nonmatch. A
nonmatching head may be a future response for another outstanding token once
multi-token query ownership exists. The safe drop predicate must come from the
row-state owner that can prove the indexed row is no longer repick, matching
the model's `entry.fsm != MTC_LDQ_REPICK` check.

## Timing

`dequeueReady` feeds the response queue's storage update. The queue's
`headValid/head*` signals remain independent of `dequeueReady`, so the
response-valid/dequeue-ready loop avoided in R398 stays broken.

## Flush/Recovery

Flush suppresses ordered consumption and stale drop. The upstream queue owns
resident-entry clearing on flush. Precise flush pruning by response identity
remains a later queue/row-identity owner.

## Deferred Owners

- Full multi-cluster row-state stale evidence into `headStale`.
- Multi-token response ownership for non-head or out-of-order responses.
- Wait-store row mutation and returned-data merge after ordered consumption.
- Precise queued-response flush pruning.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotResponseDrain
bash tools/chisel/run_chisel_tests.sh --only LoadReplaySourceReturnStoreSnapshotPath
```

Reference tests cover ordered consumption, explicit stale-head drop, no-action
holding, no-head diagnostics, disabled/flush suppression, ordered-over-stale
priority, and Chisel elaboration.
