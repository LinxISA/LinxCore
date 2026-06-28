# ROBFlushPrune

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBFlushPrune.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rob/ROBFlushPruneSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.h`
  - `model/LinxCoreModel/model/ModelCommon/ROBID.cpp`
  - `model/LinxCoreModel/model/pe/PECommon/PROBStatus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryStatus.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/recovery/FlushControl.scala`
- Contract IDs: `LC-IF-CHISEL-ROB-FLUSH-PRUNE-001`

## Purpose

`ROBFlushPrune` is the first integrated ROB flush-selection helper. It captures
the testable pruning part of `SPEROB::flush`: scan the ROB in deallocation
order, find the first row covered by a BID- or BID/RID-based flush request, and
mark that row plus all younger valid rows for removal.

The helper intentionally does not mutate `ROBEntryBank` state yet. Pointer
rebasing, rename cleanup, LSU/STQ side effects, branch restart ownership, and
precise traps remain future integrated ROB/CMT work.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `flush` | `FlushBus` | `flush.req.valid` | Annotated flush request from `FlushControl` |
| input | `deallocHead` | `UInt(log2(entries).W)` | none | Physical ROB slot where the model-style scan starts |
| input | `commitHead` | `UInt(log2(entries).W)` | none | Current commit pointer slot used for before-commit diagnostics |
| input | `rows` | `Vec[ROBFlushPruneEntry]` | row-local valid | Per-slot valid/status/BID/RID metadata |
| output | `directMatchMask` | `UInt(entries.W)` | diagnostic | Rows whose own BID or BID/RID is covered by the flush request |
| output | `pruneMask` | `UInt(entries.W)` | diagnostic | Valid rows cleared by the model-style found-and-younger walk |
| output | `pruneBeforeCommitMask` | `UInt(entries.W)` | diagnostic | Pruned rows encountered before the pre-cycle commit head |
| output | `outstandingPruneMask` | `UInt(entries.W)` | diagnostic | Pruned rows that decrement outstanding work |
| output | `firstPruneValid` | `Bool` | diagnostic | Any direct match was found |
| output | `firstPruneValue` | `UInt(log2(entries).W)` | diagnostic | First direct-match slot in deallocation-order scan |
| output | `commitRebaseNeeded` | `Bool` | diagnostic | At least one pruned row was encountered before commit head |
| output | `commitRebaseValue` | `UInt(log2(entries).W)` | diagnostic | First pruned-before-commit slot |
| output | `residentDecrement` | `UInt` | diagnostic | Number of valid resident rows selected by `pruneMask` |
| output | `outstandingDecrement` | `UInt` | diagnostic | Number of pruned rows with `ROBEntryStatus.osdActive` |

`ROBFlushPruneEntry` carries:

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Slot contains a live row |
| `status` | `ROBEntryStatus` | Row lifecycle state |
| `bid` | `ROBID` | Block identity used for BID-based flushes |
| `rid` | `ROBID` | Row identity used with BID for non-BID-based flushes |

## State

This module is purely combinational. It owns no registers and should stay a
helper under the ROB/CMT owner rather than becoming a standalone recovery state
machine.

## Logic Design

The scan order is physical ROB order starting at `deallocHead`, wrapping once
through all entries. For each row:

- If `flush.req.valid` is false, no row matches.
- If `flush.baseOnBid` is true, the direct-match predicate is
  `flush.bid <= row.bid` using `ROBID.lessEqual`.
- Otherwise, the direct-match predicate is
  `(flush.bid, flush.rid) <= (row.bid, row.rid)` using
  `FlushControl.lessEqualBidRid`.
- The first direct match starts the prune region.
- Once the prune region starts, every later valid row in scan order is included
  in `pruneMask`, even if its own direct-match predicate is false.
- Invalid rows do not set mask bits or affect counts, but they also do not end
  the prune region.

This mirrors the `found` behavior in `SPEROB::flush`, where the first matching
valid row rebases allocation and every later valid row in the ROB walk is
cleared by `HandleNextEntryVldAndFound`.

`outstandingDecrement` follows `CheckNextEntryStatus`: allocated, renamed,
issued, completed, and need-flush rows decrement outstanding work; retired,
fault, and free rows do not.

## Timing

`ROBFlushPrune` is combinational and observes pre-cycle row metadata. A later
registered ROB/CMT owner should consume its masks and perform state mutation on
the following edge or in a clearly owned recovery phase.

## Flush/Recovery

This packet covers selection only. It does not:

- clear ROB rows,
- update `allocPtr`, `commitPtr`, or `deallocPtr`,
- restore rename/checkpoint state,
- release local ready-table state,
- clean LSU/STQ/SCB state,
- raise or retire precise traps,
- publish frontend restart tokens.

Those behaviors remain in the integrated ROB/CMT and recovery-owner packets.

## Trace/Observability

The helper emits masks and counts for verification. These are not architectural
commit trace rows. Later trace work should surface only recovery events that
come from the registered ROB/CMT owner after masks have been applied.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`
- `bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus`
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl`
- `bash tools/chisel/build_chisel.sh`

Focused tests cover base-on-BID pruning, BID/RID pruning, wraparound
deallocation-order scans, invalid rows after pruning starts, resident versus
outstanding decrement accounting, invalid flush requests, and Chisel
elaboration of the mask/count interface.
