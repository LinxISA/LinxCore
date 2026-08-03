# OooIexPipeline

## Purpose

`OooIexPipeline` is the canonical composition from classed OOO dispatch
through IQ residency, P1 selection, I1 operand acquisition, retained I2, and
typed E1 execution-lane capture. It privately composes
`OooIexIssueReadFabric` with `OooIexE1TransferFabric`.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexPipeline.scala`
- `chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala`

## Ownership contract

The constructor accepts `CoreParams` and derives one
`OooIexPhysicalProfile`. The same object configures both
`OooIexIssueReadFabric` and `OooIexLinxE1TransferFabric`, so the
following properties cannot be configured independently:

- picker count and class/bank visibility;
- recipe capabilities and shared DIV/PAC/SYS participants;
- execution-lane identity;
- exact IQ release ports.

The I2 channels and issue-release channels are private to the module. For each
winning lane, I2 advancement, exact canonical IQ deletion, and retained E1
capture are one handshake. The public boundary exposes classed dispatch and
typed E1 outputs; it cannot acknowledge an E1 transfer without also releasing
the exact IQ owner.

This matches the LinxCoreModel owner order in
`model/iex/iex.cpp::{Work,Xfer,SubReleaseIQEntryI2}` and the stage contract in
`model/iex/pipe/iex_pipe.h`: IQ selection feeds P1/I1/I2 pipe state and IQ
release is associated with the non-cancellable transfer, not with an unrelated
top-level pulse. Chisel retains stricter generation-qualified member and
reservation identity because copied software pointers are not hardware
ownership authority.

## Recovery and external owners

The retained issue-recovery scan remains the preparation authority. The
canonical `RecoveryTargetIO` prepare handshake is side-effect free; only an
accepted Apply or Abort phase can mutate retained state. The accepted exact
plan reaches the E1 transfer slots on the same event, so killed IQ/P1/I1/I2
state and already-transferred E1 state observe one recovery decision.

`OooIexPipeline` does not duplicate other canonical owners:

- PC requests remain readyless typed outputs at this Task-13 mechanism
  boundary; public `OOO` does not claim a private execution PC-buffer owner;
- P/T/U initialization, allocation clears, and terminal writes remain public
  connections to rename/writeback owners;
- load cancellation, bypass, issue policy, and exact I1/I2 late cancellation
  remain typed external physical-resource inputs;
- typed E1 consumers remain the ALU/BRU/AGU/STD/FSU execution owners.

Quiescence is the conjunction of issue/read emptiness and all retained E1
transfer slots being empty. Queue-only emptiness is not pipeline quiescence.

## Verification

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipelineSpec
```

The mechanism suite proves matching main/bounded W2/W4/W6/W8 configurations,
dynamically elaborates bounded W4/W2 mechanisms, proves parallel ALU claims and
denial isolation, and connects real operand files plus a real PC-buffer read path.
The execution-pipeline suite covers typed lane routing and
terminal behavior. Detailed retry, cancel, shared-resource, and malformed
topology scenarios remain in focused child-fabric tests.

## Remaining gaps

- Connect the PC request tokens and responses directly to `OooPcBuffer` in the
  canonical OOO/IEX composition.
- Complete `OooIexExecutionPipeline` as the canonical typed E1 consumer. It
  connects implemented ALU/BRU/scalar-load lanes and explicit retained
  unfinished-family boundaries while returning bypass, wakeup, and exact
  load-cancel traffic.
- Close the retained store/multicycle/system/PAuth/FSU/CMD boundaries with
  their canonical owners; do not replace them with permissive sinks.
- Drive early issue policy and late stage cancellation from measured
  execution-lane, reflow, latency, side-door, and result-bus reservations.
- Run default-width synthesis/timing and natural workload activation after the
  typed execution and LSU owners replace the open E1 boundary.
