# STQCommitDrain

## Purpose

`STQCommitDrain` joins exact committed tokens with the canonical STQ row,
applies downstream segment credit, and shapes one or two 64-byte-line
fragments. It retains an immutable request snapshot under backpressure, but
does not create a second mutable STQ-row owner.

Sources and tests:

- `chisel/src/main/scala/linxcore/lsu/STQCommitDrain.scala`
- `chisel/src/test/scala/linxcore/lsu/STQCommitDrainSpec.scala`

## Enqueue and revalidation

The accepted WAIT-to-Commit transition may enqueue in the same cycle in which
the STQ row still reads WAIT; after that edge, issue requires the row to be
Commit. The snapshot includes exact owner, physical lease generation, full
LSID, full store ID, STID, and BID.

Before exposing readiness to `STQCommitQueue`, each queue slot is revalidated
against the current STQ row:

- row is valid, Commit, `ST_ALL`, address/data ready;
- lease index/generation still identify the same allocation;
- owner, STID, BID, full LSID, and full store ID match exactly.

A stale token remains resident, cannot issue or free the row, and raises
`queuedIdentityError`.

## Fragment shaping and free ownership

A store that stays within one 64-byte line emits one request with `last=1` and
`ownsStqRow=1`. A crossing store emits two requests:

- segment 0 carries the first-line bytes with `last=0`, `ownsStqRow=0`;
- segment 1 carries the remainder with `last=1`, `ownsStqRow=1`.

CommitQ launch is independent of downstream segment credit. The selected exact
tokens and shaped fragments move into one retained batch; `valid`, address,
data, size, identity, and ownership remain stable until all required segment
credits are present. In `STQSCBCommitPath`, only an accepted request with
`ownsStqRow`/`last` can generate the canonical STQ free mask. The drain's
acceptance-derived free mask is diagnostic and is not wired to STQ mutation.

`retainedBatchValid`, `retainedBatchAccepted`, and
`retainedIdentityError` expose this owner. The owner revalidates every retained
token against the canonical Commit row while stalled; an unexpected row/lease
change suppresses requests and fails closed. Deasserting `issueEnable` also
suppresses external request valid without clearing or changing the retained
payload, so a paused drain cannot be consumed by SCB accidentally.

## Ordering and recovery

The child CommitQ enforces the per-STID oldest-token frontier. A stalled older
store blocks younger same-STID rows, while peer STIDs may drain. Ordinary STQ
recovery prunes speculative WAIT rows but preserves committed tokens; only
module reset or an explicit architectural abort clears the CommitQ.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrainSpec
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueueSpec
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPathSpec
bash tools/chisel/build_chisel.sh
```

Dynamic tests cover stale lease rejection, multi-cycle fragment stability,
accepted-batch release, and last-fragment-only ownership.
Reference tests cover single/split shaping, same-STID blocking, peer bypass,
downstream gating, and independent STQ/ROB/full-serial widths.

## Remaining gaps

- Group pair-store/cross-line fragments under one logical completion token.
- Route MMIO stores to the serializer instead of SCB.
- Replace compatibility `commitFreeMask` observability after static-top
  migration.
