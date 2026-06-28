# TULinkFlushSourceSelector

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkFlushSourceSelector.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkFlushSourceSelectorSpec.scala`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/common/TULinkBundles.scala`
  - `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkFlushSequencePublisher.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
  - `model/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/mtccore/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FlushBus.h`

## Purpose

`TULinkFlushSourceSelector` is the ROB/LSU source boundary for T/U cleanup
sidebands. It chooses the selected row snapshot that will feed
`TULinkRecoveryCleanupPath` and, through it,
`TULinkFlushSequencePublisher.source`.

The selector does not own ROB or LSU row storage. Current Chisel ROB/STQ rows
now expose the ROB-side T/U source candidate, while LSU/STQ rows do not yet
carry the matching T/U local sequence sidecars. This module defines the stable
interface both row owners must drive:

```text
ROB row candidate + LSU row candidate + cleanup intent
  -> selected TULinkFlushSequenceSource
```

## Interface

Inputs:

- `cleanup`: registered `RecoveryCleanupIntent`.
- `robSource`: candidate selected-row snapshot from the ROB/recovery owner.
- `lsuSource`: candidate selected-row snapshot from the LSU recovery owner.

Each source candidate uses `TULinkFlushSequenceSource`:

- `valid`
- `bid`, `rid`, `stid`
- `tSeq`, `uSeq`
- `dstValid`, `dstKind`

Outputs:

- `source`: selected source for `TULinkFlushSequencePublisher`.
- `cleanupActive`: `cleanup.valid && cleanup.backendFlushValid`.
- `sourceRequired`: non-base active cleanup requires a row source.
- `robMatched`, `lsuMatched`: candidate matches `(bid, rid, stid)`.
- `robMismatched`, `lsuMismatched`: valid candidate is present but names a
  different row.
- `multipleMatched`: both candidates name the selected row.
- `sourceConflict`: both candidates match but their payloads differ.
- `sourceMissing`: non-base cleanup has no matching source.
- `selectedFromRob`, `selectedFromLsu`: selected candidate origin.

## Logic Design

The selector is active only for backend T/U cleanup:

```text
cleanup.valid && cleanup.backendFlushValid
```

Base-on-BID cleanup does not require a source because `TULinkRename` prunes by
BID and ignores T/U sequence sidebands. In that case the selector emits an
invalid source and clears source-error diagnostics.

For non-base cleanup, a candidate matches when:

```text
candidate.valid
candidate.bid  == cleanup.flush.req.bid
candidate.rid  == cleanup.flush.req.rid
candidate.stid == cleanup.flush.req.stid
```

If only one candidate matches, the selector forwards that source. If both
match and the payloads are identical, the selector forwards the ROB candidate
and reports `multipleMatched`. If both match but the payloads differ, it
suppresses the selected source and reports `sourceConflict`; downstream
`TULinkRecoveryCleanupPath` then treats the invalid source as a recovery
barrier rather than applying a potentially wrong local sequence.

Valid candidates that do not match the cleanup request are reported as
`robMismatched` or `lsuMismatched`, but they are not forwarded.

## Model Alignment

`SPEROB::getRetireID` exposes the oldest row's `tSeq/uSeq`, and
`SPEROB::CheckDstDataOut` builds scalar inner-flush requests from the current
ROB row. LSU deadlock recovery in the scalar store unit and LDQ also starts
from `GetRetireID()`, reads the owning instruction from the PE ROB, and uses
the row's `tSeq/uSeq` plus destination ownership to build local-register
cleanup sidebands.

The Chisel selector preserves that split by allowing both ROB and LSU recovery
owners to present the selected row. It requires those owners to agree if they
both claim the same `(bid, rid, stid)` row.

## Timing

The module is combinational. It assumes candidate row snapshots are stable in
the same cycle as the registered cleanup intent.

## Flush/Recovery

`sourceConflict` and `sourceMissing` are recovery-contract diagnostics. A live
composition should monitor them alongside
`TULinkRecoveryCleanupPath.flushMissingSource` and
`flushSourceMismatch`.

## Deferred Owners

- Finish live row-image coverage for both source owners.
- Compose the reduced backend's exposed ROB source and the future LSU source
  into a live top-level recovery cleanup path.
- Add matching LSU/STQ `tSeq/uSeq` and destination-class sidecars.
- Add multi-PE and multi-thread source banking beyond the current STID0
  boundary.
- Extend the source path for predicate and vector local-register sidebands.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkFlushSourceSelector
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only TULinkFlushSequencePublisher
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The current tests cover ROB selection, LSU fallback after ROB mismatch, base
cleanup without a source, missing-source diagnostics, duplicate matching
sources with identical payloads, conflicting duplicate sources, inactive
cleanup behavior, IO widths, and standalone elaboration.
