# BlockMarkerRetireSourceSerializer

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerRetireSourceSerializer.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/bctrl/BlockMarkerRetireSourceSerializerSpec.scala`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/common/BlockMarkerBundles.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerLifecycle.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`

## Purpose

`BlockMarkerRetireSourceSerializer` is the first BCTRL-side consumer boundary
for the R169 ROB marker retire-source vector. `ROBEntryBank` can free up to
`commitWidth` retired rows in one deallocation window, and more than one of
those rows may carry marker metadata. The serializer accepts the full window
only when it has room for every possible slot, compacts valid marker sources in
slot order, queues them, and exposes one marker source at a time.

This module does not yet mutate `BlockMarkerLifecycle`. It exists so the later
full marker-row retirement owner can consume retired marker rows without
collapsing a width-wide deallocation event into one lossy pulse.

R171 integrates this serializer into `DecodeRenameROBPath`: ROB deallocation
is gated by `sourceWindowReady`, queued state clears on backend/block lifecycle
cleanup, and the serialized output is exposed as policy-free diagnostics while
the lifecycle consumer remains deferred.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| Input | `sources` | `Vec(sourceWidth, BlockMarkerRetireSource)` | Marker sources from one ROB deallocation window. Invalid lanes are ignored. |
| Input | `clear` | `Bool` | Drops queued marker sources and blocks same-cycle enqueue/dequeue. |
| Input | `outReady` | `Bool` | Downstream lifecycle adapter consumed the current serialized marker source. |
| Output | `sourceWindowReady` | `Bool` | True when the queue can accept a full `sourceWidth` window. |
| Output | `sourceValidMask` | `UInt(sourceWidth.W)` | Raw valid lanes observed in the input window. |
| Output | `sourceEnqueueCount` | `UInt` | Number of marker sources accepted from the current window. Zero when not ready. |
| Output | `sourceQueueCount` | `UInt` | Current queued marker-source count. |
| Output | `sourceQueueFull/sourceQueueEmpty` | `Bool` | Queue occupancy diagnostics. |
| Output | `sourceDequeued` | `Bool` | Current serialized source fired to downstream. |
| Output | `out` | `BlockMarkerRetireSource` | Queue head source with `valid` asserted while the queue is non-empty. |

## Logic Design

- `sourceWindowReady` is conservative: it requires room for the full
  `sourceWidth`, not only the number of valid lanes in the current window.
  `DecodeRenameROBPath` depends on this signal when gating ROB deallocation, so
  it does not need to predict how many marker rows will be present before the
  row image is observed.
- Valid source lanes enqueue in original slot order. Invalid lanes are skipped,
  but they do not reorder later marker rows.
- `out.valid` reflects queue non-empty state, not the stored lane's original
  valid bit after a clear. `clear` returns an all-zero source and resets queue
  pointers.
- The queue is intentionally policy-free. It does not evaluate boundary kind,
  redirect target, branch decisions, or per-STID active state.

## Model Alignment

`SPEROB::dealloc()` walks retired ROB rows in order up to the configured retire
width, observes each row's instruction image, skips `MinstPipeView()` for
`OP_BSTOP`, and stops after block-last handling. The Chisel serializer preserves
that ordered window shape for marker rows by retaining every valid
`BlockMarkerRetireSource` before any lifecycle policy decides how to allocate,
complete, redirect, or clear BCTRL active state.

`DCTop::Work()` allocates the ROB row before writing `dec_ren_q`, so marker
classification and boundary target are row-owned metadata by the time dealloc
publishes the source.

## Deferred Owners

- A lifecycle adapter that converts serialized marker sources into
  `BlockMarkerLifecycle` marker inputs.
- Per-STID active marker state and recovery-exact queued-source pruning.
- Full marker-row ROB admission in place of the current reduced marker-skip
  path.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`

Focused tests cover slot-order compaction, full-window admission backpressure,
clear/drop behavior, and Chisel elaboration of the queue and serialized source
ports. The backend composition test covers the integrated deallocation credit
and diagnostic IO surface.
