# BlockMarkerLifecycle

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerLifecycle.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/bctrl/BlockMarkerLifecycleSpec.scala`
- Integration:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerRetireSourceSerializer.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockScalarDoneSequencer.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`

## Purpose

`BlockMarkerLifecycle` owns the reduced active block context used by the live
fetch RF/ALU bring-up path. It tracks the current full BID, marker target, and
conditional/direct marker state for consumed `BSTART`/`BSTOP` rows, scalar
redirect cleanup, scalar-created target-body blocks, and ROB block-last
deallocation.

This is still the reduced marker-consume path. Marker rows are not yet allocated
as ordinary ROB rows when `skipBlockMarkers=true`. R168 moves the active-context
state and scalar-done source selection out of `DecodeRenameROBPath` so later
full marker-row retirement and per-STID active block state can feed one BCTRL
owner instead of growing backend-local registers.
R170 adds `BlockMarkerRetireSourceSerializer` as the policy-free queue boundary
for future full marker-row retirement. That serializer preserves every marker
source from a ROB deallocation window before this lifecycle owner consumes a
single marker event.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| Input | `flushValid` | `Bool` | Clears active block context on backend/block cleanup. |
| Input | `markerBoundary` | `Bool` | Current consumed marker is a `BSTART` boundary. |
| Input | `markerStop` | `Bool` | Current consumed marker is `BSTOP`. |
| Input | `markerPc` | `UInt(pcWidth.W)` | PC of the current consumed marker, used for sequential redirect comparison. |
| Input | `markerTarget` | `UInt(pcWidth.W)` | Target carried by the current boundary marker. |
| Input | `markerInsnLen` | `UInt(4.W)` | Current marker length in bytes. |
| Input | `markerBoundaryKind` | `BoundaryKind` | Boundary kind used to distinguish conditional and unconditional-direct active state. |
| Input | `markerAllocReady` | `Bool` | BROB-only marker allocation readiness from `DispatchROBAllocator`. |
| Input | `markerAllocBid` | `UInt(bidWidth.W)` | Full BID that would be allocated for the current marker boundary. |
| Input | `branchTakenValid/branchTaken` | `Bool` | Reduced execute-owned conditional decision. |
| Input | `scalarWorkPending` | `Bool` | True while scalar ROB work can still produce a branch decision. |
| Input | `markerLifecycleConflict` | `Bool` | Blocks marker lifecycle when another scalar-done source owns the cycle. |
| Input | `retirePending` | `Bool` | Prevents repeated same-slot pre-retire while the previous retire is pending. |
| Input | `scalarRedirectValid` | `Bool` | Reduced scalar execute redirect that closes the active block context. |
| Input | `scalarBlockStartFire/scalarBlockStartBid` | `Bool`/`UInt` | Scalar row allocated a new block while no marker block was active. |
| Input | `robBlockLastValid/robBlockLastBid` | `Bool`/`UInt` | ROB deallocation reached a block-last row. |
| Output | `activeValid/activeBid/activeTarget` | mixed | Current active block context. |
| Output | `activeCond/activeUnconditionalRedirect` | `Bool` | Internal active marker decision class, exposed for diagnostics/future owners. |
| Output | `blockAllocOnlyValid` | `Bool` | Request a BROB-only marker allocation. |
| Output | `markerReady` | `Bool` | Marker-only packet readiness used for `decodeReady`. |
| Output | `markerAllocFire` | `Bool` | Marker boundary allocation fires and becomes the new active BID. |
| Output | `markerPreRetireFire` | `Bool` | Active BID is completed before a same-slot marker allocation can proceed. |
| Output | `scalarDoneValid/scalarDoneBid` | `Bool`/`UInt` | Selected scalar-done source for `BlockScalarDoneSequencer`. |
| Output | `stopRedirectValid/stopRedirectPc` | `Bool`/`UInt` | Consumed stop/direct marker redirects frontend to the active target. |

## Logic Design

- A fallthrough marker boundary requests `blockAllocOnlyValid` unless a lifecycle
  conflict blocks the scalar-done port for the cycle.
- Conditional active blocks wait for a valid branch decision only while the
  active target is nonzero and scalar ROB work can still produce the decision.
- Direct/call active blocks redirect at the next marker boundary instead of
  allocating another BROB entry.
- If marker allocation would wrap onto the active BID slot while allocation is
  not ready, `markerPreRetireFire` emits scalar done for the active BID and
  keeps active state live until the retire gap clears.
- Scalar redirects complete and clear active context before the redirected
  target's first scalar row can seed a fresh scalar-created active block.
- A scalar row allocation with no active marker block seeds active context using
  the scalar row's full BID and a zero target.
- ROB block-last deallocation emits scalar done for the deallocated block BID
  and clears active state only when the full BID matches the active context.
- Future marker-row retirement should consume one serialized
  `BlockMarkerRetireSource` at a time from `BlockMarkerRetireSourceSerializer`
  rather than scanning the ROB deallocation vector directly.

## Model Alignment

`DCTop::SelectbyInstQ` removes a block from the decode-side block split queue
when the selected instruction is last-in-block or a parallel `BSTOP` closes the
block. `SPEROB::dealloc()` calls `CommitLast()` when a retired ROB row is
block-last and not flushed. The reduced Chisel path still uses marker consume
timing for `BSTART`/`BSTOP`, but the active-context owner now mirrors the model
handoff shape: select the active full BID, produce scalar completion, then let
`BlockScalarDoneSequencer` issue the delayed retire/free pulse.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r168-block-marker-lifecycle-smoke --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
