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
replay-LIQ reduced top as diagnostics in R281. It does not mutate LIQ state,
drive the top-level launch port, return data, update ready tables, move
LHQ/ResolveQ state, or replace the reduced-store completion-drain path.

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
| output | `launchCandidateMask` | `dataHitMask` after `enable` gating. |
| output | `launchMask` | One-hot selected oldest candidate. |
| output | `launchValid`, `launchIndex` | Launch request and selected LIQ slot. |
| output | `candidateCount` | Number of enabled launch candidates. |
| output | `selected*` | Selected load identity, address, size, PC, requested byte mask, `specWakeup`, and `stackValid` diagnostics. |

## Logic Design

The model's scalar L1 pick path selects rows in `LDQ_WAIT` only when:

1. the row is not waiting on a store,
2. the row is not a tile load/store,
3. `LUEntryInfo::dataHit()` is true, and
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

This keeps freshly allocated R279 replay rows resident but not launchable until
a replay wakeup, refill wakeup, or later LDQ data owner provides the requested
bytes. It also keeps ordinary initial-load launch policy outside this selector;
`LoadInflightQueue.launchReady` remains broader because initial loads may use
external base data rather than row-owned data.

Oldest selection uses the same scalar order helper used by store/load
forwarding sidecars: `STQCommitQueue.lessEqualBidLs(row.bid, row.loadLsId, ...)`.
If two rows carry equal order keys, lower LIQ index wins as a deterministic
tie-breaker.

## Deferred Owners

- Driving the opt-in replay-LIQ reduced top's `LoadInflightQueue.launchValid`
  from `LoadInflightLaunchSelect` after launch/new-load arbitration is owned.
- Supplying `LoadInflightQueue.launchValid/launchIndex` and row-owned E2 base
  data from the selected replay row.
- Clear-resolved/LHQ/ResolveQ movement after selected rows complete.
- Ready-table and dependent-consumer wakeup after replay completion.
- Vector/tile LDQ pick rules.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadInflightLaunchSelect
```

Reference tests cover oldest scalar order, suppression of freshly allocated rows
without requested bytes, store-bypass and refill-hit candidate handling,
wait-store/tile blocking diagnostics, enable gating, and Chisel elaboration.
