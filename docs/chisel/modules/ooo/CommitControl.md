# CommitControl

`CommitControl` is the OOO-side owner of ROB-authorized scalar-store
tokens. It participates in the same terminal commit transaction as grouped
ROB/BROB/PC deallocation and P/T/U retirement.

## Contract

- Input is one retained `OooRobCommitBatch`; physical STQ indexes are not part
  of the interface.
- Every active group must match the release head, RID generation, native BID,
  BROB generation, resident generation, completed-member mask, logical member
  range, and memory-order chain.
- Per-logical-uop `{before, after}` deltas classify non-memory, load, scalar
  store, and pair store ranges. Mixed load/store deltas, oversized ranges,
  broken group-to-group chains, and malformed member extents fail closed.
- One scalar store produces one beat token; one pair store produces two
  ordered beat tokens sharing the same logical first IDs and exact owner.
- The complete ROB batch is captured atomically only on the common commit
  fire and only when the multi-enqueue ring has worst-case credit. No token is
  published during the side-effect-free prepare phase.
- The token ring is independently backpressured by LSU. Continued LSU stall
  cannot partially retire the originating ROB batch, corrupt token order, or
  make a store non-flush before architectural commit.

The default 64-entry ring covers one worst-case four-group, eight-logical-uop,
two-beat commit batch. `storeCommitBufferEntries` is a power-of-two parameter
and must cover `retireGroupWidth * decodedUopWidth *
maxMemoryRequestsPerInstruction`.

## Verification

`OOORobCommitSpec` checks atomic scalar-plus-pair capture, output
stability under backpressure, exact beat ordering, broken cross-group chain
rejection, and modular full-serial wrap. The end-to-end store integration test
connects this owner to the physical STQ matcher and SCB path.
