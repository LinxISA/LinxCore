# OooIexFastResultPort

## Purpose

`OooIexFastResultPort` connects O3 fast-resolve result producers to the
canonical IEX P operand file and issue wakeup network. Immediate and control
value producers no longer terminate at externally acknowledged placeholder
ports.

The production profile has five P write ports:

- four ports belong to the two ordinary terminal lanes with two destinations
  per lane;
- one independent port belongs to fast resolve.

The existing eighth wakeup port is likewise reserved for the fast result;
four committed terminal wakeups and three speculative load wakeups retain
their independent ports. Fast traffic therefore cannot steal ordinary W2
publication bandwidth.

## Atomicity

Fast writeback and wakeup are an atomic Decoupled pair. Their STID, epoch,
PTag, generation, and P operand class must agree. Both branches receive one
common readiness derived from the dedicated PRF owner preflight and fire only
together. Acceptance implies the exact generation-qualified PRF write and the
issue wakeup are visible in the same cycle; malformed pairs assert and do not
mutate state.

## Verification

```bash
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooIexFastResultPort
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
```

The UT proves owner-preflight blocking, exact payload conversion, same-cycle
write+wakeup acceptance, and fail-closed identity mismatch. The structural IT
elaborates the port inside the complete fourteen-lane execution/store owner.
