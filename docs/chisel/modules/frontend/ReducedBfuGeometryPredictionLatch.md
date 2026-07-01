# ReducedBfuGeometryPredictionLatch

## Purpose

`ReducedBfuGeometryPredictionLatch` is the reduced BFU handoff between accepted
resolved body-end geometry and local body-window control. The top stores
`headerPc`/`hsize`/`bsize` after `ReducedBfuResolvedBodyEndOwner` accepts a
loop body-end event, then `ReducedBfuLocalBodyWindow` uses the remembered row
when the predicted header is visible again.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears the remembered prediction row. |
| input | `learnValid` | `Bool` | Captures an accepted resolved body-end geometry row. |
| input | `learnHeaderPc`, `learnHSizeBytes`, `learnBSizeBytes` | `UInt(pcWidth.W)` | Learned geometry payload. |
| output | `geometryValid` | `Bool` | A remembered geometry row is available for body-cut prediction. |
| output | `headerPc`, `hsizeBytes`, `bsizeBytes` | `UInt(pcWidth.W)` | Registered geometry consumed by `ReducedBfuBodyCutPredictor`. |

## Logic

The latch is a small register bank with flush priority over learning. Outputs
are the current registered state, so a `learnValid` pulse becomes visible to
local body-window control on the following cycle. That is intentional: the
model learns `bsize` from a body-end event and uses it for later BFU prediction.
R153 handles the cold same-cycle body-end cut through
`ReducedBfuResolvedBodyEndOwner` directly instead of making this latch
feed through combinationally.

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
to the next prediction cycle. The R153 top-level replay uses the latch as the
payload source for `ReducedBfuLocalBodyWindow`; the external loop-reentry oracle
still supplies resolved body-end events until a real branch/BFU resolver exists.
