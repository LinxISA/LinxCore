# ReducedBfuLocalBodyWindow

## Purpose

`ReducedBfuLocalBodyWindow` is the reduced frontend owner that turns a learned
BFU body geometry row into a local body-cut window. It watches the F4 decode
window for the predicted block header, latches the prediction payload when that
header is visible, and holds the geometry until the body-cut predictor fires or
a flush clears the window.

This module does not compute `bsize`, decide whether a conditional loop is
taken, or close BROB state. In R153 the top learns body-cut-eligible geometry
from accepted resolved body-end rows and uses this module for the trained
local header window; same-cycle cold cuts still use
`ReducedBfuResolvedBodyEndOwner` as a resolved fallback.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears the active local body window. |
| input | `f4ScanValid` | `Bool` | Qualifies the F4 slot scan. The top drives this from registered F4 validity, not source out-fire, to avoid a body-cut/source-advance combinational cycle. |
| input | `cutFire` | `Bool` | Releases the active local body window after the predictor cuts the current packet. |
| input | `predictionValid` | `Bool` | A learned body-cut geometry row is available. |
| input | `predictionHeaderPc`, `predictionHSizeBytes`, `predictionBSizeBytes` | `UInt(pcWidth.W)` | Learned BFU geometry payload. |
| input | `f4Valid`, `f4Slots`, `f4ValidMask` | mixed | Current F4 decode-window slots. |
| output | `geometryValid` | `Bool` | A body-cut geometry row is active or is arming in the current F4 window. |
| output | `headerPc`, `hsizeBytes`, `bsizeBytes` | `UInt(pcWidth.W)` | Geometry forwarded to `ReducedBfuBodyCutPredictor`. |
| output | `active`, `armFire`, `releaseFire`, `armSlot` | mixed | Diagnostics for active state, accepted local header match, release, and matched F4 slot. |

## Logic

Each F4 slot is decoded with `FrontendOpcodeDecodeTable`. A slot can arm the
local window only when it is valid, in the F4 valid mask, decodes as a block
boundary, and its PC equals `predictionHeaderPc`.

`armFire` requires no flush, `f4ScanValid`, no already active local window, a
valid prediction, and a matching header slot. While active, the module returns
the registered geometry. On the arming cycle it forwards the incoming
prediction payload directly so the following `ReducedBfuBodyCutPredictor` sees
the geometry without waiting one more cycle. `flushValid` has highest priority;
`cutFire` clears the active window before any later arm can be stored.

## Model Evidence

- `model/bctrl/bfu/bfu_sp.cpp`: `StaticPredictor::Predict` opens a header on
  `BSTART` and records body size when a later boundary, `BSTOP`, or resolved
  body end closes that header.
- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(header)` uses `headerPc + 2` as
  the reduced body base before adding `bsize`.
- `model/bctrl/BCtrl.cpp`: FALL restart uses `inst.pc + spInfo->hsize`, so the
  local window carries `hsize` as part of the geometry payload even when the
  current CoreMark replay has `hsize=0`.

## Verification

R153 focused tests cover matching-header arming, geometry hold until cut,
flush-cycle suppression, nonmatching header rejection, and elaboration:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuLocalBodyWindow
```

The R153 top-level replay also passed after integrating this module with the
resolved body-end fallback:

```bash
BUILD_DIR=generated/r153-local-body-window-4000-rtl-replay-v6 \
FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl \
FETCH_QEMU_MAX_ROWS=0 \
FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 \
FETCH_ELF=tests/benchmarks/build/coremark_real.elf \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

That replay compared 3280 normalized rows with zero mismatches and reported
483 BFU static/external geometry matches.
