# OooIexAluPipeline

## Purpose

`OooIexAluPipeline` is the first typed execution owner after the exact
I2-to-E1 transfer. It computes a deliberately bounded scalar-integer subset,
retains the complete result in W1 and W2, and exposes W1 bypass data without
publishing any architectural or scheduling side effect.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexAluPipeline.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexAluPipelineSpec.scala`

The stage split follows the ownership shape in
`tools/LinxCoreModel/model/iex/pipe/alu_pipe.cpp::{move,runEx,runW1,runW2}`
and the E1/W1/W2 separation described in `Documents/a.txt`. Neither source is
used to import ARM flags, exceptions, register classes, or opcode semantics.

## Ownership and timing

E1 accepts only an exact ALU transaction whose generated recipe agrees with
its opcode, dispatch class, and IEX side-effect owner. The result appears in a
retained W1 row after the accept edge. W1 exposes a `Valid` bypass projection
and advances into retained W2 when W2 has capacity. W2 remains stable under
terminal backpressure. Both stages permit drain/refill without creating a
cycle in which the complete execute transaction has no owner.

W2 is a transaction boundary, not a writeback event. This module does not
write P/T/U data files, publish wakeup, complete the ROB member, redirect IFU,
or emit trace. A later atomic sink must accept W2 before any of those effects
occur.

Exact grouped-ROB recovery and exact speculative-load cancellation suppress
matching W1 and W2 rows independently. An unrelated STID or stale load
generation cannot kill the retained owner.

## Supported subset

The static whitelist currently contains:

- scalar immediate ADD/SUB/AND/OR/XOR and their word-result variants;
- compact ADD/ADDI/AND/OR/SUB;
- compact MOV immediate and MOV register.

Word results sign-extend bit 31. Destination identity is preserved for GPR,
T, and U namespaces. The whitelist is checked against the generated recipe
catalog so a supported opcode cannot silently change away from ALU/IEX
ownership.

Register-form `ADD/SUB/AND/OR/XOR` are intentionally still rejected. Their
Linx source-R sign/zero/negate/invert/shift modifiers are not yet normalized
into a typed D1 uop control. `ADDTPC` and `HL.ADDTPC` are also not ALU entries:
the generated catalog assigns them to BRU/BCTRL, so their value and control
effects must be implemented atomically in the branch path.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexAluPipelineSpec
```

The UT covers catalog alignment, immediate arithmetic, 32-bit sign extension,
compact operations, GPR/T destination preservation, W1 bypass timing, W2
backpressure stability, W1/W2 drain/refill, exact recovery, stale and exact
load-generation cancel, unsupported opcode rejection, and class mismatch.

## Remaining gaps

- typed D1 ALU function/source-modifier controls and register-form arithmetic;
- shifts, compares, bit manipulation, multiply/divide, and variable latency;
- physical W1 bypass fanout and latency-conflict reservation;
- atomic W2 P/T/U write, committed wakeup, ROB completion, and trace sink;
- connection to the static E1 transfer topology and default-width timing.
