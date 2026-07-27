# B-SIDE B-F0–B-F4

## Status

`BSidePredictionPipeline` implements the five-stage B-SIDE Chisel pipeline with
independent resident stage state, retained prediction responses, retained
training, request-owned speculative GHR/RAS checkpoints, exact I-F4 boundary
matching, and one final B-F4 response per fetch request. `LinxCoreIfu` composes
it with I-SIDE, the canonical redirect arbiter,
final-prediction join, Instruction Buffer, and D1. Dispatch/BRU consume and
validate the carried prediction record at their architectural boundaries.

## Stage Ownership

| Stage | Implemented predictor work | Published result |
|---|---|---|
| B-F0 | Allocate a monotonic prediction tag and exact history row; freeze `ghrBefore` plus the complete RAS image/pointer/count; query NanoBTB/L0 entry | Earliest target/type/direction candidate plus immutable history/RAS snapshot |
| B-F1 | Query uBTB; when the request-owned kind is Return and its frozen RAS is nonempty, select `FastRas` | Higher-ranked early target candidate |
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
correction. Its typed IFU redirect proposal carries PE, STID, trigger
transaction, packet UID, fetch sequence, producer epoch, prediction tag,
checkpoint, GHR/RAS recovery actions, corrected conditional delta, typed RAS
push/pop delta, `PreserveTriggerKillYounger`, and the exact restart PC. The canonical
`IfuRedirectArbiter` assigns `newEpoch`; a predictor-local increment is not an
epoch authority. Taken correction restarts at `target`; not-taken correction
restarts at `fallthroughPc`.

The response and redirect proposal are atomic: a correction cannot dequeue
unless both sinks accept the same event. The response queue holds every field
stable under backpressure.

Accepting that proposal does not mutate speculative history. It marks the STID
history redirect pending. `BSideHistoryQueue` restores `ghrBefore` plus the
saved RAS image, appends the corrected conditional direction or applies the
corrected Call/Return RAS delta exactly once, and prunes younger rows only
after the redirect arbiter returns the same event as the canonical prune. This
is the single history/state ordering point shared with I-SIDE.

## I-F4 Boundary Join

The Decoupled `boundary.valid` signal means that I-F4 delivered an event.
`boundary.bits.valid` says whether that fetch group actually contains a
BSTART/BSTOP control boundary. This distinction is required:

- B-F4 stalls until an event with exact transaction, STID, fetch sequence,
  checkpoint, and epoch arrives;
- an exact event with `bits.valid = false` explicitly confirms that the group
  has no boundary and carries its exact post-assembly fallthrough PC;
- a boundary event from another identity cannot release B-F4;
- an accepted B-F4 request clears only its matching boundary-table row;
- an index collision applies backpressure and never overwrites a resident row;
- PE, packet UID, transaction, STID, fetch sequence, checkpoint, and epoch all
  participate in the exact match.

This makes the final prediction join a retained boundary between I-F4 and
Instruction Buffer admission rather than a fixed-cycle assumption between the
two engines.

## Predictor and Training State

Resolved training enters a retained queue. Duplicate updates are detected by
prediction tag, STID, and epoch. A nonduplicate update must also match the exact
request-owned GHRQ row across PE, transaction, STID, packet UID, fetch sequence,
epoch, checkpoint, request PC, and prediction tag. A stale update is drained and
reported without mutating predictor tables. An accepted update trains:

- NanoBTB, uBTB, and PBTB type/target plus last conditional direction;
- BIM two-bit counter;
- short- and long-history tagged two-bit counters;
- IBTB for indirect branches and indirect calls;
- conditional-only loop direction/confidence;
- no speculative-history mutation; BTB/TAGE/IBTB/loop learned state only.

B-F1/B-F4 RAS lookup uses the row's immutable top/valid snapshot. B-F3/B-F4
lookup and TAGE training use the row's immutable `ghrBefore`; none of these
consumers resamples live per-STID speculative state. Resolve records the same snapshot for a
possible backend recovery. A correct resolve releases the row; a resolve marked
`mispredict` retains it until keyed BRU canonical recovery restores the snapshot
and appends the actual conditional direction or applies the actual Call/Return
delta. ITLB recovery with no B-SIDE trigger row restores the oldest killed
row's GHR and RAS snapshots; start/reset explicitly clears the selected STID's
GHR, RAS, and rows. This unkeyed fallback is ITLB-only: every non-ITLB
`RestoreTrigger`, including backend BRU recovery, must carry and match the exact
request-owned history key. Any prune that would remove an
`appliedValid` conditional row while carrying `GhrRecoveryAction.None` is a
protocol error and fires a hardware assertion. The same rule applies to an
applied RAS row with `RasRecoveryAction.None`; backend integration cannot
silently drop speculative state repair.

TAGE and loop overrides are legal only for conditional branches. RAS is used
only for a return with a nonempty stack. With no boundary, B-F4 preserves the
effective earlier provider and prediction instead of relabeling it as static.

## Production Composition

I-F0 request traffic enters B-F0 through its own retained request queue.
I-F4 boundary completion enters the boundary table independently. B-F4 cannot
retire a transaction until the exact completion is resident.

Every prediction update enters `IfuPredictionJoin`. A tuple-changing update
also proposes a redirect to `IfuRedirectArbiter`; the response and proposal
are accepted atomically. The join cannot expose the transaction until the
canonical redirect has rebased the producer epoch. The accepted redirect is
then broadcast back to B-SIDE as a selective prune and is the only point that
repairs speculative GHR/RAS; learned tables are never cleared by ordinary inner
flush.

The final response also supplies I-F0's resolved next PC. For no-boundary
cachelines, B-F4 uses I-F4's exact fallthrough rather than assuming the aligned
next-line address, preserving correctness for a 2/4/6/8-byte instruction that
crosses the cacheline boundary.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only BSidePredictionPipeline
```

The focused suite covers the following simulation scenarios plus SystemVerilog
elaboration check:

1. cold B-F4 static final correction and exact target restart;
2. B-F4 wait for an explicit exact no-boundary event;
3. trained B-F0 correction followed by non-correcting B-F4 confirmation;
4. long-TAGE direction outranking a conflicting static fallback;
5. direct/call/return/indirect training cannot pollute BIM/TAGE direction
   state;
6. response stability and atomic correction/flush under backpressure;
7. canonical prune stalls a surviving correction response for the whole cycle;
8. exact not-taken correction restart at instruction fallthrough;
9. nonempty RAS final return-target authority;
10. trained IBTB final indirect-target authority;
11. four fetch-line requests accepted in consecutive cycles and four final
   responses returned in consecutive cycles;
12. duplicate retained training detection by prediction identity;
13. stale training rejection without predictor mutation;
14. correction history fields and canonical-only GHR/RAS mutation;
15. request-owned Call push, Return pop, and late re-correction rollback.

The independent `BSideHistoryQueueSpec` covers canonical correction ordering,
late re-correction rollback, immutable training history, backend actual
direction/RAS recovery, ITLB recovery without a resident trigger, and full-queue
capacity recovery, plus multi-STID isolation and one-entry row reuse.

The elaboration check requires all five-stage occupancy, retained response,
typed inner-flush, and boundary-collision ports in generated SystemVerilog.

The shared Instruction Buffer and D1 suites additionally prove that the
expanded prediction tag, fallthrough, confidence, provider, stage, checkpoint,
and epoch remain intact through four-wide transport.

The focused predictor suite contains 16 passing tests and the focused history
queue suite contains 13. The `LinxCoreIfuSpec`
end-to-end scenarios additionally prove final B-F4
metadata on every D1 lane, canonical prediction correction ordering,
cross-line fallthrough, and backend redirect priority. The canonical IFU now
also has generated-RTL dense hot-cache throughput evidence and natural
CoreMark/Dhrystone benchmark promotion. Predictor-specific generated-RTL
recovery stimulus remains open.

## Remaining B-SIDE Work

- Add medium and additional long-history TAGE tables with provider/alternate,
  usefulness, allocation, aging, and deterministic training-port policy.
- Move path-history and loop speculative state onto the same request-owned
  checkpoint and canonical recovery framework.
- Add independent assertions and coverage for every B-F4 provider-rank and
  direction-override pair.
- Add generated-RTL predictor stimulus and full I-SIDE/B-SIDE asynchronous
  throughput/recovery gates.

`skill-evolve: no-update` — the retained response, exact correction,
boundary-event, and training rules are already required by the IFU design and
the `linx-core` skill.
