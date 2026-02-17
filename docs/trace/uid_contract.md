# LinxCore UID and Sequence Contract

## Identity fields

1. `uop_uid` (64-bit): identity of one dynamic micro-op lifetime.
2. `seq` (64-bit): architectural retire sequence index.
3. `block_uid` (64-bit): identity of one dynamic block lifetime (`BSTART` open to `BSTOP` close).
4. `uop_parent_uid` (64-bit): lineage for template/replay descendants.

## Meaning

1. `uop_uid` answers "which dynamic uop line is this?"
2. `seq` answers "in what architectural retire order did this commit happen?"
3. `block_uid` answers "which dynamic block owns this uop?"

## Rules

1. Every dynamic uop has exactly one `uop_uid`.
2. Replayed uops use a new `uop_uid` and link `uop_parent_uid` to the prior uid.
3. Template-expanded children have unique `uop_uid` values and parent linkage to the macro/template parent.
4. A `uop_uid` may be non-retired (flushed/trapped), while `seq` exists only for committed uops.
5. TB ignores post-retire/post-flush occupancy echoes for the same `uop_uid`.

## Konata mapping

1. `kid` is a local row id in one trace file.
2. Uop rows carry `uid_hex = uop_uid`.
3. Block rows use a disjoint high-bit namespace (`uid_hex = 0x8000... | block_uid`) to avoid row collapse.
4. Row order is hybrid:
   - Block row key: `(core_id, block_seq_open, 0, block_uid, 0)`
   - Uop row key: `(core_id, block_seq_open, 1, commit_seq_or_max, uop_uid)`
