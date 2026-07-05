# LoadInflightLaunchSelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightLaunchSelect.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadInflightLaunchSelectSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`

## Purpose

`LoadInflightLaunchSelect` is a combinational LIQ relaunch selector. It scans
`LoadInflightQueue` row images, exposes model-derived eligibility masks, and
selects the oldest launchable scalar `Wait` row by `(BID, loadLsId)` order.

The selector is wired into `ReducedLoadReplayLiqAllocPath` and the opt-in
replay-LIQ reduced top as diagnostics in R281. R311 also republishes the
selected row's replay destination sideband for the future LRET/writeback owner.
R375 republishes the selected row's RF-derived source-trace sideband for the
future replay W2 commit-row source provider.
It does not mutate LIQ state, drive the top-level launch port, return data,
update ready tables, move LHQ/ResolveQ state, or replace the reduced-store
completion-drain path.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `enable` | Gates launch request output. Diagnostic masks still report candidate state. |
| input | `rows` | Current `LoadInflightQueue` row images. |
| output | `waitMask` | Rows that are valid and in `LoadInflightStatus.Wait`. |
| output | `waitStoreBlockedMask` | `Wait` rows blocked by a remembered not-ready store. |
| output | `tileBlockedMask` | `Wait` rows suppressed because tile launch is outside this scalar selector. |
| output | `unblockedWaitMask` | Scalar `Wait` rows with no wait-store block. |
| output | `requestCompleteMask` | Unblocked rows whose row-owned `validMask` covers every requested load byte. |
| output | `dataHitMask` | Rows matching the model `dataHit` condition: `l1Hit`, `storeBypass`, or complete requested row-owned bytes. |
| output | `launchCandidateMask` | Unblocked scalar `Wait` rows after `enable` gating. |
| output | `launchMask` | One-hot selected oldest candidate. |
| output | `launchValid`, `launchIndex` | Launch request and selected LIQ slot. |
| output | `candidateCount` | Number of enabled launch candidates. |
| output | `selected*` | Selected load identity, address, size, PC, replay-return signedness, requested byte mask, destination, source trace, `specWakeup`, and `stackValid` diagnostics. |

## Logic Design

The model's scalar L1 pick path selects rows in `LDQ_WAIT` when:

1. the row is not waiting on a store,
2. the row is not a tile load/store,
3. the row has a path to obtain data, and
4. the oldest row by scalar `(BID, LSID)` order wins.

`LUEntryInfo::dataHit()` is `ldqHit || l1Hit || storeBypass`. Chisel does not
yet have a separate LDQ cluster-data hit bit at this boundary, so the selector
uses a conservative row-owned byte test for the LDQ-hit case:

```text
requestComplete = requestMask != 0 && ((row.validMask & requestMask) == requestMask)
dataHit = row.l1Hit || row.storeBypass || requestComplete
```

R305 also forwards the selected row's `specWakeup` and `stackValid` sidebands
so the replay-return consumer owner can decide whether a mem wakeup is
required after the always-required LRET write.
R307 forwards `returnSignExtend` from the selected LIQ row so the later
return-data owner can choose sign or zero extension without carrying the full
model opcode at the selector boundary.
R311 forwards the selected row's compact destination sideband so the future
return-payload and wakeup owner can use the same destination captured with the
load instead of reconstructing it at replay time.
R375 forwards `selectedSourceTraceValid/source0/source1` from the selected row.
These fields are pass-through provenance from the original RF-read path; the
selector does not inspect source data when deciding `dataHit` or launch order.

R483 separates selection from data ownership. A freshly allocated replay row
that is valid, scalar, in `Wait`, and not wait-store blocked is a launch
candidate even when `dataHitMask` is still zero. That lets the selected row
drive the replay base-data lookup that can make data available. `dataHitMask`
remains a diagnostic for rows that already own requested bytes through
`l1Hit`, store bypass, or complete row-owned byte state; it is not the
candidate predicate. This avoids the circular dependency where base lookup
required `launchValid`, while `launchValid` required pre-existing row data.

Oldest selection uses the same scalar order helper used by store/load
forwarding sidecars: `STQCommitQueue.lessEqualBidLs(row.bid, row.loadLsId, ...)`.
If two rows carry equal order keys, lower LIQ index wins as a deterministic
tie-breaker.

## Deferred Owners

- Source-return/SCB readiness for the opt-in replay-LIQ reduced top. R483
  proves the selected unblocked `Wait` row can issue and complete the base-data
  lookup, but the enabled early-STA fixture still blocks launch acceptance on
  `LoadReplayLaunchReadiness` source-return/SCB state.
- Live replay-row launch acceptance and row lifecycle clear after source-return
  readiness is owned.
- Clear-resolved/LHQ/ResolveQ movement after selected rows complete.
- Ready-table and dependent-consumer wakeup after replay completion.
- Vector/tile LDQ pick rules.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightLaunchSelect
```

Reference tests cover oldest scalar order, suppression of freshly allocated rows
without requested bytes only from `dataHitMask`, launch candidacy for unblocked
fresh rows, store-bypass and refill-hit data-hit diagnostics, wait-store/tile
blocking diagnostics, enable gating, selected destination elaboration, and
Chisel elaboration.
