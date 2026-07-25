# ISideLineContextQueue

## Purpose

`ISideLineContextQueue` is the ordered transaction boundary between I-F2 and
I-F3. It lets I-F0 keep multiple sequential cacheline requests in flight while
I-F2 translation/cache results return independently, without allowing I-F3 to
observe instruction bytes out of program order.

## Ownership

Each row retains:

- the exact I-F0 request identity and physical lookup PC;
- a separate semantic `startPc` for the first unconsumed byte;
- completion state and the exact I-F2 hit result.

Allocation is FIFO and atomic with the production prediction-join allocation
and I-F1 request. Completion searches all resident rows using PE, transaction,
STID, packet UID, fetch sequence, checkpoint, epoch, PC, and line VA. A younger
row may become complete first, but `out` exposes only the completed FIFO head.

## Cross-Line Prefix Carry

When I-F3 accepts an instruction whose end lies in the next cacheline, it
publishes `ISidePrefixCarry` with the exact successor identity, successor line
VA, and first unconsumed PC. The queue applies it in this order:

1. update the exact already-resident successor row;
2. apply it atomically to a matching allocation in the same cycle;
3. otherwise retain one carry until that exact successor allocates.

Only `startPc` changes. The original request PC remains intact so a delayed
I-F2 completion still matches the transaction that launched the lookup. A
matching carry suppresses head delivery in its application cycle.

## Flush

Typed IFU flush uses the shared identity/age contract, compacts survivors in
FIFO order, and discards a pending carry only when the successor identity is
killed. Physical ITLB/L1I contents are outside this queue and remain resident.

## Verification

`ISideLineContextQueueSpec` covers out-of-order completion/in-order delivery,
carry to a completed successor, retained carry before allocation, precise
flush compaction, stale completion drain, and one-entry elaboration. The
composed `LinxCoreIfuSpec` additionally proves consecutive cross-line prefix
consumption and a twenty-cycle full four-wide hot-cache D1 stream spanning
more cachelines than the context-window depth.
