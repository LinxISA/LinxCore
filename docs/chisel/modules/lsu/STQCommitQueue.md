# STQCommitQueue

## Purpose

`STQCommitQueue` is the canonical resident owner for committed store tokens
between `STQEntryBank` and the memory-side drain. Queue position is storage,
not program order. Per-STID full store ID defines the drain frontier, while
full LSID provides an independent ordering-consistency check.

Sources and tests:

- `chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala`
- `chisel/src/test/scala/linxcore/lsu/STQCommitQueueSpec.scala`

## Exact token

Every entry retains:

- physical STQ index plus lease generation;
- explicit STID and legacy BID observability;
- full LSID and valid full store ID;
- generation-qualified `STQExactOwner`.

A physical index alone never authorizes issue. Duplicate live leases or a
duplicate `{STID, full store ID}` are rejected. Missing owner/store-ID fields
are malformed and fail closed.

## Per-STID issue frontier

For each resident token, the queue checks every same-STID peer with modular
full-store-ID comparison. A token is issue-eligible only when no older token
exists in that STID. Full LSID must give the same relative ordering. Therefore:

- a ready younger store cannot bypass an older stalled same-STID store;
- one oldest token per STID may issue in a cycle;
- peer STIDs may bypass and use other issue lanes;
- duplicate serial ownership or SID/LSID order drift blocks the affected
  STID and raises `orderError`.

`readyMask` is indexed by CommitQ slot, not STQ index. `STQCommitDrain`
asserts a slot only after revalidating the token against the current canonical
STQ row and its lease generation.

## Mutation and recovery

Selected tokens are removed and survivors compacted. A new token appends after
same-cycle compaction; queue position has no age meaning.

`flushValid` is reserved for architectural reset/abort. Ordinary branch or
exception recovery must not clear committed/non-flush tokens. In the canonical
STQ-to-SCB composition it is tied low; module reset clears the queue.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueueSpec
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrainSpec
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPathSpec
bash tools/chisel/build_chisel.sh
```

Dynamic tests cover same-STID blocking, peer bypass, modular wrap ordering,
malformed/duplicate rejection, exact widths, and explicit architectural abort.

## Remaining gaps

- Replace the physical-index mark-commit command with a ROB-originated exact
  commit token at the static top.
- Add retained multi-fragment drain state and MMIO classification.
- Define full-serial quiescence before half-range ambiguity.
- Close default geometry timing and workload gates.
