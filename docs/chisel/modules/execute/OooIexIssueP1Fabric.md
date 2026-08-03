# OooIexIssueP1Fabric

## Purpose

`OooIexIssueP1Fabric` is the parameterized multi-domain canonical IQ-to-I2
composition. It instantiates one `OooIexIssue` and
`iexIssueDomainCount` private picker/bridge/P1-I1-I2 paths. Increasing issue
width therefore adds selection and pipeline residency without creating another
IQ scheduling row, ready scoreboard, or payload sidecar owner.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssueP1Fabric.scala`
- `chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala`

## Topology contract

Each domain receives one physical-bank enable mask per class. A domain may
select across multiple classes; two domains may share a numerical bank only
when their class projections differ. They may never overlap the same
class/bank address. The IQ asserts this condition every cycle, and exact retry
additionally checks that the returning reservation still belongs to that
domain.

Each domain has its own retained oldest-ready token, sidecar join, retry merge,
atomic read attempt/decision, source/PC responses, and I2 output. Recovery
apply is shared because it is one architectural event, but each private I1/I2
lane evaluates exact member membership independently. Dispatch publication,
wakeup state, and terminal IQ release remain single-owner interfaces.

Each domain also exposes two backpressurable resource-cancel inputs, one for
I1 and one for I2. The private lane proves the exact stage/member/reservation
identity and retains canceled identities until they can be returned to the
single canonical IQ. A queued lane retry blocks a new bridge join, so the
bridge and lane retry sources remain mutually exclusive without a
ready-to-valid combinational loop.

`lanesEmpty` reports private P1/I1/I2 and retained retry residency. `empty`
additionally
requires no BoundS2/ResidentS3 IQ row and no retained
recovery scan. It is the fabric quiescence signal; queue-empty or lane-empty
alone is insufficient.

The domain count is derived from `CoreParams` through
`OooIexPhysicalProfile.fromCoreParams`; the canonical W4 topology contains the
two ALU paths, two LDA and two STA pickers, one BRU path, one
system/multicycle path, one floating/vector path, and one CMD path.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
```

The mechanism suite compares the main and bounded W2/W4/W6/W8 topology,
dynamically elaborates the bounded W4 fabric, proves parallel ALU claims and
isolation of a denied path, and exercises exact retry through the real read fabric.
Focused child suites retain malformed-topology,
stage-cancel, and recovery coverage.

## Remaining gaps

- Instantiate the parameter-derived picker topology in the canonical top; the
  static class/bank/capability contract is enforced at elaboration.
- Connect class-specific physical resource owners to early policy and exact
  I1/I2 stage-cancel inputs.
- Compose the implemented operand-read fabric with `OooPcBuffer` at the
  canonical top boundary.
- Connect bypass/load tracking and all static execution pipes.

`OooIexIssueReadFabric` is the direct-composition successor for integrations
that need real P/T/U data rather than manually driven decisions.
