# OooRobCompletionBuffer

## Purpose

`OooRobCompletionBuffer` is the retained multi-producer boundary in front of
the current single-write grouped ROB. The production input count is
`iexTerminalWidth + 1`: every typed execution terminal lane plus the O3
fast-resolve owner. It replaces the former two-input arbiter that admitted at
most one completion per cycle and forced otherwise independent terminal
owners to retain completed work.

All visible inputs share one credit decision. If enough capacity exists, every
visible lane fires in the same cycle and is stored in lane order. The buffer
then drains one exact `OooRobMemberCompletion` per cycle into the grouped ROB.
This preserves arrival bandwidth without pretending that the present ROB has
multiple physical write ports.

## Recovery contract

Recovery prepare is mutation-free and fences enqueue/dequeue. For the target
STID, every buffered member must belong to the exact old ROB window. A stale or
malformed row rejects the common recovery. On the common apply edge, only
killed members are removed; survivors from the target STID and all rows from
other STIDs are compacted in original FIFO order.

This owner participates in `OooO3RenameCoordinator`'s global prepare/apply
rendezvous. Completion state therefore cannot escape suffix recovery or be
dropped by a local flush shortcut.

## Verification

```bash
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooRobCompletionBuffer
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The focused UT covers three-producer same-cycle admission and ordered drain,
cross-STID recovery preservation, partial-pivot compaction, and stale-row
rejection. The coordinator suite covers the complete common recovery join.

## Remaining gap

The buffer is the production-safe bridge for the current ROB macro boundary;
it is not a claim of final physical bandwidth. A later O8 timing packet may
bank the completion payload and accept multiple independent writes directly,
provided exact member identity, all-visible admission, and common recovery are
preserved.
