# OooIexE1TransferFabric

## Purpose

`OooIexE1TransferFabric` composes one retained
[`OooIexE1TransferSlot`](OooIexE1TransferSlot.md) per physical issue domain.
It turns the dynamic picker controls used by earlier focused tests into a
static elaboration contract without yet implementing opcode execution.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexE1TransferFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexE1TransferFabricSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueE1IntegrationSpec.scala`

## Static topology

Each `OooIexIssueDomainConfig` contains one numerical `OooUopClass`, one
nonempty IQ bank mask, and one static `releasePort`. Elaboration rejects:

- a configuration count different from `iexIssueDomainCount`;
- an out-of-range class or bank bit;
- an out-of-range or unowned release port;
- two domains that own the same class and any common bank.

Different classes may inspect the same physical bank number because class is
part of the physical IQ address. Two domains of the same class must use
disjoint banks. The validated configuration directly drives
`pickClasses/pickBankEnables`, the accepted class of each retained slot, and
the release-port ownership used by the transfer fabric.

## Transfer and release arbitration

Each domain has independent I2 input and E1 output backpressure. One
round-robin arbiter is instantiated per `iexReleaseWidth` port. Domains mapped
to the same port serialize fairly and a loser retains its complete I2
transaction without issuing an early release. Domains mapped to different
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

The fabric UT proves static ALU/BRU mapping, overlap/release-port rejection,
fair same-port serialization, retained loser ownership, independent E1
backpressure, and simultaneous ALU/BRU transfer on distinct ports. The
integration test connects the real canonical IQ/read fabric to this transfer
fabric and proves both rows can reach E1 on one edge exactly when both IQ rows
and dispatch reservations are returned.

## Remaining gaps

- select the final ALU/BRU/AGU/STD/FSU/SYS/CMD domain and bank topology;
- connect class-specific E1 outputs to typed execution units;
- connect retained recovery apply and physical load-cancel producers in the
  canonical top;
- prove default-width timing, fairness bounds, and workload throughput.
