# OooIexP1I2Lane

## Purpose

`OooIexP1I2Lane` is one reusable canonical read lane between the implemented
IQ/P1 bridge and an execution pipe. It makes the P1, I1, and I2 transaction explicit
without becoming a second owner of the IQ, P/T/U register files, PC buffer, or
recovery state.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexP1I2Lane.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexStageCancel.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexP1I2LaneSpec.scala`
- `chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala`

## Stage contract

| Stage | Retained state | Advance or cancellation |
|---|---|---|
| P1 | no independent architectural state; accepts one selected joined IQ row | exact row shape enters I1; malformed shape produces a typed reject |
| I1 | full selected row, source tags, exact W1/W2/W3 bypass selection, RF-needed mask, optional parent PC token | one whole-uop read decision grants every remaining RF source and PC port, denies for exact repick, or accepts an exact I1 resource cancel |
| I2 | full row, merged RF/bypass values, bypass mask/provenance, and reconstructed PC | retained under backpressure until a later handoff, exact recovery/load cancellation, or accepted I2 resource cancel |

P1 requires exact member/group/BID/reservation identity, a valid primary
parent, registered-ready valid sources, and a valid selected PC token when PC
is requested. Invalid inputs are consumed as `p1Rejected`; they cannot wedge
the ready/valid boundary.

I1 selects the newest legal bypass age in W1, W2, W3 order using exact
STID/epoch and PTag-generation or T/U sequence identity. A speculative source
additionally requires the complete `{producer RobMemberKey, loadGeneration}`
to match. A stale generation cannot unlock I1, and an uncovered speculative
source waits rather than falling back to RF. A legal bypass hit is removed
from the RF-needed `sourceMask`.

I1 presents one `OooIexI1ReadAttempt`. The shared RF/PC arbiter must
return one explicit `readDecisionValid/readGrant` result for the whole uop.
Port denial, an incomplete readyless response, or a typed P1 rejection
produces `repick` with the original member and reservation. A
grant advances only if the valid-return mask exactly matches the requested
source mask and the optional PC return is valid. A partial readyless response
produces `readRejected`, exact repick, and never publishes a partial I2 transaction. The
physical IQ remains the canonical row owner, so denial or rejection does not
release its reservation.

I2 is the first retained data-bearing stage. Its row, merged operand values,
logical-source mask, bypass mask/provenance, and PC remain stable while the
consumer applies backpressure. This module does not free the IQ row.
[`OooIexE1TransferSlot`](OooIexE1TransferSlot.md) is now the class-specific
next owner: its one acceptance fire is coupled to the existing exact IQ and
dispatch release fire, so no cycle exists in which neither side owns recovery.
[`OooIexE1TransferFabric`](OooIexE1TransferFabric.md) supplies static
domain/class/bank/release-port ownership. Domains sharing one release port
serialize fairly; domains on distinct ports can transfer together.

## PC timing

The current `OooPcBuffer` is readyless and returns the selected base PC. I1
requests the generation-qualified buffer index; I2 adds the retained byte
offset and registers the reconstructed full PC.

A synchronous PC or RF macro must add an explicit retained response stage or
split I1 into request/response phases. It must not hide latency by holding an
unregistered request token or duplicating allocation/recovery ownership.

## Recovery

Canonical `RecoveryPlan` membership is checked independently for retained I1
and I2 rows. Only an accepted Apply phase suppresses the affected output,
clears the retained stage, and reports the exact member/reservation in
`recoveryCanceled(0)` for I1 or `(1)` for I2. An unrelated STID continues.

## Speculative load cancellation

`OooIexLoadCancel` matches the IQ-row source token by complete
`{STID,epoch,producer RobMemberKey,loadGeneration}`. Matching P1, retained I1,
and retained I2 copies are suppressed and reported independently through
`loadCanceled(0..2)`. The lane does not mutate IQ state or manufacture a
second retry queue; the canonical issue owner observes the same event and
clears matching `specReady` plus `inFlight`. This allows one cancel to poison
I1 and I2 dependents concurrently. A stale generation leaves both stages
unchanged.

## Exact stage cancellation

`stageCancel(0)` addresses I1 and `stageCancel(1)` addresses I2. Each
backpressurable request carries an explicit stage, complete ROB member,
dispatch reservation, and the shared issue reason mask. Legal late reasons
are domain structural occupancy, latency reservation, reflow reservation,
LSU sidedoor conflict, and result-bus reservation. Queue/global/power reasons
are rejected because they must be known before pick.

An exact pending request suppresses the addressed I1 attempt or I2 output.
State clears only on request fire, after the two-entry retry queue can accept
the original member/reservation. If both retained stages are canceled
together, I2 has priority and I1 stays resident until its retry is accepted.
The queue can therefore retain both identities while the consumer is
backpressured; `empty` remains low until they drain. A stored retry also blocks
a new bridge join, preventing a malformed join retry from colliding with an
older lane retry.

`stageCanceled` reports accepted events with their reasons.
`stageCancelRejected` reports stage, occupancy, identity, and reason checks for
consumed stale or malformed requests. Recovery or exact load cancellation
wins a same-cycle race and does not generate a duplicate ordinary retry.

## Remaining integration work

- select and measure the final static class/domain/release-port map;
- connect real per-domain structural, sidedoor, reflow, latency, and
  result-bus owners to `stageCancel` in the static execution top;
- physical LSU hit/miss resolver wiring into the exact load-cancel ports;
- synchronous-macro latency variants and default-width timing evidence.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexP1I2LaneSpec
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
```

The focused UT covers backpressure stability, P/T source masks, optional PC,
whole-uop grant, denial-to-repick, partial-response rejection-to-repick,
exact cross-STID recovery, stale load-bypass rejection, RF-mask subtraction,
retained I2 bypass provenance, stale load-cancel rejection, simultaneous
I1/I2 exact resource cancellation with two retained retries, stale stage-token
rejection, and exact I2 poison.
The mechanism test additionally proves canonical IQ in-flight return and wires
a real PC buffer through the readyless read arbiter. It allocates and publishes
a PC-buffer token, reads the base, and proves I2 retains the reconstructed PC
and source value.
