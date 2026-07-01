# ReducedBfuBodyCutArm

## Purpose

`ReducedBfuBodyCutArm` is the reduced BFU diagnostic boundary between a latched
geometry prediction and the temporary external replay oracle. It does not
compute body geometry and it does not choose a branch target. It reports whether
the candidate arm row agrees with the latched prediction on `headerPc`,
`hsizeBytes`, and `bsizeBytes`.

This keeps the R150 behavior intact while separating two roles that must become
different owners later:

- `ReducedBfuGeometryPredictionLatch` owns the remembered resolved body-end
  payload used by the local body window.
- The external `reducedBfu*` row is the temporary resolved-event source and
  oracle until a real branch/BFU resolver replaces it.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `predictionValid` | `Bool` | A latched static geometry payload is available. |
| input | `predictionHeaderPc`, `predictionHSizeBytes`, `predictionBSizeBytes` | `UInt(pcWidth.W)` | Latched static geometry that will feed `ReducedBfuBodyCutPredictor` if accepted. |
| input | `armValid` | `Bool` | A candidate body-cut arm event is available. R152 drives this from the external replay row. |
| input | `armHeaderPc`, `armHSizeBytes`, `armBSizeBytes` | `UInt(pcWidth.W)` | Candidate geometry used only to validate and arm the latched prediction. |
| output | `geometryValid` | `Bool` | The prediction row matches the oracle. R153 treats this as diagnostic; top-level body-cut control uses the local body window plus resolved fallback. |
| output | `headerPc`, `hsizeBytes`, `bsizeBytes` | `UInt(pcWidth.W)` | Payload forwarded from the prediction side, not from the arm side. |
| output | `comparable`, `accepted` | `Bool` | Both sides are present, and all compared fields match. |
| output | `headerMatch`, `hsizeMatch`, `bsizeMatch` | `Bool` | Per-field match diagnostics, qualified by `comparable`. |
| output | `headerMismatch`, `hsizeMismatch`, `bsizeMismatch` | `Bool` | Per-field mismatch diagnostics, qualified by `comparable`. |

## Logic

The module is combinational:

1. `comparable` is true when both the latched prediction and candidate arm are
   valid.
2. `accepted` is true only when all three geometry fields match.
3. `geometryValid` follows `accepted`.
4. The forwarded geometry payload always comes from the prediction inputs.

Forwarding the prediction payload is intentional. The arm input is a verification
event, not the body-cut data source. If a future branch/BFU resolver drives the
arm side, it must prove it selects the same geometry as the remembered
prediction before a later packet can remove the temporary oracle.

## Model Evidence

The owner split follows the LinxCoreModel BFU flow:

- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(header)` uses
  `NextBlockPC(headerPc) + spInfo->bsize`; `SetBsize` records the body-size
  payload once an end PC is known.
- `model/bctrl/bfu/bfu_sp.cpp`: static prediction creates/carries the block
  header metadata and learns `bsize` from a later block boundary or `BSTOP`.
- `model/bctrl/bfu/bfu.cpp`: main/local prediction only applies a learned
  `end_pc` to the predicted header when the header PC and valid geometry agree.
- `model/bctrl/bfu/bfu_brq.cpp`: resolved predicted headers require valid
  `bsize` before BFU update paths use the predicted body extent.

## Verification

R152 focused tests elaborate `ReducedBfuBodyCutArm` and cover exact acceptance,
payload ownership, all mismatch diagnostics, and non-comparable idle cases. The
top-level generated-RTL replay reports comparable, accepted, and mismatched arm
counts alongside existing BFU static and resolved body-end diagnostics. R153
keeps this module as a diagnostic oracle check while body-cut control is driven
by `ReducedBfuLocalBodyWindow` or the same-cycle resolved body-end fallback.
