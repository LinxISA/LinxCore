# BROB (Block Reorder Buffer) — stage spec

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`
- `rtl/LinxCore/docs/architecture/interfaces.md`

BROB is the lifetime tracker for block-structured execution.

BROB is partitioned per STID. Entries within one partition are indexed by
**BID**; shared interfaces identify a block as `(STID,BID)`.

This page specifies the full target behavior. Current Chisel and pyCircuit
BROB helpers implement reduced metadata tracking and do not by themselves prove
full allocation/retirement pointers, per-STID active state, integrated
recovery, or stale-response-safe BID reuse.

## 1) Architectural model

- All blocks are treated uniformly.
- Blocks may execute out-of-order.
- Blocks may resolve/complete out-of-order.
- Only the **oldest block within one STID** may retire from that ring.
- Any block may be flushed.
- Exceptions are precise per STID: a younger block may detect an exception
  early, but it is reported only when that STID's head reaches it. An
  unresolved head in one STID does not block an eligible head in another STID
  except for a separately specified shared retire-port limit.

## 2) DSE parameters

- `N_STID` (configured hardware-thread contexts, at least 1). Default: 1;
  multi-STID is a supported target configuration.
- `BROB_ENTRIES` (power-of-2, at least 2, per STID). Default: 256
- `BROB_ALLOC_PER_CYCLE` default: 1
- `BROB_COMPLETE_PER_CYCLE` default: 1
- `BROB_RETIRE_PER_CYCLE` default: 1
- `N_NONSCALAR_ENGINE` (non-scalar completion sources). Default promoted list:
  4 (`vec, tma, cube, tau`); deferred types such as TEPL/FIXP require an
  explicit owner before inclusion.

Derived:

- `STID_W = max(1, ceil(log2(N_STID)))`
- `BID_W = ceil(log2(BROB_ENTRIES))`
- `BROB_PTR_W = BID_W + 1` for an internal `{wrap, bid}` pointer realization

BID is the complete external row index within one STID's BROB. The pointer wrap
bit, allocation generation, occupancy, and optional DFX sequence are separate
state and must not be concatenated above BID. For each default 256-entry
partition, BID is 8 bits. STID is carried separately and is never packed above
BID.

## 3) Entry state encoding

Enum encoding (not onehot):

- `FREE`
- `ALLOC`
- `ISSUED`
- `COMPLETE`

Notes:
- No explicit `RETIRED` state; retirement advances head.
- No explicit `FLUSHED` state; flush clears entries and rolls back tail.
- `ISSUED` is set when BIQ actually **fires** to an engine, not when enqueued.

## 4) Flush semantics

The canonical flush request identifies `(flush_stid, flush_bid)`, with
`flush_bid` naming the youngest surviving live block in that STID. The selected
BROB ring resolves age from its current head, tail, occupancy, and internal
wrap state.

- Keep the live prefix from head through `flush_bid`.
- Kill the live ring interval from `successor(flush_bid)` through the
  pre-flush tail.
- Publish `brob_kill_stid` plus
  `brob_kill_mask[BROB_ENTRIES-1:0]`, or an exactly equivalent ring-qualified
  context, to every `(STID,BID)`-carrying queue.
- Roll tail back to `successor(flush_bid)` and update occupancy atomically.

Unsigned comparisons such as `bid > flush_bid` are illegal because BID wraps.
An interface using `first_killed_bid` is legal only when it also carries the
live ring context needed to distinguish the interval; the bare index is not an
age predicate.

## 5) Interfaces (valid/ready)

Signal naming MUST follow `producer_consumer_signal`.

### 5.1 Allocation (from decode/resource admission)

- `d3_brob_alloc_valid`
- `d3_brob_alloc_ready`
- `d3_brob_alloc_stid`
- `d3_brob_alloc_kind` (engine target/type)
- `d3_brob_alloc_body_tpc` (if applicable)
- `d3_brob_alloc_pred` (optional)

### 5.2 Issue (from BIQ)

- `biq_brob_issue_valid`
- `biq_brob_issue_ready`
- `biq_brob_issue_stid`
- `biq_brob_issue_bid`

### 5.3 Completion (from engines)

BROB has a dedicated scalar boundary-completion input that sets `scalar_done`
for the selected `(STID,BID)`. It is not counted as a non-scalar engine
response. A `BSTART` retire targets the old active block; a `BSTOP` retire
targets the current active block.

For a block whose type requires a non-scalar participant, one accepted response
from its selected engine owner sets `engine_done`. Completion is
`scalar_done && (!needs_engine || engine_done)`; no source may set both bits for
one event.

Design intent: BROB must losslessly service multiple non-scalar engines
attempting to complete in the same cycle. The collector either accepts the
required simultaneous burst or deasserts ready independently per lane while
each producer holds valid and payload stable.

Inputs (conceptual):

For each engine `e` in `[0..N_NONSCALAR_ENGINE-1]`:

- `engX_brob_complete_valid`
- `engX_brob_complete_stid`
- `engX_brob_complete_bid`
- `engX_brob_complete_exception`
- `engX_brob_complete_trapno`
- `engX_brob_complete_traparg0`
- `engX_brob_complete_bi`

Arbitration:

- BROB consumes up to `BROB_COMPLETE_PER_CYCLE` completions per cycle.
- If multiple completions contend, first select the oldest live completion
  within each STID by that ring's age from head. Then arbitrate fairly (RR by
  default) among eligible STIDs for each shared completion port. There is no
  cross-STID age comparison.
- `(complete_stid, complete_bid)` must name a live issued row. BROB rejects
  completion for a free or flushed slot.
- Per-STID BID reuse is held off until all possible responses for the old
  occupant have drained. A transport that cannot guarantee this must echo a
  separate command epoch/transaction ID; that field is not part of BID.

### 5.4 Retire output

- `brob_retire_valid`
- `brob_retire_ready`
- `brob_retire_stid`
- `brob_retire_bid`
- `brob_retire_exception`
- `brob_retire_trapno/traparg0/bi`

Retire rule:

1) Determine each STID's eligible head independently.
2) Arbitrate fairly among eligible STIDs when shared retire bandwidth is
   narrower than `N_STID`.
3) If the selected head has a pending exception, report it precisely and stop
   further retirement for that STID in the cycle.
4) Otherwise commit that block's side effects and advance only that STID's
   head.

### 5.5 Strong non-flush frontier

BROB publishes one strong non-flush prefix per STID. The prefix begins at that
STID's exact live commit head and contains only consecutive resident blocks
that can no longer be cancelled by branch recovery, trap, exception, or
interrupt. The interface carries `valid`, `head_bid`, `prefix_count`, and the
youngest safe `frontier_bid`; consumers must use the head and count as the age
proof and must not treat `frontier_bid` as an unsigned threshold.

- An empty ring or an unsafe head produces `valid=0` and `prefix_count=0`.
- A hole, stale slot identity, or unsafe row terminates the prefix. A younger
  safe row may not bypass it.
- An exception-bearing row is never strong-non-flush, even if its execution
  completion bits are set.
- The conservative implementation may require full block completion. A later
  implementation may mark a scalar non-memory block safe after every branch is
  resolved, or a tile load/store block safe after authoritative issue, but only
  through explicit BROB metadata producers.
- The frontier does not encode an ISA branch condition, exception level,
  exclusive-monitor state, or barrier opcode. Those architectural mechanisms
  are not imported from the reference design.

The store-commit owner retains a committed ROB store event until the matching
full block BID lies inside the selected STID's strong prefix. Only then may the
STQ row transition from speculative `Wait` state toward SCB. This retained
event is flushed with the same accepted recovery as the STQ/BROB state.

### 5.6 Store-range frontier

BROB owns one block store-range cursor and next store ID per STID. Allocation
metadata records every accepted store row for its exact full BID. When the
block's count producer declares that count certain, BROB may advance the range
frontier through that block and any consecutive known successors.

The count-certain event either latches the scalar stores accumulated for that
block or carries an explicit parameterized count from an authoritative
template/tile count producer. A decode hint alone is not authoritative.

Explicit count publication uses a retained valid/ready boundary. The producer
names an exact `(STID, full BID)` and holds its payload until the publication
owner accepts ownership. That owner admits only identities inside the
authoritative live head/count window, retains one event across range-sink
backpressure, and reports cancellation only when an accepted recovery kills
that suffix.

- A scalar boundary completion is not backpressurable. If it collides with an
  explicit event for a different block, scalar count closure publishes first
  and the explicit event remains retained.
- If scalar and explicit sources name the same block, the explicit count is
  authoritative and the scalar accumulated count is redundant for range
  closure. BROB scalar completion still proceeds independently.
- Repeating an already-certain explicit count is idempotent only when the exact
  value agrees. A conflicting value is an integration error and cannot mutate
  the frozen range.
- Count publication does not imply scalar completion, engine completion,
  exception freedom, or non-flush safety. Those remain separate BROB owners.

- The exact row at `store_range_cursor_bid` receives `start_store_id` even if
  its count is not yet certain. This makes the start stable while preventing
  younger bypass.
- A block advances the cursor only when its full BID is resident and its count
  is certain. `next_store_id += store_count` uses the parameterized store-ID
  width.
- Allocation, count publication, range assignment, retirement, and recovery
  all match exact `(STID, full BID)` identities. Same-slot stale events are
  ignored.
- Accepted suffix recovery clears killed range rows. If the first killed row
  already had a start ID, the owner restores both cursor and next ID from that
  row; otherwise an older unresolved cursor remains authoritative.
- Range assignment is not SCB commitment and does not make a block
  non-flushable. The strong non-flush prefix remains the separate authorization
  for speculative-to-committed store state.

## 6) Invariants (for unit tests)

- Head retires in order only.
- Tail advances on alloc, rolls back on flush.
- A `(STID,BID)` slot cannot be reused until no scalar row, engine command, memory side
  effect, cleanup record, or response can still name the prior occupant.
- Completion for a flushed `(STID,BID)` must be dropped.
- A younger block’s exception must not be reported before that STID's head
  reaches it.
- Every age comparison is within one STID and uses that BROB ring's context or
  generated kill mask; no consumer infers age from BID magnitude or compares
  blocks across STIDs as if they shared one ring.
- The strong non-flush prefix is contiguous from the exact commit head; no
  unsafe or nonresident row is included, including across BID rollover.
