# OooIexTerminalPublish

## Purpose

`OooIexTerminalPublish` is the common terminal owner for typed ALU W2, BRU E2,
and scalar-load result transactions. It normalizes the three inputs and
releases exactly one owner only when every required state mutation can occur on
the same cycle.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexTerminalPublish.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexTerminalPublishSpec.scala`

The physical W2 topology is informed by
`tools/LinxCoreModel/model/iex/pipe/alu_pipe.cpp::ALUPipe::runW2` and
`tools/LinxCoreModel/model/iex/pipe/agu_pipe.cpp::AGUPipe::runW2`, plus the
retained W2 ownership described in `Documents/a.txt`. Linx generated recipes,
grouped-ROB identity, P/T/U rename identity, and ISA semantics remain
authoritative; model-specific buses are not copied into the Chisel contract.

## Atomic publication contract

The fair three-input arbiter accepts only exact source routes:

- ALU owner + ALU dispatch + IEX side-effect recipe;
- BRU owner + BRU dispatch + BCTRL side-effect recipe;
- AGU owner + AGU dispatch + LSU side-effect recipe, with an exact load token.

The complete ROB member, PE/STID, opcode/recipe route, destination vector, and
load producer must agree. P destinations use `{STID,epoch,PTag,generation}`;
T/U destinations use `{STID,epoch,localTag,localSequence}`. Duplicate physical
destinations reject the complete input without consuming it.

For every selected transaction, these endpoints form one required mask:

| Endpoint | Required when |
| --- | --- |
| P/T/U data-file write | destination is valid and the result is not a fault |
| committed wakeup | the matching data-file write is required |
| BCTRL update | BRU terminal carries a condition or target update |
| execution trace | always |
| exact ROB member completion, with optional fault/cause | always |

Each Decoupled output suppresses its `valid` until all peer endpoints are
ready, while excluding its own `ready` from that condition. Therefore one
blocked endpoint cannot consume or expose a partial terminal mutation. On
`terminalFire`, every required write/wakeup/BCTRL/trace/completion output also
fires; the selected ALU/BRU/load owner may then release its retained row.

A load hit writes data and converts any IQ-local speculative readiness into a
committed wakeup. A load fault writes no data and emits no committed wakeup,
but publishes its precise fault in both terminal trace and the exact ROB
completion on the same fire. The grouped ROB records a per-member fault bitmap
and cause vector; suffix recovery masks both and clears causes for killed
members.

## Operand-file composability repair

The composition IT exposed a valid-to-ready cycle in the prior T/U write
priority logic: one terminal write `valid` waited for a peer `ready`, while
that peer `ready` waited for the first write `valid`. `OooIexOperandFiles` now
defines P/T/U write readiness strictly as an exact-owner preflight. Duplicate
same-target write vectors are explicit fail-closed protocol errors and grant
no mutation. This makes the physical files composable with an atomic
multi-destination terminal fork.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexTerminalPublishSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexOperandFilesSpec
bash tools/chisel/run_chisel_tests.sh --only OooS1GroupedRobFaultSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAluPipelineSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexBruPipelineSpec
```

The terminal suite covers P write/wakeup backpressure, two-destination T/U
atomicity, BRU BCTRL coupling, precise load fault publication, duplicate
destination rejection, and real P/T/U data-file readback. The operand-file
suite covers stale owners and duplicate exact P/T/U write vectors. Adjacent
ALU and BRU suites preserve their retained terminal contracts. The focused ROB
suite proves exact runtime-fault retention at commit and killed-member fault
pruning during suffix recovery.

## Remaining gaps

- connect ALU W2, BRU E2, LoadUnit results, OperandFiles, IQ wakeup ports,
  grouped ROB, BCTRL, and trace in the canonical static IEX top;
- replace the single terminal arbiter with the measured multi-pipe publication
  topology and explicit write-port assignment;
- add redirect validation/recovery for redirecting BRU operations;
- join final architectural commit trace with the execution-terminal trace;
- close synthesis timing, randomized multi-pipe pressure, and workload gates.
