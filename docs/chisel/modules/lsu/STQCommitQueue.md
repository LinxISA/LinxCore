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
- logical-store first LSID/first store ID, request count, and beat ordinal;
- generation-qualified `STQExactOwner`.

A physical index alone never authorizes issue. Duplicate live leases or a
duplicate `{STID, full store ID}` are rejected. Missing owner/store-ID fields
are malformed and fail closed.

## Per-STID issue frontier

For each resident logical group, the queue checks every same-STID peer with
modular first-store-ID comparison. The first full LSID must give the same
relative ordering. A scalar group contains beat 0 only; a pair group is
eligible only when exact beats 0 and 1 are both resident and every beat has a
revalidated ready bit. Therefore:

- a ready younger store cannot bypass an older stalled same-STID store;
- the complete oldest logical store issues atomically as one or two tokens;
- peer STIDs may bypass and use other issue lanes;
- an incomplete pair blocks younger same-STID stores but not peer STIDs;
- duplicate/missing beats, conflicting logical owner metadata, duplicate
  serial ownership, or first-SID/first-LSID order drift blocks the affected
  STID and raises `orderError` where the state is malformed.

Issue packing operates on logical groups, not individual queue slots. Pair
beats may be physically separated by a peer-STID token; once selected they
still occupy adjacent output lanes in beat order, so no half-pair can leave
the queue.

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

Dynamic tests cover same-STID blocking, incomplete-pair peer bypass, atomic
pair issue with physically interleaved tokens, modular wrap ordering,
malformed/duplicate rejection, exact widths, and explicit architectural abort.

## Remaining gaps

- Replace the physical-index mark-commit command with a ROB-originated exact
  commit token at the static top.
- Add MMIO classification and serializer routing.
- Define full-serial quiescence before half-range ambiguity.
- Close default geometry timing and workload gates.
