# OooIexIssueP1Fabric

## Purpose

`OooIexIssueP1Fabric` is the parameterized multi-domain canonical IQ-to-I2
composition. It instantiates one `OooIexIssue` and
`iexIssueDomainCount` private picker/bridge/P1-I1-I2 paths. Increasing issue
width therefore adds selection and pipeline residency without creating another
IQ scheduling row, ready scoreboard, or payload sidecar owner.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssueP1Fabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueP1FabricSpec.scala`

## Topology contract

Each domain receives one `pickClass` and one physical-bank enable mask. Two
domains may select different classes from the same bank number, or disjoint
banks of the same class. They may not overlap both class and bank. The IQ
asserts this condition every cycle, and exact retry additionally checks that
the returning reservation still belongs to that domain.

Each domain has its own retained oldest-ready token, sidecar join, retry merge,
atomic read attempt/decision, source/PC responses, and I2 output. Recovery
apply is shared because it is one architectural event, but each private I1/I2
lane evaluates exact member membership independently. Dispatch publication,
wakeup state, terminal IQ release, and PTag recycling remain single-owner
interfaces.

`lanesEmpty` reports only private P1/I1/I2 residency. `empty` additionally
requires no retained S1 row, no BoundS2/ResidentS3 IQ row, and no retained
recovery scan. It is the fabric quiescence signal; queue-empty or lane-empty
alone is insufficient.

The default parameter remains one domain so existing focused modules and tests
use domain-zero aliases. `OooIexIssueP1Lane` explicitly requires that default;
multi-domain integrations instantiate this fabric.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1Fabric
```

The two-domain IT installs ALU and PC-reading BRU children together, observes
simultaneous I1 attempts, grants ALU while denying BRU, and proves only the BRU
row returns to pickable state. It then repicks/grants BRU, drains both I2 lanes,
releases both canonical IQ rows, and checks aggregate `empty`. The same DUT is
then reused with two ALU domains on disjoint banks to prove that legal
same-class partition. A negative test configures overlapping ALU/bank
projections and requires the topology assertion to fire.

For the focused 2-bank × 4-entry geometry, generated RTL contains one
`OooIexIssue`, two `OooIexPickP1Bridge`, and two `OooIexP1I2Lane` instances.
The 12,129-line fabric has no schedule/payload storage reference; the single
multi-port IQ owner is 163,798 lines. These are structure counts, not physical
area or timing claims.

## Remaining gaps

- Freeze the default ALU/BRU/AGU/STD/FSU/SYS/CMD domain and bank map.
- Add class-specific control, memory-order, nonspeculative, and load-generation
  eligibility blockers before pick.
- Compose the implemented operand-read fabric with `OooPcBuffer` at the
  canonical top boundary.
- Connect bypass/load tracking and the non-cancellable I2-to-E1 release point.

`OooIexIssueReadFabric` is the direct-composition successor for integrations
that need real P/T/U data rather than manually driven decisions.
