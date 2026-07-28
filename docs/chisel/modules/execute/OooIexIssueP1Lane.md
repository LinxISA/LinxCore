# OooIexIssueP1Lane

## Purpose

`OooIexIssueP1Lane` is the first executable canonical IQ-to-I2 composition. It
instantiates one `OooIexIssue`, one `OooIexPickP1Bridge`, and one
`OooIexP1I2Lane` for a topology-neutral class/bank picker domain.
It is the domain-zero compatibility composition and requires
`iexIssueDomainCount=1`; parameterized parallel issue uses
[`OooIexIssueP1Fabric`](OooIexIssueP1Fabric.md).

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssueP1Lane.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueP1LaneSpec.scala`

## Transaction flow

1. S3 scheduling state selects and retains an exact oldest-ready token.
2. The bridge uses its class/bank/entry to read and prove the canonical joined
   row.
3. P1 accepts the generated PC controls and complete row; the IQ sets only its
   canonical `inFlight` bit.
4. I1 requests every P/T/U source and optional PC token atomically.
5. Denial or invalid response returns the exact member/reservation to the IQ;
   the row becomes pickable again. A complete grant retains all data at I2.

The lane cannot accept a replacement P1 on a denial/invalid-response edge.
Malformed bridge joins wait for lane capacity. The composition asserts that
bridge and lane retry outputs never collide, then merges them into the IQ's
single exact retry input.

The IQ's exact retained recovery apply is forwarded to the lane. Target I1/I2
residency is canceled on the same common apply as target IQ rows; peer STIDs
remain independent.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1LaneSpec
```

The IT publishes one PC-reading BRU row through real S1/S2/S3, observes the
generated parent token at I1, denies the read, proves exact retry clears
`inFlight`, observes the same row repick, then grants the PC read and checks the
retained I2 result. For its focused 2-bank × 4-entry geometry, the composition
is 8,244 SystemVerilog lines around separate 158,376-line IQ, 913-line bridge,
and 2,992-line lane modules. These are structure counts, not area/timing claims.
