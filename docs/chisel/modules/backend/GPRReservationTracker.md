# GPRReservationTracker

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/backend/GPRReservationTracker.scala`
- Integration: `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- Generated RTL probe: `tools/chisel/run_chisel_gpr_rename_stid_probe.sh`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.h`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`

## Purpose

`GPRReservationTracker` prevents decode from reserving ROB/BROB rows that the
queued scalar rename path cannot later accept. It reflects the model's scalar
GPR ownership split:

- physical registers form one shared pool across STIDs;
- each STID owns an independent finite MapQ.

The module therefore keeps one global pending physical-allocation count and
one pending MapQ count per STID. It does not own architectural maps or allocate
physical tags; `GPRRenameCheckpoint` remains that state owner.

## Parameters

| Parameter | Meaning |
|---|---|
| `queueDepth` | Maximum number of decoded rows whose rename resources are reserved. |
| `physRegs` | Shared scalar physical-register capacity and free-count width. |
| `mapQDepth` | Capacity of each STID-local scalar MapQ. |
| `stidWidth` | Width of Linx STID sidebands. |
| `stidCount` | Number of implemented scalar rename lanes. |

All capacities must be nonzero, and `stidCount` must fit `stidWidth`.

## Interface And State

`pushValid/pushStid` records a decoded GPR destination entering the decode-to-
rename queue. `popValid/popStid` removes the reservation when that queued row
enters rename. `flush` clears all pending reservations with the queue.

`selectedStid`, `selectedNeedsGpr`, `freePhysCount`, and
`selectedMapQFreeCount` evaluate a new decode candidate. The selected MapQ free
count must come from the same STID as `selectedStid`; it is intentionally
independent of the older queued rename row's STID.

The admission equations are:

```text
physNeed = globalPending + selectedNeedsGpr
mapQNeed = pending[selectedStid] + selectedNeedsGpr
ready = selectedStidInRange
     && physNeed <= freePhysCount
     && mapQNeed <= selectedMapQFreeCount
```

Push and pop may name different STIDs in one cycle. Global occupancy remains
unchanged, while the source lane decrements and destination lane increments.
Invalid selectors and count underflow raise `stateError`; the integrated path
asserts that this never occurs.

## Linx Architectural Boundary

Finite-resource reservation is ISA-neutral OOO machinery. Its lane key is the
Linx STID carried by decoded and queued rows. The module does not define ARM
register banks, exception levels, condition codes, barriers, or memory-model
behavior. It also does not compare BID age across STIDs.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_gpr_rename_stid_probe.sh
```

The generated Verilated probe proves that a full STID0 pending MapQ budget
blocks another STID0 reservation without blocking STID1, and that exhaustion
of shared physical-register credit blocks both lanes. The integrated
elaboration test proves `DecodeRenameROBPath` contains the tracker and supports
two scalar STID lanes.
