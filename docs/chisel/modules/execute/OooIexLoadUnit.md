# OooIexLoadUnit

## Purpose

`OooIexLoadUnit` is the current migration-era scalar-load owner after a typed
AGU request is accepted. It
allocates an exact load attempt, retains multiple outstanding requests, emits
speculative readiness, accepts out-of-order exact responses, retries misses,
and retains hit/fault results for a later atomic writeback/commit sink.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexLoadUnit.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexLoadUnitSpec.scala`

The lifecycle follows the load-tracking and replay intent in `Documents/a.txt`
and the request/response boundary in
`tools/LinxCoreModel/model/iex/pipe/agu_pipe.cpp::runE1Load`. Linx grouped ROB,
P/T/U tags, generated recipes, and memory semantics remain authoritative.

This is no longer the target production residency. I0.15c-b1 adds
`OooIexCanonicalLoadOwnership`, which already proves atomic canonical LIQ plus
OOO-sidecar allocation/rebind/terminal ownership without instantiating this
tracker. The execution cluster still instantiates this migration module until
c-b2 binds real LIQ launch/rebind to speculative wakeup/cancel policy. The old
tracker and its abstract memory ports must be deleted in that same cluster
cutover packet; they must never receive a load also admitted to canonical LIQ.

## Tracking ownership

`iexLoadTrackEntries` is a positive power-of-two parameter, default 16. Each
entry has one of four states:

```text
Free -> RequestPending -> AwaitingResponse -> ResultPending -> Free
                         ^          |
                         +-- Miss --+
```

AGU acceptance requires a free entry, an exact P/T/U destination, and no live
entry for the same producer ROB member. The entry allocates
`{producer RobMemberKey, generation}`; a numerical generation alone is never
an identity. A fair request arbiter presents pending entries to memory.

Every accepted memory attempt emits a `SpeculativeLoad` wakeup containing the
destination identity and exact load token. Consumers may become IQ-local
`specReady`, but I1 still requires a matching bypass before it can advance.

## Response and replay

Responses match only an `AwaitingResponse` entry with the complete load token:

- `Hit`: byte/half/word data is sign- or zero-extended from the D1 control;
  doubleword data is preserved. The result and W1 bypass remain stable until
  the terminal result is accepted.
- `Miss`: the old generation is canceled, the same tracking owner allocates a
  new generation, and the request returns to `RequestPending`. The next
  request fire emits a new speculative wakeup. Existing IQ/P1/I1/I2 consumers
  observe the cancel and repick through the canonical issue owner.
- `Fault`: the speculative generation is canceled and a precise fault result
  is retained without bypass.

Unknown, duplicate, stale, or post-recovery responses are consumed through a
typed rejection and cannot mutate another entry. Grouped-ROB recovery removes
only matching entries.

## Publication boundary

This unit does not directly write P/T/U data files, emit a committed wakeup,
complete ROB, or publish trace. `OooIexLoadResult` is accepted by the
implemented `OooIexTerminalPublish`, which writes data, publishes committed
readiness, traces the result/fault, and completes the ROB member on one common
terminal fire. Canonical static-top wiring remains open.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooParamsSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexLoadUnitSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1LaneSpec
```

The load UT covers generation allocation, request backpressure, speculative
wakeup, signed-byte extension, retained bypass/result, exact hit, miss cancel,
new-generation retry, malformed identity/access and duplicate-producer
rejection, stale-response rejection, precise fault, recovery, and post-recovery
response rejection. The issue/lane regression proves that the
same cancel contract poisons retained I2 and clears canonical IQ `inFlight`,
then a new generation wakes, bypasses, and repicks the consumer.

## Remaining gaps

- physical cache/TLB request and response adapter, ordering, alignment, and
  access-fault generation;
- store AGU/STD source projection, join, store queue, forwarding, and commit;
- canonical connection to `OooIexTerminalPublish`, grouped ROB, IQ, and trace;
- multi-port load response/bypass bandwidth and latency reservation;
- canonical static-top connection and end-to-end AGU/LSU/IQ replay IT;
- synthesis timing, randomized pressure, and workload evidence.
