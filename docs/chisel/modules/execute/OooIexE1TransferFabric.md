# OooIexE1TransferFabric

## Purpose

`OooIexE1TransferFabric` composes one retained
[`OooIexE1TransferSlot`](OooIexE1TransferSlot.md) per picker/execution lane.
It turns the dynamic picker controls used by earlier focused tests into a
static elaboration contract without yet implementing opcode execution.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexE1TransferFabric.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexPhysicalProfile.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexE1TransferFabricSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexPhysicalProfileSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueE1IntegrationSpec.scala`

## Static topology

Each `OooIexIssueDomainConfig` contains one bank mask per numerical
`OooUopClass`, one capability predicate, and one static `releasePort`. Despite
the retained type name, this is a picker-function configuration: canonical
residency is defined separately by `OooIexPhysicalProfile`. Elaboration
rejects:

- a configuration count different from `iexIssueDomainCount`;
- a missing class mask, an empty domain, or an out-of-range bank bit;
- an out-of-range or unowned release port;
- two pickers that observe the same class/bank and share any capability.

Different classes may use the same numerical bank because class is part of the
physical IQ address. Two capability-disjoint pickers may also observe the same
physical class/bank. The validated matrix directly drives
`pickBankEnables`, each retained slot's accepted class/bank/capability
projection, and release-port ownership. Canonical IQ storage is never copied.

[`OooIexPhysicalProfile`](OooIexPhysicalProfile.md) freezes the first complete
profile: six ALU, three AGU, two BRU, and one external FSU residency owner,
expanded into fourteen picker/execution lanes. AGU0/1 each expose an LDA and
STA picker; AGU2 exposes LDA only. STD shares ALU0/3 residency, SYS shares
ALU2/5, and FSU/CMD share the external FSU owner. Boundary rows remain
fast-resolved and own no IQ bank.

`OooIexLinxE1TransferFabric` is the production specialization. It accepts one
formal profile and derives every transfer slot, capability predicate, bank
projection, and release owner from that profile. Paired with
`OooIexLinxIssueReadFabric`, neither half can silently substitute an ad-hoc
picker topology.

## Transfer and release arbitration

Each picker lane has independent I2 input and E1 output backpressure. One
round-robin arbiter is instantiated per `iexReleaseWidth` port. Pickers mapped
to the same port serialize fairly and a loser retains its complete I2
transaction without issuing an early release. Pickers mapped to different
ports may transfer on the same edge. For every winner, `i2.fire`, exact issue
release, IQ row removal, dispatch-slot return, and E1 capture remain one
ownership transaction.

The IQ and dispatch owners independently reject duplicate physical targets
across valid release lanes. Thus a bad static/dynamic composition cannot
double-free a row even when release throughput is widened.

## Canonical in-flight authority

The pipeline row is captured on the canonical pick/claim edge and may contain
the pre-claim value of its copied schedule `inFlight` bit. The transfer slot
does not treat that snapshot as authority. The canonical IQ release sink
checks its live resident row and returns ready only for the exact in-flight
member and reservation. Coupling that ready/fire to E1 acceptance prevents a
second issued-state owner.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueE1IntegrationSpec
```

The fabric UT proves static ALU/BRU mapping, multi-class ALU/STD admission,
simultaneous capability-disjoint AGU LDA/STA transfer, wrong-bank rejection,
unsafe overlap/release-port rejection,
fair same-port serialization, retained loser ownership, independent E1
backpressure, and simultaneous ALU/BRU transfer on distinct ports. The
integration test connects the real canonical IQ/read fabric to this transfer
fabric using the same static domain declarations and proves both rows can
reach E1 on one edge exactly when both IQ rows and dispatch reservations are
returned. The formal-profile UT elaborates the production specialization.

## Remaining gaps

- connect shared DIV/PAC/SYS busy/latency state to issue policy and exact
  stage-cancel; same-cycle ALU2/ALU5 arbitration is owned before RF read;
- connect picker-specific E1 outputs to typed execution units;
- connect retained recovery apply and physical load-cancel producers in the
  canonical top;
- prove default-width timing, fairness bounds, and workload throughput.
