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

The arbiter enumerates every bounded domain subset (at most eight domains) and
keeps only subsets whose complete P/T/U/PC demand fits. Feasible subsets are
ordered lexicographically: wrap-qualified
`{ridGeneration,ridSlot,memberIndex}` age within one STID, then advancing STID
round-robin order across STIDs. The winning subset therefore preserves the
oldest request and adds every lower-priority group that still fits. It never
partially grants a uop.

Each emitted port request retains domain, source index, STID/epoch, and the
complete generation-qualified source token. Responses are readyless and route
back through those exact port positions. A selected group remains granted when
one response is missing; its returned valid mask is incomplete, so
`OooIexP1I2Lane` rejects that attempt and repicks the canonical IQ row. Partial
operand data never enters I2.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexAtomicReadArbiter
bash tools/chisel/run_chisel_tests.sh --only OooParamsSpec
```

The focused UT covers an older three-P-source group excluding a younger
two-source peer from three ports, cross-STID round-robin turnover, mixed
P/T/U/PC mapping, complete response routing, a missing T response, and a
malformed operand class, and a mixed logical source group whose bypassed P
source is absent from the RF mask. The tested 2-domain/3P/2T/2U geometry emits a
2,325-line standalone SystemVerilog module.

## Remaining gaps

- Connect the exposed PC requests to `OooPcBuffer` in the canonical top.
- Add exact load-miss cancel/repick after retained bypass consumption.
- Replace the bounded subset comparator with a physically reviewed hierarchy if
  default-width timing requires it.

`OooIexIssueReadFabric` now connects P/T/U requests to the exact operand owners
and feeds every decision/response directly into the issue lanes.
