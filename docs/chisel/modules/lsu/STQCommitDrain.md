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
LSID/store ID, logical first IDs/request count/beat, STID, and BID.

Before exposing readiness to `STQCommitQueue`, each queue slot is revalidated
against the current STQ row:

- row is valid, Commit, `ST_ALL`, address/data ready;
- lease index/generation still identify the same allocation;
- owner, STID, BID, full LSID/store ID, and every logical-group field match
  exactly.

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

## Logical completion

A scalar store has one retained beat and at most two retained fragments. A
pair store has two exact beats and may therefore expose four fragments. The
CommitQ cannot launch the pair until both beats are present and ready, and the
drain assertion rechecks that the retained batch contains the declared beat
count.

On atomic retained-batch acceptance, `logicalCompletions` emits one record per
distinct logical owner, not per beat or fragment. The record carries the exact
owner, STID, first full LSID/store ID, and request count. Thus a pair with four
accepted fragments frees two physical STQ rows but produces exactly one
logical drain completion. This record is not a lower-level WriteResp or final
architectural memory-completion acknowledgement. Peer scalar stores may still
produce separate completion records in the same accepted batch.

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
accepted-batch release, last-fragment-only ownership, and a two-beat pair held
as four stable fragments before one logical completion and two-row free.
Reference tests cover single/split shaping, same-STID blocking, peer bypass,
downstream gating, and independent STQ/ROB/full-serial widths.

## Remaining gaps

- Route MMIO stores to the serializer instead of SCB.
- Replace compatibility `commitFreeMask` observability after static-top
  migration.
