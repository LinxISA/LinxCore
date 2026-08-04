# Task 17 review fixes — round 2

## Findings addressed

`OooIexTerminalPublish` previously included trace readiness in the atomic
terminal rendezvous. The IEX boundary also derived its public terminal PWrite
observation from trace-source fire and fed observation capacity backward into
that source's ready signal. A stalled or full observation path could therefore
delay architectural completion.

## RED evidence

The focused `IEXTerminalTraceIndependenceSpec` held `trace.ready` low while
every required architectural sink was ready. Against the round-1 base it
failed at the first architectural check: `alu.ready` was zero instead of one.
The focused behavioral RED used 113,563,306 artifact bytes across 110 files,
below its 200,000,000-byte cap.

After the first independence repair, the exact activation assertions exposed
a second behavioral RED: `rf=1 resolve=2 commit=1 accepted=2 dropped=1`. Two
architectural terminal lanes completed together, but a combinational
single-output observation selector retained only one PWrite observation.

An earlier attempt to use the full legacy terminal suite was stopped after its
one-simulator-per-case shape exceeded the compact resource target. A separate
setup attempt passed an executable instead of the firtool directory to
`CHISEL_FIRTOOL_PATH`. Neither attempt is classified as behavioral evidence.

## Architectural acceptance

- `OooIexTerminalPublish` now defines `architecturalReady` solely from the
  required ROB resolve, physical-file write, wakeup, recovery-event, and BCTRL
  endpoints. Trace ready, trace queue state, DTU state, and export state do not
  participate.
- One `architecturalFire` releases the selected terminal owner and atomically
  drives required architectural completion, PWrite, wakeup, recovery, and
  BCTRL outputs. The terminal trace valid pulse is generated from that event
  as a best-effort observation; trace acceptance is not asserted as part of
  architectural fire.
- A private typed `architecturalAccepted` observation carries the exact
  accepted request through TerminalFabric, ExecutionCluster, and
  ExecutionPipeline. IEX no longer reconstructs terminal PWrite observation
  from trace fire.
- Trace-source ready is hard true at the IEX observation boundary. Local trace
  queues may miss a pulse when full, but cannot stall architectural acceptance;
  DTU remains the sole external loss/accounting owner.

`IEXIO.terminalPWrite` is explicitly a `Valid[IEXTerminalPWriteObservation]`,
not an RF mutation port or state owner. The real architectural PWrite ports
remain the Decoupled outputs of `OooIexTerminalPublish` connected to the
operand files. Per-lane retained observations serialize simultaneous terminal
events without feeding capacity into architectural readiness. Consume and
same-lane replacement are atomic, and an assertion forbids silent observation
overflow.

## GREEN evidence

- `IEXTerminalTraceIndependenceSpec` — PASS, 1/1. With trace ready low, one ALU
  owner acceptance, exact PWrite data, wakeup, ROB resolve, and terminal fire
  occur once; the following three cycles contain no duplicate. Final artifact
  size was 115,360,634 bytes across 110 files under the 200,000,000-byte cap;
  elapsed time was 13.180 seconds and peak process-tree RSS was 2,736,242,688
  bytes.
- `DTUActivationTraceSpec` — PASS, 1/1. With the external DTU export stalled,
  the retained packet remains stable and final counters are exact:
  `rf=2 resolve=2 commit=1 accepted=2 dropped=1`. Final artifact size was
  586,772,137 bytes across 564 files under the 900,000,000-byte cap; elapsed
  time was 147.043 seconds and peak process-tree RSS was 7,541,555,200 bytes.
- `DTUSpec` — PASS, 4/4. Artifact size was 167,214,581 bytes across 198
  files under the 300,000,000-byte cap; elapsed time was 14.575 seconds and
  peak process-tree RSS was 1,667,006,464 bytes.
- The closed-owner manifest checker passed with 27 closed owners, 24
  classified emitters, 6 declared adapters, and all L1/L2/L3 roles mapped.
  Its 46-case unit suite, the top-interface manifest check, and
  `git diff --check` also passed.

Both final suites used the repository wrapper, one job, a clean build
directory, and the native arm64 firtool override. `OOORobCommitSpec` was not
run.
