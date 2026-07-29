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

Each `OooIexIssueDomainConfig` contains one numerical `OooUopClass` and one
nonempty IQ bank mask. Elaboration rejects:

- a configuration count different from `iexIssueDomainCount`;
- an out-of-range class or bank bit;
- two domains that own the same class and any common bank.

Different classes may inspect the same physical bank number because class is
part of the physical IQ address. Two domains of the same class must use
disjoint banks. The validated configuration directly drives
`pickClasses/pickBankEnables` and the accepted class of each retained slot.

## Transfer and release arbitration

Each domain has independent I2 input and E1 output backpressure. The canonical
IQ and dispatch owners currently expose one exact terminal-release path, so a
round-robin arbiter selects at most one slot acceptance per cycle. A losing
domain sees `i2.ready=0`, retains its complete I2 transaction, and issues no
release. The winner's `i2.fire`, exact issue release, IQ row removal, dispatch
slot return, and E1 capture remain one ownership transaction.

This is a correctness closure, not a two-transfer throughput claim. Widening
IQ release and dispatch return is required before two ready I2 domains can
both enter E1 on one edge.

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

The fabric UT proves static ALU/BRU mapping, overlap rejection, fair
single-release serialization, retained loser ownership, and independent E1
backpressure. The integration test connects the real canonical IQ/read fabric
to this transfer fabric and proves one source-free ALU row reaches E1 exactly
when its IQ row and dispatch reservation are returned.

## Remaining gaps

- select the final ALU/BRU/AGU/STD/FSU/SYS/CMD domain and bank topology;
- widen exact IQ release and dispatch return to the required issue rate;
- connect class-specific E1 outputs to typed execution units;
- connect retained recovery apply and physical load-cancel producers in the
  canonical top;
- prove default-width timing, fairness bounds, and workload throughput.
