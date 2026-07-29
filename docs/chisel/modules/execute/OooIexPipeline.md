# OooIexPipeline

## Purpose

`OooIexPipeline` is the canonical production composition from retained IEX S1
publication through P1, I1 operand acquisition, retained I2, and typed E1
execution-lane capture. It replaces the former test-only wiring between
`OooIexIssueReadFabric` and `OooIexE1TransferFabric`.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexPipeline.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueE1IntegrationSpec.scala`

## Ownership contract

The constructor accepts one `OooIexPhysicalProfile`. The same object creates
both `OooIexLinxIssueReadFabric` and `OooIexLinxE1TransferFabric`, so the
following properties cannot be configured independently:

- picker count and class/bank visibility;
- recipe capabilities and shared DIV/PAC/SYS participants;
- execution-lane identity;
- exact IQ and dispatch release ports.

The I2 channels and issue-release channels are private to the module. For each
winning lane, I2 advancement, exact canonical IQ deletion, dispatch-slot
return, and retained E1 capture are one handshake. The public boundary exposes
only S1 admission and typed E1 outputs; it cannot acknowledge an E1 transfer
without also returning the exact IQ and dispatch owners.

This matches the LinxCoreModel owner order in
`model/iex/iex.cpp::{Work,Xfer,SubReleaseIQEntryI2}` and the stage contract in
`model/iex/pipe/iex_pipe.h`: IQ selection feeds P1/I1/I2 pipe state and IQ
release is associated with the non-cancellable transfer, not with an unrelated
top-level pulse. Chisel retains stricter generation-qualified member and
reservation identity because copied software pointers are not hardware
ownership authority.

## Recovery and external owners

The existing retained issue-recovery scan remains the preparation authority.
`recoveryFire` is legal only while the exact plan is still valid and prepared.
On that edge the same plan reaches the E1 transfer slots, so killed S1/IQ/P1/
I1/I2 state and already-transferred E1 state observe one physical recovery
event. An assertion rejects an unprepared or withdrawn recovery fire.

`OooIexPipeline` does not duplicate other canonical owners:

- PC requests remain six readyless token reads to the `OooPcBuffer` already
  owned by `OooO3RenameCoordinator`;
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
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueE1IntegrationSpec
```

The integration test instantiates the formal twelve-residency-owner,
fourteen-picker, fourteen-execution-lane profile. A source-free ALU row and a
PC-reading BRU row select ALU0 and BRU0, transfer together through their
production release ports, return the exact dispatch reservations, disappear
from canonical IQ residency, remain retained under E1 backpressure, and make
aggregate quiescence true only after both typed E1 consumers fire.

The IT uses the minimum two-entry-per-bank geometry while preserving all
twelve owners, fourteen pickers, and fourteen lanes. Detailed retry, cancel,
shared-resource, and malformed-topology scenarios remain in the smaller child
fabric UTs because compiling the complete production topology is intentionally
a heavyweight integration gate.

## Remaining gaps

- Connect the six PC request tokens and responses directly to the existing O3
  coordinator in the canonical OOO/IEX composition.
- Connect every typed E1 lane to its implemented ALU/BRU/AGU/STD/load/FSU
  execution owner and return W1/W2/W3 bypass/writeback/cancel traffic.
- Connect `OooIexLoadUnit` speculative wakeup, E4 result, miss-pending, and
  exact load-cancel paths.
- Drive early issue policy and late stage cancellation from measured
  execution-lane, reflow, latency, side-door, and result-bus reservations.
- Run default-width synthesis/timing and natural workload activation after the
  typed execution and LSU owners replace the open E1 boundary.
