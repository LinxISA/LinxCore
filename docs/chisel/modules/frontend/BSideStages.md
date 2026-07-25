# B-SIDE B-F0–B-F4

## Status

R680 implements the first complete five-stage B-SIDE Chisel pipeline with
independent resident stage state, retained prediction responses, retained
training, exact I-F4 boundary matching, and one final B-F4 response per fetch
request. It is production-shaped but is not yet composed with I-SIDE,
Instruction Buffer, D1, Dispatch, or BRU.

## Stage Ownership

| Stage | Implemented predictor work | Published result |
|---|---|---|
| B-F0 | Allocate monotonic prediction tag; query NanoBTB/L0 entry | Earliest target/type/direction candidate |
| B-F1 | Query uBTB | Higher-ranked early candidate |
| B-F2 | Query PBTB and use BIM for conditional direction | Main BTB/BIM candidate |
| B-F3 | Query the short-history tagged table for conditional branches only | Short-TAGE correction |
| B-F4 | Wait for exact I-F4 boundary event; arbitrate static, long TAGE, loop, final IBTB, and valid RAS | Mandatory final response and last prediction-driven correction |

Every stage is a one-entry ready/valid resident owner. A stage that must publish
a correction cannot advance until the retained response queue accepts it.
Response arbitration uses the fixed rank:

```text
B-F4 > B-F3 > B-F2 > B-F1 > B-F0 > sequential
```

The pipeline accepts one fetch-line request per cycle while downstream
capacity is available. A request can therefore represent up to four I-F4/D1
instruction rows without reducing the nominal four-instruction frontend rate.

## Exact Prediction and Correction

The effective prediction record carries:

- prediction tag;
- `{taken, branchPc, target, kind}`;
- fallthrough PC;
- provider and B-SIDE stage;
- confidence;
- checkpoint and effective epoch.

If a candidate changes the accepted exact tuple, the response is marked as a
correction and increments the effective epoch. Its typed IFU inner flush carries
PE, STID, trigger transaction, fetch sequence, old/new epochs, checkpoint, and
the exact restart PC. Taken correction restarts at `target`; not-taken
correction restarts at `fallthroughPc`.

The response and correction flush are atomic: a correction cannot dequeue
unless both sinks accept the same event. The response queue holds every field
stable under backpressure.

## I-F4 Boundary Join

The outer `boundary.valid` signal means that I-F4 delivered an event.
`boundary.bits.valid` says whether that fetch group actually contains a
BSTART/BSTOP control boundary. This distinction is required:

- B-F4 stalls until an event with exact transaction, STID, fetch sequence,
  checkpoint, and epoch arrives;
- an exact event with `bits.valid = false` explicitly confirms that the group
  has no boundary;
- a boundary event from another identity cannot release B-F4;
- an accepted B-F4 request clears only its matching boundary-table row.

This makes the final prediction join a retained boundary between I-F4 and
Instruction Buffer admission rather than a fixed-cycle assumption between the
two engines.

## Predictor and Training State

Resolved training enters a retained queue. Duplicate updates are detected by
prediction tag, STID, and epoch. An accepted nonduplicate update trains:

- NanoBTB, uBTB, and PBTB type/target plus last conditional direction;
- BIM two-bit counter;
- short- and long-history tagged two-bit counters;
- IBTB for indirect branches and indirect calls;
- conditional-only loop direction/confidence;
- RAS push/pop with explicit nonempty/full tracking;
- conditional-only resolved GHR.

TAGE and loop overrides are legal only for conditional branches. RAS is used
only for a return with a nonempty stack. With no boundary, B-F4 preserves the
effective earlier provider and prediction instead of relabeling it as static.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only BSidePredictionPipeline
```

R680 passes nine real Chisel simulation scenarios plus one SystemVerilog
elaboration check:

1. cold B-F4 static final correction and exact target restart;
2. B-F4 wait for an explicit exact no-boundary event;
3. trained B-F0 correction followed by non-correcting B-F4 confirmation;
4. response stability and atomic correction/flush under backpressure;
5. exact not-taken correction restart at instruction fallthrough;
6. nonempty RAS final return-target authority;
7. trained IBTB final indirect-target authority;
8. four fetch-line requests accepted in consecutive cycles and four final
   responses returned in consecutive cycles;
9. duplicate retained training detection by prediction identity.

The elaboration check requires all five-stage occupancy, retained response,
typed inner-flush, and boundary-collision ports in generated SystemVerilog.

The shared Instruction Buffer and D1 suites additionally prove that the
expanded prediction tag, fallthrough, confidence, provider, stage, checkpoint,
and epoch remain intact through four-wide transport.

The exact full Chisel suite was also attempted but is not green in the current
dirty tree. Its first observed failure is `LinxCoreFrontendFetchTraceTop`,
which does not initialize the newly added `DecodeRenameROBPath` same-packet and
store-SC inputs. Static connection audit finds the same missing inputs in
`LinxCoreFrontendTraceTop`, `LinxCoreFrontendAluTraceTop`, and
`LinxCoreFrontendRfAluTraceTop`; production-shaped
`LinxCoreFrontendFetchRfAluTraceTop` connects all six groups. Three concurrently
running suites were interrupted after the observed elaboration failure and are
not classified as B-SIDE failures. R680 does not modify or hide that unrelated
in-progress wrapper integration.

## Remaining B-SIDE Work

- Add speculative GHR/GHRQ checkpoint update and rollback; current GHR changes
  only on resolved training.
- Add medium and additional long-history TAGE tables with provider/alternate,
  usefulness, allocation, aging, and deterministic training-port policy.
- Add explicit cancellation/recovery input for younger resident stages,
  boundary rows, and response rows while preserving learned table state.
- Compose the final B-F4 record with every matching I-F4 instruction before
  Instruction Buffer admission.
- Add generated-RTL predictor stimulus and full I-SIDE/B-SIDE asynchronous
  throughput/recovery gates.

`skill-evolve: no-update` — the retained response, exact correction,
boundary-event, and training rules are already required by the IFU design and
the `linx-core` skill.
