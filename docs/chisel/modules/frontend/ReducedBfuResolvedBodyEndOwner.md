# ReducedBfuResolvedBodyEndOwner

## Purpose

`ReducedBfuResolvedBodyEndOwner` is the reduced frontend boundary that
normalizes a resolved BFU body-end event before
`ReducedBfuStaticGeometryProducer` consumes it. It represents the model
`SetBsize(header, bodyBasePc, bodyEndPc)` handoff as a standalone Chisel module
so a later branch/BFU resolver has a concrete interface to drive.

In R153 this module has two live reduced-top roles. It trains
`ReducedBfuGeometryPredictionLatch` for later local body-window cuts, and it
also provides the same-cycle cold-cut geometry when the current F4 packet is
the packet that proves the body end. The external replay row is still temporary;
a real branch/BFU resolver must eventually drive this interface.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Suppresses a resolved event during frontend flush/start/restart. |
| input | `headerActive`, `activeHeaderPc` | mixed | Active static-predictor header state from `ReducedBfuStaticGeometryProducer`. |
| input | `resolvedValid`, `resolvedHeaderPc`, `resolvedHSizeBytes`, `resolvedBodyEndPc` | mixed | Resolved body-end event payload from the current replay sideband or a future real BFU/branch resolver. |
| output | `geometryValid`, `geometryHeaderPc`, `hsizeBytes`, `bsizeBytes`, `bodyEndPc` | mixed | Accepted geometry event forwarded to `ReducedBfuStaticGeometryProducer`. |
| output | `accepted`, `headerMismatch`, `inactiveDrop`, `flushDrop`, `bodyEndUnderflow` | `Bool` | Diagnostics for accepted and suppressed resolved events. |

## Logic

The owner accepts a resolved body-end event only when no flush is active, a
header is active, and the resolved header PC matches the active header PC. On
acceptance it computes:

```text
bodyBasePc = activeHeaderPc + 2
bsizeBytes = max(resolvedBodyEndPc - bodyBasePc, 0)
```

The zero-saturation is intentional: it mirrors LinxCoreModel
`BFUUtils::SetBsize`, which records zero when the end PC is not beyond the body
base. Rejected events do not emit geometry and instead raise one of the
diagnostic drop flags.

## Model Evidence

- `model/bctrl/bfu/bfu_utils.h`: `SetBsize` records `end_pc - start_pc` when
  the end is beyond the start, otherwise zero.
- `model/bctrl/bfu/bfu_sp.cpp`: `StaticPredictor::Predict` calls `SetBsize`
  when a later block split or `BSTOP` closes the open header.
- `model/bctrl/bfu/bfu.cpp`: BTB/TAGE, loop, and local-end paths also call
  `SetBsize` from a learned end PC.
- `model/bctrl/BCtrl.cpp`: FALL restart consumes `spInfo->hsize`, so the
  resolved event carries `hsize` alongside `bsize` even though R149 still uses
  replay-provided `hsize`.

## Verification

R149 adds reference tests for accepted CoreMark FALL body geometry, resolved
`hsize` carry, header mismatch/inactive/flush drops, and underflow saturation.
The generated-RTL harness fails on any resolved-event rejection. The R153
4000-row replay reports `bfu_resolved_body_end_accepts=483` and zero
QEMU/DUT mismatches while using this owner as both prediction training source
and cold-cut fallback.
