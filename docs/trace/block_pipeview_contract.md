# Block Pipeview Contract

## Required IDs

1. `uop_uid`: dynamic micro-op identity.
2. `block_uid`: dynamic block identity.
3. `block_bid`: block/BROB identity (`low bits = BROB slot`, high bits = debug uniqueness).
4. `seq`: retire order index (text/commit stream).

## Raw event records

For `occ`, `commit`, and `blk_evt` records, include:

1. `core_id`
2. `uop_uid` / `parent_uid` (when applicable)
3. `block_uid`
4. `block_bid`
5. stage/lane/pc/rob metadata

## Konata rendering rules

1. One row per block lifecycle (`BLOCK` row).
2. One row per dynamic uop.
3. Uop rows are grouped under block rows by `(core_id, block_open_seq, row_kind, seq_or_first_cycle, uid)`.
4. Left pane:
   - block: concise block header
   - uop: `pc: asm` (or `GEN seq=<n> op=<mnemonic>` for generated uops)
5. Hover/detail keeps extended operand/memory/trap metadata.
