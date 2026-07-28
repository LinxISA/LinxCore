# OooIexP1I2Lane

## Purpose

`OooIexP1I2Lane` is one reusable canonical read lane between the implemented
IQ/P1 bridge and an execution pipe. It makes the P1, I1, and I2 transaction explicit
without becoming a second owner of the IQ, P/T/U register files, PC buffer, or
recovery state.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexP1I2Lane.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexP1I2LaneSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexP1I2PcIntegrationSpec.scala`

## Stage contract

| Stage | Retained state | Advance or cancellation |
|---|---|---|
| P1 | no independent architectural state; accepts one selected joined IQ row | exact row shape enters I1; malformed shape produces a typed reject |
| I1 | full selected row, source tags/mask, optional parent PC token | one whole-uop read decision grants every requested source and PC port, or denies the attempt for exact repick |
| I2 | full row, returned source values, and reconstructed PC | retained under backpressure until a later non-cancellable execution handoff or exact recovery cancellation |

P1 requires exact member/group/BID/reservation identity, a valid primary
parent, registered-ready valid sources, and a valid selected PC token when PC
is requested. Invalid inputs are consumed as `p1Rejected`; they cannot wedge
the ready/valid boundary.

I1 presents one `OooIexI1ReadAttempt`. The future shared RF/PC arbiter must
return one explicit `readDecisionValid/readGrant` result for the whole uop.
Port denial, an incomplete readyless response, or a typed P1 rejection
produces `repick` with the original member and reservation. A
grant advances only if the valid-return mask exactly matches the requested
source mask and the optional PC return is valid. A partial readyless response
produces `readRejected`, exact repick, and never publishes a partial I2 transaction. The
physical IQ remains the canonical row owner, so denial or rejection does not
release its reservation.

I2 is the first retained data-bearing stage. Its row, operand values, and PC
remain stable while the consumer applies backpressure. This module does not
free the IQ row; the later E1 handoff must first become non-cancellable and
then drive the existing exact IEX release transaction.

## PC timing

The current `OooPcBuffer` is readyless and returns reconstructed
`base + byteOffset` data combinationally from one fixed replica port. I1
requests the token and I2 registers the returned full PC. This differs from
the ARM reference notes, which describe an I1 base-array read followed by I2
address reconstruction, but preserves the same visible I2 timing boundary.

A synchronous PC or RF macro must add an explicit retained response stage or
split I1 into request/response phases. It must not hide latency by holding an
unregistered request token or duplicating allocation/recovery ownership.

## Recovery

`OooResidencyRecoveryPlan` membership is checked independently for retained I1
and I2 rows. A matching recovery suppresses the affected output in the apply
cycle, clears the retained stage, and reports the exact member/reservation in
`recoveryCanceled(0)` for I1 or `(1)` for I2. An unrelated STID continues.

## Remaining integration work

- instantiate the implemented oldest-ready picker/bridge/lane composition
  across the frozen
  multi-domain class/bank-to-pipe topology and connect per-pipe P1 steering;
- canonical P/T/U RF implementations and one atomic multi-lane read arbiter;
- P/T/U bypass matching with tag generation and local-sequence identity;
- E1/W1 execution, wakeup, completion, and exact terminal IQ release;
- synchronous-macro latency variants and default-width timing evidence.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexP1I2LaneSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexP1I2PcIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1LaneSpec
```

The focused UT covers backpressure stability, P/T source masks, optional PC,
whole-uop grant, denial-to-repick, partial-response rejection-to-repick, and exact
cross-STID recovery. The IT allocates and publishes a real PC-buffer token,
reads it through the fixed readyless port, and proves that the full PC and
source value are retained at I2.
