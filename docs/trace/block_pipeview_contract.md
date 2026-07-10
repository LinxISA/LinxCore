# Block Pipeview Contract

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/interfaces.md`
- `rtl/LinxCore/docs/trace/linxtrace_v1.md`

## Required IDs

1. `uop_uid`: dynamic micro-op identity.
2. `block_uid`: dynamic block identity.
3. `stid`: per-thread BROB-ring identity.
4. `block_bid`: exactly the `BID_W`-bit BROB slot identity; debug uniqueness is
   carried only by `block_uid`.
5. `seq`: retire order index (text/commit stream).

This is the LinxTrace v2 target. LinxTrace v1 lacks STID and serializes a
legacy 64-bit `block_bid`; do not reinterpret its high bits in place. A schema
major change plus producer/viewer/comparator migration is required. If a
fixed-width v2 container remains 64 bits, bits above `BID_W` are zero/reserved,
not uniqueness or age.

## Raw event records

For `occ`, `commit`, and `blk_evt` records, include:

1. `core_id`
2. `stid`
3. `uop_uid` / `parent_uid` (when applicable)
4. `block_uid`
5. `block_bid`
6. stage/lane/pc/rob metadata

## Konata rendering rules

1. One row per block lifecycle (`BLOCK` row).
2. One row per dynamic uop.
3. Uop rows are grouped under block rows by
   `(core_id, stid, block_open_seq, row_kind, seq_or_first_cycle, uid)`.
4. Left pane:
   - block: concise block header
   - uop: `pc: asm` (or `GEN seq=<n> op=<mnemonic>` for generated uops)
5. Hover/detail keeps extended operand/memory/trap metadata.
