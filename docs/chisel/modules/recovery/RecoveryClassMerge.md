# RecoveryClassMerge

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoveryClassMerge.scala`
- Standalone probe:
  `chisel/src/main/scala/linxcore/recovery/RecoveryClassMergeProbe.scala`
- Integrated proof:
  `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`
- Tests: `chisel/src/test/scala/linxcore/recovery/RecoveryClassMergeSpec.scala`
- Generated-RTL testbench: `tools/chisel/recovery_class_merge_probe_tb.cpp`
- Model evidence: `model/LinxCoreModel/model/core/FlushControl.cpp`, especially
  `CheckOlder`, `mergeSignal`, `selectFlushSigal`, `selectReplaySigal`, and
  `selectPESigal`
- Contract ID: `LC-CHISEL-RECOVERY-CLASS-MERGE-001`

## Purpose

`RecoveryClassMerge` is the stateful class owner between retained source
arbitration and cleanup intent. It preserves the model's separate global
flush, global replay, and PE-scoped recovery lanes for every instantiated Linx
STID, while keeping cross-STID serialization fair and scope-safe.

This module does not define ARM architectural behavior. It reuses ISA-neutral
model cancellation and merge rules after rebinding identity to Linx STID, full
block BID, ring ROBID sidecars, PE, TID, GID, RID, and LSID.

## Parameters

| Parameter | Meaning |
|---|---|
| `stidCount` | Number of instantiated scalar STID recovery lanes. |
| `peCount` | Number of PE-scoped lanes under each STID. |
| `entries` | Ring ROB capacity used by `ROBID`. |
| `bidWidth` | Full implementation block-generation width. |
| `peIdWidth`, `stidWidth`, `tidWidth` | Linx execution-scope identity widths. |

`stidCount` must fit `stidWidth`, and `peCount` must fit `peIdWidth`. A valid
request naming an uninstantiated STID or PE is not admitted into class state.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `in` | Selected full-BID recovery request from `RecoverySourceArbiter`. |
| output | `inReady/inAccepted` | Admission handshake for the selected report. |
| output | `inBlockedByStid/inBlockedByPe` | Invalid instantiated-lane diagnostics. |
| output | `inDroppedByOlder` | Incoming report lost to an older/equal resident class entry. |
| output | `inDroppedByComplete` | Global replay rejected because the oldest block is already complete. |
| output | `inMerged` | Incoming/resident pair was transformed by model `mergeSignal` rules. |
| input | `oldestBid[stidCount]` | Wrap-qualified oldest block identity per STID. |
| input | `oldestBlockComplete[stidCount]` | Per-STID completed-oldest replay drop input. |
| output | `out` | Irrevocably staged selected class request. |
| input | `outReady` | Downstream cleanup can consume the staged request. |
| output | `outAccepted` | Staged request consumed this cycle. |
| output | `selectedClass/selectedStid/selectedPe` | Current staged selection diagnostics. |
| output | `globalFlushPendingMask` | One bit per STID with resident global-flush state. |
| output | `globalReplayPendingMask` | One bit per STID with resident global-replay state. |
| output | `pePendingMask` | Flattened `[STID][PE]` resident PE-scoped mask. |
| output | `pending` | Any resident class or output-slot state remains. |

## State

- `globalFlush[stidCount]`: one retained global flush per STID.
- `globalReplay[stidCount]`: one retained global replay per STID.
- `peScoped[stidCount][peCount]`: one retained PE-scoped request per STID/PE.
- `nextStid`: cross-STID round-robin pointer.
- `outPending/outReq/outClass/outStid/outPe`: one irrevocable downstream slot.

The output slot isolates downstream backpressure from class-state mutation.
While `out.valid` is blocked, later input reports may still cancel, merge, or
fill queued class lanes without changing the staged output payload.

## Logic Design

Incoming requests are first annotated through the same `FlushControl` helpers
used by `FullBidRecoveryBridge`.

For each accepted request:

- PE- or thread-scoped requests target the `(STID, PE)` lane.
- Non-PE flush types target the STID global-flush lane.
- Non-PE replay types target the STID global-replay lane.

Within one STID, resident lanes use `FlushControl.checkOlder` with that STID's
`oldestBid`. Older resident state may drop the incoming report. An incoming
older global flush may cancel younger PE-scoped and global-replay state. A
global replay is dropped when `oldestBlockComplete(stid)` is set, and an older
global replay may cancel a resident global flush at dispatch.

`mergeSignal` preserves the model transformation for same-PE inner/nuke cases:
when a resident and incoming request must become a single inner flush, the
merged request keeps the older exact identity and is reclassified into the
appropriate global or PE lane. This is a transformation, not a second cleanup
event.

Different STIDs are never age-compared. Each STID produces a local class
winner, and a round-robin pointer serializes populated STIDs after dispatch.
This preserves Linx per-STID BID/ring ROBID semantics and avoids inventing a
global BID order.

## Timing

Admission is combinationally ready only for in-range STID and PE IDs. Class
state updates on the accepting clock edge. Selected class state moves into the
one-entry output slot only when the slot is empty or the downstream consumer
accepts the current slot.

## Integration Status

R641 adds `RecoveryClassMerge` as a parameterized standalone owner and places
it between `RecoverySourceArbiter` and `RecoveryCleanupControl` through
`RecoveryFabric`. `RecoveryCleanupROBProbe` now uses the fabric wrapper, so
the real-ROB probe sees retained source arbitration, class cancellation/merge,
registered cleanup intent, block authority, and scoped ROB pruning in one path.

This is not complete production recovery composition. BCC, IEX, and PE
producer modules and canonical backend-top wiring remain open.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryClassMergeSpec`
- `bash tools/chisel/run_chisel_recovery_class_merge_probe.sh`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`

The standalone generated probe covers output-slot stability under backpressure,
same-STID PE replay cancellation by nuke, older same-BID rejection, independent
flush/replay lane retention, replay-over-flush cancellation at dispatch,
completed-block replay rejection, invalid STID/PE blocking, inner merge
transformation, independent PE lanes, and full drain.
