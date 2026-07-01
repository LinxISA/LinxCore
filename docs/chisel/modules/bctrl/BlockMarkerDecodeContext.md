# BlockMarkerDecodeContext

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerDecodeContext.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/bctrl/BlockMarkerDecodeContextSpec.scala`
- Planned integration:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockMarkerLifecycle.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`

## Purpose

`BlockMarkerDecodeContext` is the decode-time owner for active block BID
assignment when marker rows are admitted into the ordinary ROB path. It tracks
the current active block context per STID and answers one question for the row
being allocated: should this row reuse an active BID, or should it carry the
new BID currently offered by `DispatchROBAllocator`?

This module is intentionally separate from `BlockMarkerLifecycle`.
`BlockMarkerDecodeContext` runs before the row reaches rename/ROB, so following
scalar rows can receive a decoded `BSTART` row's BID immediately. The retire
lifecycle remains responsible for row-retirement effects: scalar-done, delayed
BROB retire, and marker redirect policy.

R176 adds the module, reference tests, and integration contract. It does not
flip the live top away from `skipBlockMarkers=true`.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| Input | `flushValid` | `Bool` | Clears all decode-side active context. |
| Input | `decodeFire` | `Bool` | Current decoded row is accepted for ROB allocation. |
| Input | `decodeBoundary` | `Bool` | Accepted row is a `BSTART` boundary marker. |
| Input | `decodeStop` | `Bool` | Accepted row is a `BSTOP` marker. |
| Input | `decodeStid` | `UInt(stidWidth.W)` | STID lane for the accepted decoded row. |
| Input | `decodeAllocBid` | `UInt(bidWidth.W)` | Full BID offered by the allocator for rows that start a new block. |
| Input | `decodeTarget` | `UInt(pcWidth.W)` | Boundary target stored for following fallback/retire policy. |
| Input | `decodeBoundaryKind` | `BoundaryKind` | Boundary class stored with the active context. |
| Input | `scalarRedirectValid/scalarRedirectStid` | mixed | Execute-owned redirect cleanup for one STID lane. |
| Input | `robBlockLastValid/robBlockLastBid` | mixed | ROB deallocation cleanup for any active lane matching the full BID. |
| Input | `queryStid` | `UInt(stidWidth.W)` | STID lane selected for active-state diagnostics. |
| Output | `decodeStidInRange` | `Bool` | Accepted row's STID maps to an instantiated context lane. |
| Output | `decodeActiveValid/decodeActiveBid` | mixed | Pre-update active context for the accepted row's STID. |
| Output | `decodeBlockBid` | `UInt(bidWidth.W)` | Full BID to attach to the accepted row before enqueue to rename/ROB. |
| Output | `decodeUsesExistingBlock` | `Bool` | Accepted row reuses the pre-update active BID and should not allocate a new BROB entry. |
| Output | `decodeStartsNewBlock` | `Bool` | Accepted row starts a new active block context. |
| Output | `decodeClosesActive` | `Bool` | Accepted boundary or stop observes a pre-existing active context. |
| Output | `decodeStopWithoutActive` | `Bool` | Diagnostic for a `BSTOP` accepted without an active decode context. |
| Output | `activeValid/activeBid/activeTarget` | mixed | Current active context for `queryStid`. |
| Output | `activeCond/activeUnconditionalRedirect` | `Bool` | Stored boundary class diagnostics for `queryStid`. |

## Logic Design

- State is an STID-indexed active context array containing valid, full BID,
  target, conditional flag, and unconditional redirect flag.
- A decoded boundary marker always uses `decodeAllocBid`, even when an older
  active context exists. The boundary installs that new BID as active for the
  STID after the row is accepted.
- A decoded scalar row reuses the active BID when one exists. If no active
  context exists, the scalar row uses `decodeAllocBid` and seeds a scalar-owned
  active block with zero target.
- A decoded stop marker reuses the active BID, then clears the STID lane. If no
  active context exists, `decodeStopWithoutActive` reports the malformed local
  condition.
- Scalar redirect cleanup clears only `scalarRedirectStid`.
- ROB block-last cleanup clears every active lane whose full BID matches
  `robBlockLastBid`.
- Flush has highest priority. Scalar redirect has the next priority, followed by
  decoded boundary install, decoded stop clear, scalar-start install, and ROB
  block-last cleanup.

## Model Alignment

`DCTop::Work()` selects a decoded instruction, assigns `inst->rid`, allocates
the SPE ROB row through `SPEROB::allocROB(inst)`, assigns load/store IDs, and
only then writes the row to `dec_ren_q`. `SPERename::Rename()` consumes that
queue later, and `SPERename::InsertToSIEXQ()` routes `InstGroup::BLOCK_SPLIT`
instructions to the command path rather than the scalar ALU path.

That ordering means Chisel must decide the row's full block BID before rename
and before the marker row can retire. `BlockMarkerDecodeContext` captures the
decode-time half of that model order. `BlockMarkerLifecycle` and the marker
retire-source serializer continue to capture retired row evidence from
`SPEROB::dealloc()` timing.

## Integration Contract

When this module is wired into `DecodeRenameROBPath`, the intended split is:

- `BlockMarkerDecodeContext.decodeBlockBid` replaces the current direct
  `Mux(activeBlockValid, activeBlockBid, allocator.io.allocBlockBid)` selected
  BID.
- `BlockMarkerDecodeContext.decodeUsesExistingBlock` drives
  `DispatchROBAllocator.allocUsesExistingBlock`.
- `BlockMarkerDecodeContext.decodeStartsNewBlock` identifies rows that need a
  new BROB allocation.
- `BlockMarkerLifecycle` keeps owning scalar-done, delayed retire, retired
  marker consumption, and marker redirect policy.
- The live top remains in marker-skip mode until the C++ harness can compare
  admitted marker rows without treating them as DUT-only skip rows.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerDecodeContextSpec`
- `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycleSpec`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTopSpec`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r176-marker-decode-context-prep-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`

R176 tests cover scalar seeding, boundary replacement of an active BID, stop
reuse and clear, STID isolation, flush/redirect/ROB block-last cleanup, malformed
stop diagnostics, and SystemVerilog elaboration.

The R176 live CoreMark regression
`generated/r176-marker-decode-context-prep-6000-qemu-elf-xcheck` captured
6000 QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows,
compared 5137 rows, and passed with zero mismatches and no CBSTOP divergence.
The manifest recorded dirty LinxCore
`e689761fcf80dcef44c76f4917a304ca67ed4e42` for the uncommitted packet, clean
LinxCoreModel `3c0878da3aa1e06669b718e93269f094e7244066`, dirty QEMU
`08783bb4572df9c5f6623bed0d468641befab762`, and dirty superproject
`bc456855704986c24c91e485cb27ac0d27a48c84`.
