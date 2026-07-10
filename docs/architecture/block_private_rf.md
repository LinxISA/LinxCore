# Block-Private Register Contract

## Scope

This page defines how scalar global `P` state differs from block-private `T/U`
ClockHands state. The distinction is architectural: `P` survives block
boundaries through committed rename state, while `T/U` names relative values
whose lifetime is owned by one dynamic block.

## Owner split

- Scalar `P` uses `SMAP`, `CMAP`, a global physical-register free list, and a
  scalar remap log.
- `T` and `U` do not use that scalar-`P` rename package.
- Each local class has an independent `LocalRegMgr`/ClockHands mapping queue,
  allocation sequence (`tSeq` or `uSeq`), circular local physical pool,
  retired marks, and deallocation head.
- `CARG`/`BARG` is block state keyed by `(STID,BID)`; it is not independently
  renamed.

The word `MapQ` is therefore domain-qualified. A `T/U` local mapping queue is
not the scalar-`P` `MapQ` and does not restore from scalar `CMAP`.

## Rename and source lookup

- A `T/U` destination allocates from the owning local queue in decode-slot
  order and records BID/RID/STID ownership.
- A relative source resolves from the pre-allocation sequence using its encoded
  backward offset. A same-cycle destination must not change the source's
  lookup base.
- Local allocation stalls when either the local mapping queue or the local
  physical pool would overflow.
- Scalar and local rename state changes for one uop are atomic.

## Retire, commit, and release

Retirement and freeing are separate:

1. ROB deallocation publishes row-owned local sequence and destination
   sidecars.
2. Relation processing marks the matching local mapping row retired.
3. Relation releases and `CleanCMAP`-equivalent work drain in order.
4. A BID/STID-qualified local block-commit event frees only consecutive retired
   rows at the local deallocation head whose BID matches.

The release trigger is block completion, not the spelling of one boundary
opcode. A block may commit after explicit `BSTOP`, implicit termination by the
next `BSTART`, or template completion. An implementation must not release local
state on raw `BSTART` decode or retire unless the prior block's ordered local
block-commit event has been established.

## Recovery

- Non-base cleanup uses both local sequence and native RID ordering; local
  sequence must not be reconstructed from ROB RID.
- If the flushed row owns a local destination, recovery supplies the previous
  local sequence for that class.
- ROB and LSU recovery sources carry exact row-owned
  `(bid, rid, stid, tSeq, uSeq, destination-kind)` sidecars.
- A missing or conflicting non-base source is a recovery barrier. Local rename,
  retire, and commit must not continue using unrelated current-head state.

## Implementation status

LinxCoreModel provides the executable `LocalRegMgr` behavior. Chisel has
focused `TULink*` owners for rename, relation cleanup, bank selection, and
local block commit, but full multi-PE/multi-STID integration remains a
promotion item. Older pyCircuit bring-up paths that approximate local state
through scalar maps are reduced implementation behavior, not this contract.
