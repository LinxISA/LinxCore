# OooIexIssueReadFabric

## Purpose

`OooIexIssueReadFabric` is the first composition in which canonical IQ rows
reach I2 using real P/T/U data owners rather than manually supplied read
decisions and operand values.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssueReadFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueReadFabricSpec.scala`

## Composition contract

The module instantiates exactly one of each state-owning function:

```text
OooIexIssueP1Fabric
  -> OooIexSharedResourceArbiter
  -> OooIexAtomicReadArbiter
  -> OooIexOperandFiles
  -> retained I2 outputs
```

The issue fabric remains the only IQ scheduling, sidecar, ready-scoreboard,
in-flight, and retry owner. Every picker contributes one complete retained I1
attempt after exact W1/W2/W3 bypass selection. It also exports that row's
one-hot recipe capability independently from the compact RF request bundle.

Configured shared resources arbitrate before RF reads. The formal profile has
three independent resources shared by ALU2 and ALU5: DIV, PAC, and SYS. Two
requests for different resources may proceed together; two requests for the
same resource use work-conserving round-robin. The fairness base advances only
when the winning attempt also receives the atomic RF grant. A losing attempt
remains in its original I1 lane, keeps its canonical IQ `inFlight` claim, and
retries arbitration without delete/reinsert state. Invalid or non-one-hot
capability metadata is blocked and reported separately.

The surviving attempts enter the shared atomic RF arbiter.
Bypass hits retain complete provenance in I2 and are removed from RF demand;
an uncovered speculative source waits in I1. Selected P/T/U port requests go directly
to the operand-file owner, and readyless responses return through the exact
domain/source crossbar. The arbiter drives each lane's decision and data
inputs; this composition exposes no manual grant/data injection.

PC requests remain an explicit readyless boundary so the canonical
`OooPcBuffer` can be composed without creating another PC owner. Missing P/T/U
or PC responses preserve the grant but produce an incomplete response mask;
the lane rejects it and returns the exact member/reservation to the canonical
IQ for repick. Partial data cannot reach I2.

P initialization, rename clears, and terminal writes plus exact T/U
allocation/write ports are exposed for later top-level integration. Protocol
errors from every physical operand namespace remain observable.

Per-domain I1/I2 resource-cancel channels pass through unchanged to the
private lanes. They are intentionally separate from the atomic RF grant: a
known read-port conflict is expressed by the arbiter as an ordinary denial,
same-cycle shared-resource conflict retains I1 without a read decision, while
a structural/sidedoor/reflow/latency/result-bus conflict discovered by a later
physical owner uses the exact retained stage token.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueReadFabricSpec
```

The shared-resource UT proves ALU2/ALU5 same-resource exclusion, accepted-grant
round-robin rotation, independent DIV/PAC concurrency, ordinary-ALU bypass,
and malformed capability rejection. The IT publishes one ready ALU row whose
generation-qualified PTag has no
physical data. I1 receives a complete port grant but no P response, rejects the
attempt, and clears only that row's in-flight claim. After the exact P owner is
initialized, the row is repicked and reaches retained I2 with the expected
64-bit value. The test then drains I2, performs exact terminal IQ release, and
requires aggregate quiescence.

## Remaining gaps

- Compose the six PC ports with `OooPcBuffer` in the canonical top.
- Connect the implemented `OooIexLoadUnit` speculative wakeup, bypass, and
  miss/fault cancel outputs to the canonical issue ports in the static top.
- Connect execution-unit busy/latency reservations to early policy and static
  E1/reflow/result-bus owners to the exact stage-cancel channels.
