# OooIexExecutionPipeline

## Purpose

`OooIexExecutionPipeline` is the production static composition from the OOO
S1 publication boundary through physical IQ residency, P1/I1/I2 operand
acquisition, retained E1 ownership, typed scalar execution, W1 bypass, and
atomic terminal publication.

The composition contains three new owners:

- `OooIexExecutionCluster` routes the formal fourteen E1 lanes to their typed
  execution owners;
- `OooIexTerminalFabric` provides two independent terminal publication lanes;
- `OooIexExecutionPipeline` closes feedback from execution into the canonical
  IQ and P/T/U operand-file owners.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexExecutionCluster.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexTerminalFabric.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexStoreStqFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexExecutionClusterSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexTerminalFabricSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexStoreStqFabricSpec.scala`

## Static execution topology

The wrapper accepts only `linx-scalar-control-v2`. Its twelve residency owners
expand to fourteen capability-disjoint picker/execution lanes:

| Physical owner | Picker/lane capability |
| --- | --- |
| ALU0 / ALU3 | simple ALU or store data |
| ALU1 / ALU4 | simple ALU |
| ALU2 / ALU5 | simple ALU, multicycle ALU, system, or pointer authentication |
| AGU0 / AGU1 | independent load-address and store-address pickers |
| AGU2 | load-address only |
| BRU0 / BRU1 | branch/control |
| FSU0 | floating/vector or engine command |

Six simple ALU pipes, two BRU pipes, three load-address AGUs, and three scalar
load trackers are internal. Store address, store data, multicycle ALU, system,
pointer authentication, floating/vector, and engine-command transactions are
explicit retained outputs. An unimplemented family is therefore backpressured
at its E1 owner; it is never silently consumed by an ALU fallback.

When `requireStoreReservation` is enabled, every split store also emits an
exact reservation request while retained in S1. Its S2 publication is blocked
until canonical STQ allocation fires. `OooIexStoreStqFabric` is the production
owner for that request and for the two STA/two STD retained outputs; the final
direct wrapper connection remains an explicit I0.11 follow-up.

Every route requires one generated recipe capability, the expected IQ class,
and the exact physical owner lane. Zero/multiple capabilities, wrong class,
wrong lane, or unsupported topology produce a typed `routeRejected` record and
leave the upstream E1 transaction resident.

## W1, W2, and terminal ownership

The three internal result families feed two terminal clusters. Source `i`
belongs to terminal `i % iexTerminalWidth`; each cluster round-robins within
its ALU, BRU, and load families before the existing three-family terminal
publisher arbitrates. Arbitration state advances only on `terminalFire`.

This is intentionally cluster-local rather than one global crossbar:

- one blocked cluster does not stop the peer cluster;
- one retained W-stage owner can appear at only one terminal lane;
- two independent results may publish in the same cycle;
- each lane retains the existing all-required-sinks rendezvous for P/T/U
  write, committed wakeup, BCTRL, trace, and exact ROB member completion.

ALU W1 and load-return W1 bypasses return to the issue fabric with exact
destination and producer provenance. Speculative load wakeup has no result
data and remains IQ-local. A miss/fault emits an exact load-cancel token, which
poisons matching P1/I1/I2/W-stage consumers and permits a new-generation
repick without modifying non-speculative RF readiness.

## Recovery and quiescence

One held `OooResidencyRecoveryPlan` is prepared by the canonical issue owner.
`recoveryFire` applies that same plan to issue/read/E1 and every internal
execution owner on one edge. External retained transactions remain qualified
by their complete member identity and must consume the same recovery plan in
their later owner.

`empty` is true only when both the issue/read/E1 pipeline and the execution
cluster are empty. It does not treat an empty IQ as backend quiescence while a
W1/W2/load-tracker or retained external transaction still owns work.

## Reference alignment

The physical shape follows the production scalar techniques in the reference
OOO/IEX design: six ALUs, three load-address pipes, two store-address pipes,
two BRUs, P1/I1/I2 operand staging, speculative E1 load wakeup, W1 result
bypass, and later terminal publication. Linx exact BID/BROB/RID/member,
P/T/U, CTU, and grouped-recovery semantics remain authoritative.

The scalar Chisel `OooIexAguPipeline` corresponds to the reference/model LDA
address-generation pipe. The model component named `AGUPipe` is used for a
different vector/MEM execution surface and is not the scalar topology source.

## Verification

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only OooIexTerminalFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionClusterSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipelineSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
```

The terminal UT covers dual publication, independent cluster backpressure, and
fire-qualified round-robin advancement. The cluster UT covers every explicit
external family, malformed capability rejection, concurrent ALU W1 bypass,
and two-lane W2 publication. The production-top structural IT elaborates all
eight classes, eight banks, fourteen picker/E1 lanes, and two terminal lanes,
then proves the canonical issue and execution owners are instantiated. It uses
one residency entry per bank; focused issue/recovery suites cover multi-entry
S1-to-E1 behavior, while cluster, terminal, and operand-file suites cover the
E1-to-W2 and atomic RF/completion path. A monolithic dynamic top simulation is
not a routine gate because the existing generated `OooIexIssue` dominates the
Verilator dependency graph; O8/O9 must close that structural compile cost.

## Remaining gaps

- Add the direct production wrapper between this pipeline and
  `OooIexStoreStqFabric`; do not add a pre-STQ address/data join owner.
- Connect exact ROB/SCB commit control, store-data banking, forwarding/replay,
  translation, L1D/coherence, and fault publication around the canonical STQ.
- Implement internal multicycle ALU/divide, system, pointer-authentication,
  floating/vector, and engine-command owners instead of leaving retained
  integration boundaries.
- Connect the three scalar-load memory ports to the canonical TLB/L1D/LIQ
  adapter, including sidedoor/reissue and physical arbitration loss.
- Replace cluster-local terminal modulo assignment only if synthesis shows a
  materially better fixed port map; do not introduce a global all-to-all
  result crossbar without measured timing evidence.
- Run default-width synthesis/timing, randomized sustained pressure, and
  CoreMark/Dhrystone promotion after O9 top integration.
