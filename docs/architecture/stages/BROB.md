# BROB (Block Reorder Buffer) — stage spec

BROB is the lifetime tracker for block-structured execution.

BROB entries are indexed by **BID**.

## 1) Architectural model

- All blocks are treated uniformly.
- Blocks may execute out-of-order.
- Blocks may resolve/complete out-of-order.
- Only the **oldest** block may retire.
- Any block may be flushed.
- Exceptions are precise: a younger block may detect an exception early, but the exception is reported only when the oldest block retires.

## 2) DSE parameters

- `BROB_ENTRIES` (power-of-2). Default: 128
- `BROB_ALLOC_PER_CYCLE` default: 1
- `BROB_COMPLETE_PER_CYCLE` default: 1
- `BROB_RETIRE_PER_CYCLE` default: 1
- `N_ENGINE` (engine sources for completion). Default: 5
  - Default engine list: `scalar, vec, tma, cube, tau`

Derived:

- `BID_W = log2(BROB_ENTRIES)`

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

Flush event carries **`first_killed_bid`**.

- After flush, blocks with `bid >= first_killed_bid` are killed.
- Tail is rolled back precisely:
  - `tail := first_killed_bid`

## 5) Interfaces (valid/ready)

Signal naming MUST follow `producer_consumer_signal`.

### 5.1 Allocation (from D3)

- `d3_brob_alloc_valid`
- `d3_brob_alloc_ready`
- `d3_brob_alloc_kind` (engine target/type)
- `d3_brob_alloc_body_tpc` (if applicable)
- `d3_brob_alloc_pred` (optional)

### 5.2 Issue (from BIQ)

- `biq_brob_issue_valid`
- `biq_brob_issue_ready`
- `biq_brob_issue_bid`

### 5.3 Completion (from engines)

Design intent: BROB must service multiple engines attempting to complete in the same cycle.

- Engines should not be backpressured; a completion collector/skid FIFO is expected in front of BROB.

Inputs (conceptual):

For each engine `e` in `[0..N_ENGINE-1]`:

- `engX_brob_complete_valid`
- `engX_brob_complete_bid`
- `engX_brob_complete_exception`
- `engX_brob_complete_trapno`
- `engX_brob_complete_traparg0`
- `engX_brob_complete_bi`

Arbitration:

- BROB consumes up to `BROB_COMPLETE_PER_CYCLE` completions per cycle.
- If multiple completions contend, select the **oldest BID first**.

### 5.4 Retire output

- `brob_retire_valid`
- `brob_retire_ready`
- `brob_retire_bid`
- `brob_retire_exception`
- `brob_retire_trapno/traparg0/bi`

Retire rule:

1) If head has pending exception: raise/report trap and stop retiring further blocks.
2) Else commit block side effects.
3) Advance head.

## 6) Invariants (for unit tests)

- Head retires in order only.
- Tail advances on alloc, rolls back on flush.
- A BID cannot be reused until it is past head (ring discipline).
- Completion for a flushed BID must be dropped.
- A younger block’s exception must not be reported before the head reaches it.
