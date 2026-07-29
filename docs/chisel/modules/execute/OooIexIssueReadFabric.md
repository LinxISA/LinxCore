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
  -> OooIexAtomicReadArbiter
  -> OooIexOperandFiles
  -> retained I2 outputs
```

The issue fabric remains the only IQ scheduling, sidecar, ready-scoreboard,
in-flight, and retry owner. Every domain contributes one complete retained I1
attempt to the shared atomic arbiter after exact W1/W2/W3 bypass selection.
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

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueReadFabric
```

The IT publishes one ready ALU row whose generation-qualified PTag has no
physical data. I1 receives a complete port grant but no P response, rejects the
attempt, and clears only that row's in-flight claim. After the exact P owner is
initialized, the row is repicked and reaches retained I2 with the expected
64-bit value. The test then drains I2, performs exact terminal IQ release, and
requires aggregate quiescence.

## Remaining gaps

- Compose the six PC ports with `OooPcBuffer` in the canonical top.
- Add exact load-miss cancel, wakeup poison, and repick across IQ/I1/I2.
- Freeze the default class/bank/pipe map and class-specific eligibility rules.
- Add the non-cancellable I2-to-E1 owner transfer and W1 terminal network.
