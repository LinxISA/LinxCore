# ReducedScalarRegisterFile

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarRegisterFile.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarRegisterFileSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/isa/ISACommon/GPR.h`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.cpp`
- Contract IDs: `LC-IF-CHISEL-IEX-RF-001`, `LC-IF-CHISEL-XCHK-007`

## Purpose

`ReducedScalarRegisterFile` is the first scalar physical GPR data owner in the
Chisel frontend ALU lane. It replaces the R81 top-level per-uop operand
fixture with persistent register state keyed by renamed physical tags.

This is a reduced RF/ready owner, not the final issue queue or bypass network.
It is intentionally single-bank, STID0/PE0 oriented, and exists to make
dependent scalar ALU rows observable through the monitored commit trace.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `readValid` | `Vec(3, Bool)` | valid | Source lanes to check and read. Invalid lanes read as don't-care and are ready. |
| input | `readTags` | `Vec(3, UInt(physRegWidth.W))` | with `readValid` | Physical source tags from `RenamedUop.src(*).physTag`. |
| output | `readData` | `Vec(3, UInt(64.W))` by default | combinational | Current data for each physical source tag. |
| output | `readReady` | `Vec(3, Bool)` | combinational | Per-source ready state; invalid lanes are reported ready. |
| output | `allReadReady` | `Bool` | combinational | Reduction of `readReady`; the RF-backed top keeps this as diagnostic while `ReducedScalarIssueQueue` samples `readyMask` into registered per-entry readiness. |
| input | `auxReadValid` | `Vec(3, Bool)` | valid | Auxiliary source lanes for non-issue readers such as reduced STA address execution. |
| input | `auxReadTags` | `Vec(3, UInt(physRegWidth.W))` | with `auxReadValid` | Physical source tags for the auxiliary read lanes. |
| output | `auxReadData` | `Vec(3, UInt(64.W))` by default | combinational | Current data for each auxiliary physical source tag. |
| output | `auxReadReady` | `Vec(3, Bool)` | combinational | Per-auxiliary-source ready state; invalid lanes are reported ready. |
| input | `initValid` | `Bool` | pulse | Preload one architectural identity register for reduced top fixtures. |
| input | `initArchTag` | `UInt(archRegWidth.W)` | with `initValid` | Architectural GPR tag. In reset identity state this is the same physical tag. |
| input | `initData` | `UInt(64.W)` by default | with `initValid` | Initial data for the identity physical tag. |
| input | `clearValid` | `Bool` | pulse | Mark an allocated destination physical tag not-ready. |
| input | `clearTag` | `UInt(physRegWidth.W)` | with `clearValid` | Destination physical tag allocated by rename. |
| input | `writeValid` | `Bool` | pulse | Write a completed scalar GPR result. |
| input | `writeTag` | `UInt(physRegWidth.W)` | with `writeValid` | Physical destination tag from execute completion. |
| input | `writeData` | `UInt(64.W)` by default | with `writeValid` | Result data to store and mark ready. |
| output | `readyMask` | `UInt(physRegs.W)` | diagnostic | Current ready bit vector. |
| output | `stateError` | `Bool` | diagnostic | Init, clear, or write attempted an out-of-range tag. |

## State

- `data[physRegs]`: scalar physical GPR values.
- `ready[physRegs]`: reduced physical tag readiness.

Reset initializes physical tags `0..23` ready and tags `24..physRegs-1`
not-ready.
Data resets to zero. This follows `GPR::GPR_COUNT = 24` and
`GPRRename::Build`, where the speculative and committed maps start as
architectural identity maps and free tags begin above the architectural count.
R141 lets this reduced RF elaborate with 128 physical entries, matching
LinxCoreModel `ggpr_count`, while keeping initialization in the 6-bit
architectural reg namespace. The init path pads `initArchTag` to the configured
physical tag width before indexing the RF.

## Logic Design

`GPRRename::RenameSrc` maps an architectural source through `smap` into a
physical tag, and `GPRRename::RenameDst` allocates a new free physical tag for
scalar GPR destinations. The model ready table stores data and readiness by
physical tag: `ReadyState::InitGGPRRtable` initializes a tag with data and
ready, and `ReadyState::GetSrcData` copies ready physical-tag data back into
operand source fields. The RF model read path in `iex_rf.cpp` reads OPD_GREG
data by physical tag.

The Chisel module mirrors the reduced subset:

- preload writes architectural identity tags for test fixtures;
- allocation clear marks the renamed destination physical tag not-ready;
- execute writeback stores result data and marks the destination physical tag
  ready;
- read ports return data and readiness for the current physical source tags.
- auxiliary read ports return the same combinational data/readiness for
  sideband owners without arbitrating away the scalar issue queue's three
  source-read lanes.

If init, clear, and write target the same tag in one cycle, writeback has final
priority because it is applied after init and clear in the sequential block.

## Timing

Reads are combinational from registered RF state. Init, clear, and write are
registered on the rising edge. In the R87 top, the issue queue samples
`readyMask` into registered per-entry source-ready state, and
`ReducedScalarIssuePick` captures `readData` from the selected oldest-ready row
into execute only when every valid source lane for that row has confirmed RF
read readiness.

R82 did not feed `allReadReady` back into `DecodeRenameROBPath` readiness,
because `ScalarDecodeRenameBridge.outValid` and source-valid bits are
acceptance-gated. R87 keeps that rule and uses `ReducedScalarIssueQueue` plus
`ReducedScalarIssuePick` as the readiness owner between rename and execute:
rename readiness is capacity, while source readiness and selected-row RF read
confirmation gate only issue.

## Flush/Recovery

No flush, replay, commit-map restore, or physical-tag free handling exists in
this reduced owner. Those behaviors remain in `GPRRenameCheckpoint` and future
issue/ready-table packets. This RF only proves persistent source data and
writeback for the serialized frontend ALU trace path.

## Trace/Observability

`readyMask`, `readReady`, `allReadReady`, `stateError`, and the parent top's
`rfWrite*` outputs provide enough visibility for the RF-backed Verilator gate.
The architectural evidence remains the monitored commit row: source, dst, and
writeback data must match the QEMU-shaped reference rows.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarRegisterFile`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
