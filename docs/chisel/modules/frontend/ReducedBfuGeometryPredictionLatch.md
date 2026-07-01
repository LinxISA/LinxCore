# ReducedBfuGeometryPredictionLatch

## Purpose

`ReducedBfuGeometryPredictionLatch` is the reduced BFU handoff between learned
static geometry and body-cut control. `ReducedBfuStaticGeometryProducer` emits a
one-cycle row when it learns `headerPc`/`hsize`/`bsize`; this latch stores that
row for later prediction so the packet that discovers a body end is not clipped
by the geometry it just produced.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears the remembered prediction row. |
| input | `learnValid` | `Bool` | Captures a newly learned static geometry row. |
| input | `learnHeaderPc`, `learnHSizeBytes`, `learnBSizeBytes` | `UInt(pcWidth.W)` | Learned static geometry payload. |
| output | `geometryValid` | `Bool` | A remembered geometry row is available for body-cut prediction. |
| output | `headerPc`, `hsizeBytes`, `bsizeBytes` | `UInt(pcWidth.W)` | Registered geometry consumed by `ReducedBfuBodyCutPredictor`. |

## Logic

The latch is a small register bank with flush priority over learning. Outputs
are the current registered state, so a `learnValid` pulse becomes visible to
body-cut control on the following cycle. That is intentional: the model learns
`bsize` from a body-end event and uses it for later BFU prediction; it should
not retroactively cut the sequential F4 packet that contained the learning
event.

`LinxCoreFrontendFetchRfAluTraceTop` clears this latch on external frontend
flush, start, or explicit restart. It does not clear the latch on the internal
block-marker redirect used for loop re-entry because BFU predictions must
survive the redirect that makes them useful.

## Model Evidence

The storage boundary follows the LinxCoreModel static predictor split:

- `model/bctrl/bfu/bfu_sp.cpp`: `StaticPredictor::Predict` learns body
  geometry and records it in predictor metadata.
- `model/bctrl/bfu/bfu_utils.h`: `SetBsize` records the body-end-derived
  geometry for subsequent `NextBlockPC` calculations.
- `model/bctrl/BCtrl.cpp`: later conversion uses BFU metadata to choose FALL
  restart PC.

## Verification

R150 focused tests elaborate this module and document the key timing rule:
learning does not feed through in the same cycle, and the stored row is visible
to the next prediction cycle. The top-level replay proof for R150 uses the
latched row as the `ReducedBfuBodyCutPredictor` payload, but still requires the
external loop-reentry oracle to arm the cut until a real branch/BFU resolver
exists.
