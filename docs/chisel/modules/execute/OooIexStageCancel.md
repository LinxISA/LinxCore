# OooIexStageCancel

## Purpose

`OooIexStageCancel` is the exact protocol for a physical conflict discovered
after an IQ row has crossed P1 and become `inflight`. It lets an I1 or I2
resource owner suppress one pipe copy and return that same canonical row to
normal pick eligibility without delete/reinsert state.

Source owner:

- `chisel/src/main/scala/linxcore/ooo/OooIexStageCancel.scala`

## Request contract

The producer holds a Decoupled request containing:

| Field | Meaning |
|---|---|
| `stage` | exact retained target, `I1` or `I2` |
| `member` | complete ROB-group/BID/member generation identity |
| `reservation` | complete class/bank/slot/reservation generation identity |
| `reasonMask` | one or more legal pipe-local issue reason bits |

The reason bits reuse `OooIexIssueBlockReason`. Structural occupancy, latency
reservation, reflow reservation, sidedoor conflict, and result-bus
reservation may be reported either before pick as policy or after pick as a
stage cancel. Global quiesce, power throttle, class pressure, LDQ pressure,
and store-window pressure are early-only and fail the late reason check.

## Acceptance and retry

Acceptance requires target-stage occupancy, exact member and reservation,
and a nonempty legal reason mask. The lane suppresses an exact pending target
from downstream visibility, but does not clear it until the retry queue has
capacity and the request fires. I2 wins simultaneous I2/I1 requests because
it is older; the I1 producer remains backpressured and retries on the next
available cycle.

An accepted event produces `stageCanceled` and enqueues the original
member/reservation for canonical IQ retry. A stale or malformed event is
accepted as `stageCancelRejected` with separate stage, occupancy, identity,
and reason evidence; it does not change lane or IQ state.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexP1I2LaneSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1LaneSpec
```

The lane UT holds the retry consumer off while exact requests target different
I2 and I1 members together. It proves I2-first arbitration, two retained retry
identities, downstream suppression, quiescence accounting, and ordered drain.
It also proves a stale identity is rejected while the real I1 attempt remains
live. The canonical-IQ IT proves an accepted I2 sidedoor-class cancel clears
only the matching `inflight` row and repicks it into I1.
