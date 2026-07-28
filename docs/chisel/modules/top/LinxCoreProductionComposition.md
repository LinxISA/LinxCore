# LinxCoreProductionComposition

## Purpose

`LinxCoreProductionComposition` is the single promoted frontend composition
owner. It connects the four production wrappers without reconstructing request
identity at a seam:

```text
tagged 64-byte memory <-> IfuLineMemoryBridge <-> LinxCoreIfu
                                                    |
                                                    v
                                      D1InstructionDecodeStage

backend validation -> IfuBackendFeedbackBridge -> B-SIDE training
                                      `---------> canonical BRU recovery

applied OOO recovery -> recoveryRedirect --------> canonical IFU recovery
```

`LinxCoreIfu` remains the owner of I-SIDE, B-SIDE, canonical epoch allocation,
the Instruction Buffer, and fixed-width D1 grouping. The composition replaces
the former standalone connection gaps; it does not replace those stage owners.

## External interfaces

The production boundary exposes:

- `start`, PTW request/refill, ITLB/L1I invalidation, and fetch-fault channels;
- a tagged lower-memory request/response port carrying only tag, physical line,
  and 512-bit line data;
- one selected D1 STID and a four-lane `D1DecodedInstructionGroup` output;
- an exact `BackendBranchValidation` input supplied by Dispatch or BRU E1;
- a retained `recoveryRedirect` supplied only after the exact OOO common apply;
- canonical flush, epoch, miss, line-transport, join, line-context, B-SIDE, PTW,
  and feedback residency diagnostics.

There is no raw line-refill identity port and no raw prediction-training port.
The line bridge reconstructs refill identity from retained requests, while the
feedback bridge constructs training and BRU recovery from the retained final
prediction sidecar.

## Capacity and ownership rules

The composition requires architectural 64-byte cache lines and enforces
`lineBridgeEntries >= missEntries`. An I-F2 miss therefore cannot consume an
IFU miss row while losing the corresponding lower-memory transport capacity.
Accepted memory requests are not cancelled independently by speculative
recovery; orphan completion remains owned by the IFU miss table.

The D1 output is the direct result of `D1InstructionDecodeStage`. It consumes
the Instruction Buffer's fixed 64-bit entries and carries final B-F4 prediction
metadata on every valid lane. No packet-window or variable-width instruction
representation is inserted by the composition.

## Backend feedback ordering

A B-F4 correction preserves its request-owned GHR/RAS checkpoint while the
canonical redirect rebases that surviving row into the newly allocated epoch.
A later Dispatch/BRU mismatch therefore matches the same checkpoint using the
epoch carried by D1.

Mispredict training and backend recovery enter the composition as one retained
feedback event. When the exact `BruRecovery` prune reaches B-SIDE in the same
cycle as its queued resolve, predictor training consumes the immutable
checkpoint before the prune removes it. Unrelated training remains blocked by
an active prune.

An applied production OOO redirect has priority over the compatibility
feedback redirect. If both proposals are bit-exact apart from the IFU-owned
`newEpoch`, the composition consumes both on one IFU handshake and emits one
canonical flush. If they differ, the compatibility event remains queued. The
OOO recovery bridge waits for `canonicalFlush`; `recoveryRedirect.ready` alone
does not close R4.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreProductionComposition
bash tools/chisel/run_chisel_tests.sh --only BSideHistoryQueue
bash tools/chisel/run_chisel_tests.sh --only BSidePredictionPipeline
bash tools/chisel/run_chisel_linxcore_production_composition_probe.sh
```

The focused composition suite proves a translated tagged line miss through
the memory bridge into a full four-wide decoded D1 group, plus a real
`BSTART Direct ... BSTOP` block whose Dispatch target mismatch produces exact
training and canonical `BruRecovery`. The emitted-RTL probe repeats both paths
and observes consecutive epoch allocation for B-F4 correction followed by
backend recovery.
`OooFrontendIfuRecoveryIntegration` additionally connects the production OOO
R4 bridge to this real composition and proves that the IFU-allocated epoch is
the terminal frontend acknowledgement.

## Remaining production boundary

The `backendValidation` and applied `recoveryRedirect` ports are intentionally
explicit. The standalone frontend composition does not instantiate the full
OOO coordinator. Natural CoreMark/Dhrystone benchmark integration remains O9.
Lower-memory denied/corrupt termination also requires a typed fetch-fault
extension; it must not be represented as a successful zero-data refill.
