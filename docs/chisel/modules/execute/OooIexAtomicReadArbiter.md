# OooIexAtomicReadArbiter

## Purpose

`OooIexAtomicReadArbiter` converts one retained I1 attempt per issue domain
into atomic P/T/U/PC read-port groups. It is a port allocator and response
crossbar, not a register-file data owner and not an IQ residency owner.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexAtomicReadArbiter.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexAtomicReadArbiterSpec.scala`

## Arbitration contract

The independently parameterized resources are:

- `iexPReadPorts`, default 6 global physical-P reads;
- `iexTReadPorts`, default 4 STID-local T reads;
- `iexUReadPorts`, default 4 STID-local U reads;
- the existing `pcReadPorts`, with at most one PC token per group.

Every RF-requested source must be P, T, or U and have an in-range physical
tag. `sourceMask` is the exact RF-needed subset left after lane-local bypass
selection, so it must be a subset of the logical source-valid vector rather
than equal to it. Member, BID, reservation, STID, and optional PC-token shape
must also be exact. A malformed attempt receives a
decision with `grant=0` and drives no physical request.

The arbiter computes a total priority rank for every exact request and walks
those ranks once. At each rank it accepts the complete group only when its
P/T/U/PC demand fits the remaining independent resources. This polynomial
greedy selection is exactly the lexicographic optimum: preserve the oldest
request, then add every lower-priority complete group that still fits. It
scales to the formal fourteen-picker issue profile without a `2^N` elaboration
network. Priority is wrap-qualified `{ridGeneration,ridSlot,memberIndex}` age
within one STID, then advancing STID round-robin order across STIDs. It never
partially grants a uop.

Each emitted port request retains domain, source index, STID/epoch, and the
complete generation-qualified source token. Responses are readyless and route
back through those exact port positions. A selected group remains granted when
one response is missing; its returned valid mask is incomplete, so
`OooIexP1I2Lane` rejects that attempt and repicks the canonical IQ row. Partial
operand data never enters I2.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexAtomicReadArbiterSpec
bash tools/chisel/run_chisel_tests.sh --only OooParamsSpec
```

The focused UT covers an older three-P-source group excluding a younger
two-source peer from three ports, cross-STID round-robin turnover, mixed
P/T/U/PC mapping, complete response routing, a missing T response, and a
malformed operand class, and a mixed logical source group whose bypassed P
source is absent from the RF mask. A second case presents fourteen same-STID
pickers to six P ports and proves that the six oldest complete groups win.

## Remaining gaps

- Connect the exposed PC requests to `OooPcBuffer` in the canonical top.
- Connect physical LSU resolve producers to the lane/IQ exact cancel path;
  the arbiter itself remains outside replay-state ownership.
- Synthesize and place the rank/greedy hierarchy at fourteen pickers; add a
  registered arbitration boundary only if measured timing requires it.

`OooIexIssueReadFabric` now connects P/T/U requests to the exact operand owners
and feeds every decision/response directly into the issue lanes.
