# RecoveryFabric

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoveryFabric.scala`
- Child source owner:
  `chisel/src/main/scala/linxcore/recovery/RecoverySourceArbiter.scala`
- Child class owner:
  `chisel/src/main/scala/linxcore/recovery/RecoveryClassMerge.scala`
- Child cleanup owner:
  `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
- Integrated proof:
  `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`
- Tests: `chisel/src/test/scala/linxcore/recovery/RecoveryClassMergeSpec.scala`
- Generated-RTL testbench:
  `tools/chisel/recovery_cleanup_rob_probe_tb.cpp`
- Contract ID: `LC-CHISEL-RECOVERY-FABRIC-001`

## Purpose

`RecoveryFabric` is the retained recovery composition wrapper:

```text
RecoverySourceArbiter -> RecoveryClassMerge -> RecoveryCleanupControl
```

It keeps producer arbitration, model class resolution, and cleanup fanout in
separate child owners while defining the production handshake between them. The
wrapper exposes source, class, and cleanup diagnostics so generated RTL can
prove that reports are retained across every boundary.

The fabric preserves Linx full block BID, ring ROBID, STID, PE, TID, GID, RID,
and LSID semantics. It does not import ARM exception levels, condition-code
state, exclusive monitors, barrier encodings, or other ARM architectural
behavior.

## Parameters

| Parameter | Meaning |
|---|---|
| `sourceCount` | Number of independently retained full-BID recovery producers. |
| `stidCount` | Number of instantiated scalar STID recovery lanes. |
| `peCount` | Number of PE-scoped lanes under each STID. |
| `entries` | Ring ROB capacity used by `ROBID`. |
| `bidWidth` | Full implementation block-generation width. |
| `peIdWidth`, `stidWidth`, `tidWidth` | Linx execution-scope identity widths. |

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `sources[sourceCount]` | Full-BID producer reports. |
| output | `sourceReady/sourceAccepted` | Per-source retained-slot handshake. |
| output | `sourceBlockedByStid/sourceBlockedByPe` | Source-level invalid-scope diagnostics asserted before source-slot admission. |
| input | `oldestBid[stidCount]` | Per-STID oldest ring block identity. |
| input | `oldestBlockComplete[stidCount]` | Per-STID completed-oldest replay drop input. |
| input | `intentReady` | Cleanup consumer can accept the registered intent. |
| output | `intent` | Registered `RecoveryCleanupIntent`. |
| output | `intentAccepted/intentConsumed` | Cleanup-boundary diagnostics. |
| output | `pending` | Source, class, or cleanup state is resident. |
| output | `sourcePendingMask/sourceSelected*` | Source-arbiter diagnostics. |
| output | `classSelected*` | Class-merge selected diagnostics. |
| output | `classGlobalFlushPendingMask` | Per-STID global-flush class residency. |
| output | `classGlobalReplayPendingMask` | Per-STID global-replay class residency. |
| output | `classPePendingMask` | Flattened `[STID][PE]` class residency. |
| output | `classDroppedByOlder/classDroppedByComplete/classMerged` | Class action diagnostics. |
| output | `classBlockedByStid/classBlockedByPe` | Class admission diagnostics. |

## Composition Contract

`RecoverySourceArbiter` admits each producer into an independent slot, selects
the model-oldest report within one STID, and round-robins STID winners without
comparing cross-STID BIDs.

`RecoveryFabric` validates PE range before presenting a report to the source
arbiter. An invalid PE therefore remains unaccepted at its producer boundary
and cannot wedge an already-accepted source slot waiting for class admission.

`RecoveryClassMerge` owns the model's global flush, global replay, and
PE-scoped class state. It performs same-STID cancellation, completed-block
global replay drop, `mergeSignal` transformation, PE-lane retention, and
cross-STID fair serialization into an irrevocable output slot.

`RecoveryCleanupControl` registers the selected request and exposes explicit
cleanup intent bits for ROB/BCTRL/BROB, rename, frontend, backend, LSU/STQ,
tile, vector, MTC, and PE consumers. The fabric drives the cleanup raw-ring
compatibility input inactive; production reports must enter as full-BID
requests.

## Integration Status

R641 introduces this wrapper and updates the real-ROB recovery probe to use it.
The probe integrates retained source arbitration, class merge, cleanup intent,
and real `ROBEntryBank` pruning.

This wrapper is the canonical recovery fabric shape, but it is not yet
canonical production top wiring. BCC, IEX, and PE producer modules remain open,
and the backend top still needs to instantiate the complete producer set.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryClassMergeSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`

Focused elaboration proves the wrapper instantiates the source arbiter, class
merge, and cleanup controller. The integrated generated probe proves the fabric
path against resident ROB rows.
