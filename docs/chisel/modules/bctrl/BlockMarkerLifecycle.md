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
for marker-row retirement. That serializer preserves every marker
source from a ROB deallocation window before this lifecycle owner consumes a
single marker event.
R172 adds that retired-marker source lane to this lifecycle owner. Serialized
`BlockMarkerRetireSource` rows now consume lifecycle readiness directly, use
the row-owned `blockBid` captured before dispatch, and backpressure malformed
retired boundaries that do not carry a valid block BID. The decode-time
reduced marker-skip inputs remain separate until full marker-row admission
replaces the bring-up skip path.
R174 changes the active context from one global slot to an explicit
STID-indexed array. The default instantiation still has one STID lane for the
current reduced top, but marker consumption, retired marker consumption, scalar
redirect cleanup, scalar-created active blocks, and diagnostic active-state
queries now carry the STID that owns the operation.
R175 does not change this module, but it constrains the next full marker-row
design. `DecodeRenameROBPath` can now admit, rename-update, internally consume,
and complete marker rows without issuing them to the reduced scalar ALU.
However, this lifecycle owner still combines decode-time active-BID assignment
with retire-time scalar-done/redirect policy. The retired-marker lane cannot
simply replace decode-time marker consumption in the live top until the design
splits or otherwise proves how following scalar rows receive the new boundary
BID before the marker row retires.
R192 adds the marker-row redirect boundary drain rule found by the filtered
marker-row QEMU gate. A retired redirecting boundary can own a row-allocated
BROB entry even when the marker redirects instead of opening a scalar body.
The lifecycle now completes the previous active block first, records the
marker-owned boundary BID, and emits a later scalar-done event for that
marker-only block when the scalar-done port is free.

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
| Input | `markerStid` | `UInt(stidWidth.W)` | STID lane for the decode-time marker-only packet. Marker readiness is false when this STID is outside the instantiated `stidCount`. |
| Input | `markerAllocReady` | `Bool` | BROB-only marker allocation readiness from `DispatchROBAllocator`. |
| Input | `markerAllocBid` | `UInt(bidWidth.W)` | Full BID that would be allocated for the current marker boundary. |
| Input | `branchTakenValid/branchTaken` | `Bool` | Reduced execute-owned conditional decision. |
| Input | `scalarWorkPending` | `Bool` | True while scalar ROB work can still produce a branch decision. |
| Input | `markerLifecycleConflict` | `Bool` | Blocks marker lifecycle when another scalar-done source owns the cycle. |
| Input | `retirePending` | `Bool` | Prevents repeated same-slot pre-retire while the previous retire is pending. |
| Input | `scalarRedirectValid` | `Bool` | Reduced scalar execute redirect that closes the active block context. |
| Input | `scalarRedirectStid` | `UInt(stidWidth.W)` | STID lane to close for an execute-owned scalar redirect. |
| Input | `scalarBlockStartFire/scalarBlockStartStid/scalarBlockStartBid` | mixed | Scalar row allocated a new block while no marker block was active for that STID. |
| Input | `robBlockLastValid/robBlockLastBid` | `Bool`/`UInt` | ROB deallocation reached a block-last row. |
| Input | `activeQueryStid` | `UInt(stidWidth.W)` | STID lane selected for `active*` diagnostic outputs and scalar-row BID reuse. |
| Input | `retiredMarker` | `BlockMarkerRetireSource` | One serialized retired marker row from `BlockMarkerRetireSourceSerializer`, including row-owned BID, PC, length, boundary kind, and target. |
| Output | `activeValid/activeBid/activeTarget` | mixed | Current active block context for `activeQueryStid`. |
| Output | `activeCond/activeUnconditionalRedirect` | `Bool` | Internal active marker decision class for `activeQueryStid`, exposed for diagnostics/future owners. |
| Output | `blockAllocOnlyValid` | `Bool` | Request a BROB-only marker allocation. |
| Output | `markerReady` | `Bool` | Marker-only packet readiness used for `decodeReady`. |
| Output | `markerAllocFire` | `Bool` | Marker boundary allocation fires and becomes the new active BID. |
| Output | `markerPreRetireFire` | `Bool` | Active BID is completed before a same-slot marker allocation can proceed. |
| Output | `retiredMarkerReady` | `Bool` | Downstream readiness for the serialized retired marker source. False backpressures the serializer and, transitively, ROB deallocation when queued credit is exhausted. |
| Output | `retiredMarkerFire` | `Bool` | Retired marker source consumed by lifecycle policy. |
| Output | `retiredMarkerBoundaryFire` | `Bool` | Retired fallthrough boundary installed row-owned active state. |
| Output | `retiredMarkerStopFire` | `Bool` | Retired stop marker completed the active context. |
| Output | `scalarDoneValid/scalarDoneBid` | `Bool`/`UInt` | Selected scalar-done source for `BlockScalarDoneSequencer`. |
| Output | `stopRedirectValid/stopRedirectPc` | `Bool`/`UInt` | Consumed stop/direct marker redirects frontend to the active target. |

## Logic Design

- A fallthrough marker boundary requests `blockAllocOnlyValid` unless a lifecycle
  conflict blocks the scalar-done port for the cycle.
- Conditional active blocks wait for a valid branch decision only while the
  active target is nonzero and scalar ROB work can still produce the decision.
- Direct/call active blocks redirect at the next marker boundary instead of
  allocating another BROB entry.
- Active context is stored per instantiated STID lane. Decode-time marker
  operations use `markerStid`; retired marker operations use
  `retiredMarker.stid`; scalar redirect cleanup uses `scalarRedirectStid`; and
  scalar row BID reuse/diagnostics query `activeQueryStid`.
- If marker allocation would wrap onto the active BID slot while allocation is
  not ready, `markerPreRetireFire` emits scalar done for the active BID and
  keeps active state live until the retire gap clears.
- Scalar redirects complete and clear active context before the redirected
  target's first scalar row can seed a fresh scalar-created active block. Only
  the redirecting STID lane is cleared.
- A scalar row allocation with no active marker block seeds active context using
  the scalar row's full BID, STID, and a zero target.
- ROB block-last deallocation emits scalar done for the deallocated block BID
  and clears any active STID lane whose full BID matches the deallocated block.
- Serialized retired marker sources are consumed only when no decode-time
  marker, scalar redirect, backend flush, or ROB block-last owner is using the
  lifecycle for the cycle.
- A retired fallthrough boundary installs `retiredMarker.blockBid` as the active
  BID. It never requests a BROB allocation at retire time because the marker row
  was allocated before dispatch.
- A retired boundary that redirects the previous active direct/call or taken
  conditional block completes the old active BID and clears active state without
  installing a new active scalar block. If that retired boundary also carries a
  different row-owned marker BID, the marker-only BID is queued for scalar-done
  on a later cycle. A retired stop marker completes and clears the active
  context.
- Pending marker-owned completion blocks new marker/retired-marker lifecycle
  consumption until the queued BID has used the scalar-done port. Live scalar
  done sources keep priority; the queued marker-only BID fires only when no
  marker, retired marker, scalar redirect, or ROB block-last source owns the
  port.
- A retired fallthrough boundary with `blockBidValid=false` is deliberately not
  ready. That malformed row remains queued instead of being silently dropped.

## Model Alignment

`DCTop::SelectbyInstQ` removes a block from the decode-side block split queue
when the selected instruction is last-in-block or a parallel `BSTOP` closes the
block. `SPEROB::dealloc()` calls `CommitLast()` when a retired ROB row is
block-last and not flushed. The reduced Chisel path still uses marker consume
timing for `BSTART`/`BSTOP`, but the active-context owner now mirrors the model
handoff shape: select the active full BID, produce scalar completion, then let
`BlockScalarDoneSequencer` issue the delayed retire/free pulse.

`BCtrlUnit::RunFetchStage5` allocates a block BID for marker rows before the
row reaches dispatch. Later, `SPEROB::dealloc()` and the block commit helpers
observe the retired row image. R172 follows that order by treating
`BlockMarkerRetireSource.blockBid` as row-owned retirement evidence instead of
performing another BROB allocation when the marker retires.
R175's marker-row completion shell covers only the command-path completion
piece of that model order. A full live switch away from `skipBlockMarkers=true`
still needs an explicit decode/retire split for active block context, because
model decode uses the `BSTART` BID for subsequent scalar rows before the
corresponding marker row has retired.
R176 adds `BlockMarkerDecodeContext` as that decode-time owner. This lifecycle
module still owns retire-time scalar-done, delayed block retire, retired marker
source consumption, and marker redirect policy. Do not reintroduce following-row
BID assignment into this module while the decode context exists.
R177 wires the decode context into `DecodeRenameROBPath` behind
`useMarkerDecodeContext=true`; this lifecycle owner remains the default live
source while `skipBlockMarkers=true` is still used by the top-level harness.
R192 covers the marker-row mode that does use `useMarkerDecodeContext=true`.
The model order is still row-owned: the redirecting marker row allocated its
own BROB entry before dispatch, so even when its retire-time policy redirects
and does not open a scalar body, the row-owned BID must become completed and
retired. The Chisel lifecycle preserves that by separating previous-active BID
completion from the marker-only row BID completion.

The C++ model carries block-control context per scalar thread. `BRQ` owns
`stashH[stid]` and `brq[stid]` and sizes both arrays from
`scalar_smt_thread` in `BRQ::Build`. `BCtrlUnit::Build` similarly sizes
`currentBID`, `currentBHeader`, `currentBlkCmd`, and stall state by
`scalar_smt_thread`. `BlockROB::Build` creates per-STID `current` and `next`
state, while `DCTop::Build` sizes `blockSplitDCTopArr[pe][stid]` and
`lastHeader[stid]`. R174 mirrors that ownership shape in Chisel by making the
reduced active marker state lane-indexed even though the live bring-up top
still instantiates one lane.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerDecodeContextSpec`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycleSpec`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPathSpec`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r168-block-marker-lifecycle-smoke --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r172-retired-marker-lifecycle-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`

The R172 focused test adds retired-boundary and malformed-boundary reference
cases. The live CoreMark gate captured 6000 QEMU rows, normalized 5138 QEMU/DUT
rows, compared 5137 rows, and passed with zero mismatches and no CBSTOP
divergence.
R174 adds a two-STID reference case that proves a STID1 marker stop completes
only STID1 while STID0 remains active. The R174 live CoreMark gate
`generated/r174-stid-marker-lifecycle-6000-qemu-elf-xcheck` captured 6000 QEMU
rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, compared
5137 rows, and passed with zero mismatches and no CBSTOP divergence. The
manifest recorded dirty LinxCore `b8b94fa82e4f67d3c322b71fd92ccd11b4b36786`
for the uncommitted packet, clean LinxCoreModel
`3c0878da3aa1e06669b718e93269f094e7244066`, and clean QEMU
`08783bb4572df9c5f6623bed0d468641befab762`.
R192 adds a reference case for a marker-owned redirect boundary block. The
focused R192 live CoreMark marker-row gate
`generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck` admitted and
filtered 36 marker commits, normalized 88 QEMU/DUT rows, compared 88 rows, and
passed with zero mismatches and no CBSTOP divergence. The default skip-mode
regression `generated/r192-default-skip-regression-qemu-elf-xcheck` compared
the three-row prefix with zero marker admissions and zero mismatches.
R515 scales the admitted-marker CoreMark proof beyond R492 with
`generated/r515-row-order-425984-marker-qemu-elf-xcheck`: 425984 raw QEMU rows,
425850 expected rows, 30727 admitted and filtered marker commits, 395123
normalized rows per side, 395122 compared rows, zero mismatches, and no CBSTOP
divergence. The manifest records clean LinxCore
`d6f405c33184a96b01aeba425ceb99877488ecb6`, clean LinxCoreModel
`793722e85c62eade9ab4e8481c9577dc5b9c98f7`, clean QEMU
`3fb2ae66bc4d1976731b43e38e53c3c60d92f0c7`, and clean superproject
`50ebc41d3267b6ff78ee4a821e747ed7f0f4a87b`.
R516 scales the same admitted-marker CoreMark proof beyond R515 with
`generated/r516-row-order-458752-marker-qemu-elf-xcheck`: 458752 raw QEMU rows,
458618 expected rows, 33068 admitted and filtered marker commits, 425550
normalized rows per side, 425549 compared rows, zero mismatches, and no CBSTOP
divergence. The manifest records clean LinxCore
`08f72966f1c356ffa3a194e70e0b8f040b0576a9`, clean LinxCoreModel
`793722e85c62eade9ab4e8481c9577dc5b9c98f7`, clean QEMU
`63bb880da43bec3cb980b91c29397c1d2f12cf3f`, and clean superproject
`ff30111e5e92b74b8778311c4329901ec74c4b9c`.
R517 scales the same admitted-marker CoreMark proof beyond R516 with
`generated/r517-row-order-491520-marker-qemu-elf-xcheck`: 491520 raw QEMU rows,
491386 expected rows, 35408 admitted and filtered marker commits, 455978
normalized rows per side, 455977 compared rows, zero mismatches, and no CBSTOP
divergence. The manifest records clean LinxCore
`283b25b69ab48508a5792c074e8be68aea37fb75`, clean LinxCoreModel
`793722e85c62eade9ab4e8481c9577dc5b9c98f7`, clean QEMU
`63bb880da43bec3cb980b91c29397c1d2f12cf3f`, and clean superproject
`5b28ed8d1ef02466d59153c762bec464c4ffd75d`.
R518 scales the same admitted-marker CoreMark proof beyond R517 with
`generated/r518-row-order-524288-marker-qemu-elf-xcheck`: 524288 raw QEMU rows,
524154 expected rows, 37749 admitted and filtered marker commits, 486405
normalized rows per side, 486404 compared rows, zero mismatches, and no CBSTOP
divergence. The manifest records clean LinxCore
`838dc573c27297622aa4d6ed3b8e969cb73c0d0a`, clean LinxCoreModel
`793722e85c62eade9ab4e8481c9577dc5b9c98f7`, clean QEMU
`63bb880da43bec3cb980b91c29397c1d2f12cf3f`, and clean superproject
`4013f546b03db6ce5225cd1a5f8a5e10c806cc64`.
R519 observes the same admitted-marker CoreMark proof beyond R518 with
`generated/r519-row-order-557056-marker-qemu-elf-xcheck`: 557056 raw QEMU rows,
556922 expected rows, 40089 admitted and filtered marker commits, 516833
normalized rows per side, 516832 compared rows, zero mismatches, and no CBSTOP
divergence. The manifest records clean LinxCore
`5360d36af2ae6092fdbb4fcc06aee388702c4e40`, clean LinxCoreModel
`793722e85c62eade9ab4e8481c9577dc5b9c98f7`, dirty QEMU
`63bb880da43bec3cb980b91c29397c1d2f12cf3f`, and dirty superproject
`3b542ea2484c02fffbc2ed852793dc5d9ddf31d0`. Treat R519 as dirty-provenance
observation evidence until the same window is rerun on clean QEMU and
superproject state.
