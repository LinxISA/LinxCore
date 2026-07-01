# ReducedBfuStaticGeometryProducer

## Purpose

`ReducedBfuStaticGeometryProducer` is the first reduced Chisel owner for
LinxCoreModel static-predictor block geometry. It watches accepted F4 slots,
opens a header on a decoded block-boundary instruction, and emits
`headerPc`/`hsize`/`bsize` geometry when a later block boundary, `BSTOP`, or
normalized resolved body-end event closes the current body.

This module does not replace full BFU scan ownership. It only implements the
model rule for explicit block-split and stop events. CoreMark's current FALL
loop still needs the external loop replay geometry because its static
continuation at `0x4000632e` decodes as `OP_ACRC`, not as a block-boundary
event in the current Chisel metadata. R146 therefore models the resolved-end
learning path separately: an external or future branch-resolution owner can
provide the body end without classifying that instruction as a boundary. R147
also carries a resolved `hsize` value on that same diagnostic row while keeping
the registered static-predictor header size at zero until a real producer is
identified.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears the active reduced header state. |
| input | `f4UpdateFire` | `Bool` | Pulses when the current F4 packet is accepted and may update active header state. |
| input | `f4Valid`, `f4Slots`, `f4ValidMask` | mixed | Current F4 window and decoded slot shape. |
| input | `resolvedBodyEndValid`, `resolvedHeaderPc`, `resolvedHSizeBytes`, `resolvedBodyEndPc` | mixed | Resolved geometry learning hook for the active header. This mirrors the model path that sets `bsize` from an end PC learned outside explicit block-marker decode and carries the resolved `hsize` payload alongside it. |
| output | `geometryValid` | `Bool` | A block body geometry row was learned this cycle. |
| output | `headerPc`, `hsizeBytes`, `bsizeBytes` | `UInt(pcWidth.W)` | Geometry consumed by `ReducedBfuBodyCutPredictor`. |
| output | `headerActive`, `learnedFire`, `eventLearnedFire`, `resolvedLearnedFire` | `Bool` | Reduced diagnostics for active header state and whether geometry came from an explicit F4 event or a resolved body end. |
| output | `eventValid`, `eventSlot`, `eventPc`, `eventIsBoundary`, `eventIsStop` | mixed | First block-boundary or stop event observed in the accepted F4 window. |

## Logic

For each visible F4 slot, the producer reuses `FrontendOpcodeDecodeTable` to
detect `isBlockBoundary` and `isBlockStop`. The first event in slot order owns
the combinational event geometry for the cycle, while `f4UpdateFire` gates the
registered active-header update. A matching resolved body end takes geometry
priority over the explicit F4 event output, but does not by itself change the
active-header state:

- If no header is active, a boundary opens a new header.
- If a header is active and a later boundary appears, the producer emits
  `bsize = boundaryPc - (headerPc + 2)` and then opens the new header.
- If a header is active and a `BSTOP` appears, the producer emits
  `bsize = (bstopPc + bstopLen) - (headerPc + 2)` and clears the active header.
- If a matching resolved body end appears, the producer emits
  `hsize = resolvedHSizeBytes` and
  `bsize = resolvedBodyEndPc - (headerPc + 2)`, then leaves the active header
  state unchanged. A flush suppresses both explicit-event and resolved-end
  learning for that cycle.

The registered `hsizeBytes` is currently zero, matching the reduced compressed
FALL replay case and the observed model static-predictor path. The resolved
input lets a future branch-resolution/header-size owner provide a value without
routing this diagnostic producer into control. R149 factors resolved-event
normalization into `ReducedBfuResolvedBodyEndOwner`, so this producer now
consumes an already matched active-header event instead of comparing the raw
replay header itself.

## Model Evidence

The owner boundary mirrors LinxCoreModel:

- `model/bctrl/bfu/bfu_sp.cpp`: `StaticPredictor::Predict` opens
  `SPMInstInfo` on block-split headers and calls `SetBsize` when it sees the
  next block split or `BSTOP`.
- `model/bctrl/bfu/bfu.cpp`: later BFU prediction and local-end paths also
  call `SetBsize` when an end PC is learned for the active header.
- `model/bctrl/bfu/bfu_utils.h`: `SetBsize(header, start_pc, end_pc)` records
  `end_pc - start_pc`, and `NextBlockPC(header)` starts from the 16-bit body
  base before adding `bsize`.
- `model/bctrl/BCtrl.cpp`: `ConvertToBHeader` resolves FALL restart from
  `inst.pc + spInfo->hsize`.
- `model/ModelCommon/SimInstInfo.h` and `SimInstInfo.cpp`: `SPMInstInfo::hsize`
  defaults to zero and is copied through BFU machine-info wrappers in the
  observed path; R147 therefore carries resolved `hsize` as diagnostic payload
  only.

## Verification

R145 focused tests cover the reference geometry rule for boundary-closed and
`BSTOP`-closed bodies, document that CoreMark's `OP_ACRC` continuation is not
yet a producer event, and elaborate the Chisel module. R146 adds a resolved
body-end reference case for the CoreMark FALL body ending at `0x4000632e`.
R147 adds a resolved-`hsize` reference case and keeps the producer diagnostic.
R148 compares the producer's emitted diagnostic geometry against the external
replay geometry at `LinxCoreFrontendFetchRfAluTraceTop`, proving the handoff
contract before the producer is allowed to drive reduced body-cut control.
R149 inserts `ReducedBfuResolvedBodyEndOwner` in front of the resolved input and
keeps the same replay comparison as the generated-RTL proof. R150 feeds this
producer's learned geometry into `ReducedBfuGeometryPredictionLatch`, and
`ReducedBfuBodyCutPredictor` consumes the latched row when the external
loop-reentry oracle arms a matching cut. The external replay row remains the
temporary resolved-event source and oracle.
