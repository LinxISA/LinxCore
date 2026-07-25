# InstructionBuffer

## Status

`InstructionBuffer` is the production I-F4-to-D1 queue. The similarly named
`FrontendInstructionBuffer` is a verification-only packet FIFO and is excluded
from the production graph.

The new owner implements:

- one bank per configured active STID;
- atomic dense enqueue of one to four instruction rows;
- atomic dequeue of up to four rows for the selected STID;
- fixed 64-bit instruction storage plus explicit 2/4/6/8-byte length;
- one complete prediction record per row;
- independent ready/valid backpressure;
- same-cycle four-row dequeue and four-row replacement enqueue;
- per-STID active epoch and selective IFU inner flush;
- stale-epoch and malformed-group rejection diagnostics.

`threadCount` is the number of active hardware STID banks instantiated by the
top. Supported STIDs are the dense range `[0, threadCount)`. A request outside
that range fails closed rather than aliasing through truncated index bits.

## Enqueue Contract

I-F4 supplies `InstructionBufferEnqueueGroup`. `validMask` must be dense from
lane zero (`0001`, `0011`, `0111`, or `1111`) and all valid entries must carry
the same STID and epoch. Enqueue is atomic: no subset is accepted when capacity
is insufficient.

The bank admits only the currently active epoch. An inner flush changes the
selected bank epoch and clears only that STID. Other banks retain their rows.

## Dequeue Contract

D1 selects an STID and observes a stable `D1InstructionGroup` containing the
oldest one to four rows. The group remains stable while `ready` is low.
Acceptance removes all valid lanes atomically.

When a full bank simultaneously dequeues and enqueues four rows, the outgoing
rows are observed before the edge and the replacement rows become the new head
after the edge. This keeps the queue capable of one four-instruction group per
cycle.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only InstructionBuffer
```

The focused suite covers both the verification packet FIFO and this production
buffer without conflating their owner contracts.
The real Chisel simulations cover:

- four-row enqueue/dequeue with per-lane prediction preservation;
- full-bank four-row dequeue plus four-row enqueue in one cycle;
- independent STID banks;
- selective epoch flush;
- stale epoch rejection;
- sparse-mask rejection;
- elaboration of the architectural four-wide interface.

## Open Work

- The `LinxCoreIfu` composition already connects I-F4 through the final-prediction
  join into this queue and connects `deq` through `D1DecodeGroupGather`.
- `D1InstructionDecodeStage` is the production full-decode consumer contract
  for this boundary; top-level composition, four-lane rename, and dispatch
  remain open.
- Add fetch-fault payload before I-F2 fault rows are promoted.
- Prove sustained four-row traffic through D1/rename/dispatch, not only at the
  queue boundary.

`skill-evolve: no-update` — the multi-enqueue/four-dequeue and epoch rules are
already recorded in the IFU design and `linx-core` skill.
