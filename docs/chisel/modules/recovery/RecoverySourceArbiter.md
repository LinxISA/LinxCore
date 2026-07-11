# RecoverySourceArbiter

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoverySourceArbiter.scala`
- Integrated proof: `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`
- Tests: `chisel/src/test/scala/linxcore/recovery/RecoveryCleanupROBProbeSpec.scala`
- Generated-RTL testbench: `tools/chisel/recovery_cleanup_rob_probe_tb.cpp`
- Model evidence: `model/LinxCoreModel/model/core/FlushControl.cpp`, especially
  `FlushControl::CheckOlder`, `report`, `select`, and `Xfer`
- Contract ID: `LC-CHISEL-RECOVERY-ARBITER-001`

## Purpose

`RecoverySourceArbiter` is the retained post-promotion report boundary between
recovery producers and `RecoveryCleanupControl`. Every producer owns an
independently backpressured slot. A report cannot disappear because another
source is selected or the cleanup consumer stalls.

The model compares age only when two reports have the same STID. Linx extends
that single-thread model rule without inventing a cross-thread BID order: the
arbiter selects one model-oldest report per populated STID and round-robins the
independent STID winners onto the single cleanup path.

## Parameters

| Parameter | Meaning |
|---|---|
| `sourceCount` | Number of independently retained report producers. |
| `stidCount` | Number of instantiated scalar STID lanes. |
| `entries` | Ring ROB capacity used by `ROBID`. |
| `bidWidth` | Full implementation block-generation width. |
| `peIdWidth`, `stidWidth`, `tidWidth` | Linx execution-scope identity widths. |

`stidCount` must fit `stidWidth`. A valid report naming an uninstantiated STID
is backpressured and raises `sourceBlockedByStid`; it is never silently mapped
to another lane.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `sources[sourceCount]` | Full-BID reports held until `sourceAccepted`. |
| output | `sourceReady/sourceAccepted` | Per-source retained-slot handshake. |
| output | `sourceBlockedByStid` | Per-source invalid-lane diagnostic. |
| input | `oldestBid[stidCount]` | Wrap-qualified oldest block identity per STID. |
| output | `out` | Selected retained full-BID report. |
| input | `outReady` | Cleanup boundary can accept the selected report. |
| output | `outAccepted` | Selected report transferred this cycle. |
| output | `pendingMask/lanePendingMask` | Source and STID residency diagnostics. |
| output | `selectedSourceValid/selectedSource/selectedStid` | Current selection diagnostics. |

## Selection Contract

Within one STID, sources are scanned in source-index order and use
`FlushControl.checkOlder(current, next, oldestBid(stid))`. The current winner
is retained when it is older; otherwise the next report replaces it. This
preserves model type precedence and exact BID/RID conflict behavior.

Requests from different STIDs never enter `checkOlder`. A round-robin STID
pointer chooses among lane winners and advances only after `outAccepted`.
Therefore a continuously populated STID cannot starve another populated STID,
and numeric BIDs from unrelated STIDs are never compared.

Each source slot supports consume-and-replace: when its selected report
transfers, a new report from the same source may be accepted on that edge.
Losing sources remain resident.

## Linx Adaptation

This block preserves ISA-neutral mechanisms from the model: retained producer
queues, oldest-report selection, typed tie precedence, cleanup backpressure,
and deterministic serialization. It uses Linx STID, PE, TID, BID, GID, RID,
and LSID identities. It defines no ARM architectural behavior.

## Integration Status

R639 places this owner in `RecoveryCleanupROBProbe` ahead of the real exact
full-BID lookup/ROB cleanup path. Ring-originated MDB reports are promoted by
production `ScalarLSURecoverySource` before entering the arbiter. The generated proof
covers exact lookup, retained admission, same-STID oldest selection, losing
source retention, consume-and-replace, invalid-STID rejection, cross-STID
round-robin selection, and scoped real ROB pruning.

R640 removes the transitional two-input local cleanup composition from
production `ScalarLSU` and exports its promoted MDB source. Instantiating this
arbiter with the real BCC/IEX/PE source set in the canonical backend top remains
the next ownership packet. The harness proves the central contract but does not
claim complete top-level recovery composition.

The model also retains separate global-flush, global-replay, and PE-scoped
signal lanes and performs cancellation/merge between them. This packet does
not collapse those class owners into this arbiter. Canonical composition must
either preserve those lanes or define and prove an equivalent serialized merge
before calling the full `FlushControl` path converged.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupROBProbeSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`
- `bash tools/chisel/run_chisel_tests.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`

R639 passes 251 suites and 1,482 tests. The generated probe passes exact lookup,
same-STID oldest selection, consume-and-replace, invalid-STID rejection, and
continuous two-lane round-robin retention. The canonical top cross-check passes
3 rows with zero mismatches. Reduced CoreMark passes 426 rows with zero
mismatches and zero CBSTOP at
`generated/r639-final-recovery-source-arbiter-coremark/report/crosscheck_manifest.json`
as no-regression evidence, not natural recovery activation.
