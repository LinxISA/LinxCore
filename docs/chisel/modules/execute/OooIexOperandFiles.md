# OooIexOperandFiles

## Purpose

`OooIexOperandFiles` is the canonical P/T/U operand-data boundary for the new
OOO-to-IEX path. It does not own IQ residency, read-port arbitration, bypass,
or terminal writeback arbitration.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexOperandFilesSpec.scala`

## Ownership contract

P data and non-speculative readiness remain in the existing
`ScalarGPRFile`. `OooIexOperandFiles` adds one allocation-identity sidecar per
physical PTag:

```text
{STID, epoch, PTag, PTag generation}
```

An initialization or rename clear installs that exact owner. Clear also makes
the underlying P row not-ready. A write request may change data and readiness
only when its complete owner key matches and its commit bit is accepted.
Write ready is a valid-independent owner preflight so an atomic terminal fork
cannot form a valid-to-ready cycle. Stale-generation reads return no readyless
response; stale writes, duplicate write targets, duplicate clears, and
clear/write collisions assert and perform no intended mutation.

T and U are independent STID-local arrays. Each row is qualified by:

```text
{STID, epoch, local tag, local sequence index, local sequence generation}
```

Allocation clear installs a new exact sequence as not-ready. Only an exact
committed T/U write installs data and sets non-speculative ready. T/U ready is
also independent of peer port valids; duplicate exact targets fail closed as a
whole vector. Reallocation changes the visible identity, so recovery need not
roll physical data back and an older local sequence cannot become visible
after tag reuse.

P, T, and U read-port counts and write-port counts are independent parameters.
The default formal geometry is 6P/4T/4U reads and 4P/4T/4U writes.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexOperandFiles
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueReadFabric
```

The focused UT covers P initialization, generation-changing clear,
request-versus-commit write behavior, exact T/U sequence allocation, stale
read suppression, assertion-backed stale P/T write rejection, and duplicate
exact P/T vector rejection without mutation. The
composition IT additionally proves that a missing canonical P response causes
exact IQ repick and that the matching generation later reaches I2 with the
expected data.

## Remaining gaps

- Connect the physical LSU resolver to the implemented IQ-local cancel/repick
  and retained bypass provenance owners; the RF remains non-speculative.
- Replace wide ready-mask observability with banked or replicated timing-safe
  reads at the default physical point.
- Connect rename allocation clear and `OooIexTerminalPublish` writes in the
  canonical top.
- Close physical macro and 6R/4W timing evidence.
