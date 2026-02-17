# LinxCore Block + Uop Pipeview Design

## Goal

Visualize LinxISA block-structured control flow and micro-uop execution at the same time:

1. One row per dynamic block lifecycle (`BSTART` open, `BSTOP` close/fault).
2. One row per dynamic micro-uop (`uop_uid`) with exact stage residency.

## Two-layer trace model

1. Block rows:
   - Kind: `block`
   - Left label: `BLOCK c<core> b<block_uid> <open_pc>..<close_pc> <status>`
   - Built from commit boundary metadata (`commit_is_bstart`, `commit_is_bstop`, `commit_block_uid`) and block events.
2. Uop rows:
   - Kind: `normal|template_child|replay|flush|trap`
   - Left label: `pc: disassembly`
   - Hover/detail (`L type=1`): `uid/op/src/dst/mem/trap/lineage`.

## Data sources

1. Raw per-cycle occupancy and commit stream from:
   - `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp`
   - `PYC_RAW_TRACE=<path>.jsonl`
2. Offline builder:
   - `/Users/zhoubot/LinxCore/tools/trace/build_konata_block_view.py`
3. Output:
   - `Kanata\t0005` trace with `I/L/P/R/C`.

## Deterministic row ordering (seq+uid hybrid)

1. Block row key:
   - `(core_id, block_seq_open, 0, block_uid, 0)`
2. Uop row key:
   - `(core_id, block_seq_open, 1, commit_seq_or_max, uop_uid)`

This keeps architectural order stable while preserving dynamic identity.

## Exact residency semantics

1. `P` records are emitted from stage occupancy (`dbg__occ_*`) snapshots.
2. One uid should be in one stage per cycle (except lifecycle markers such as `CMT/FLS`).
3. No stage occupancy is emitted after terminal lifecycle (`R`) for that uid.

## Multi-core mixed trace

1. Single output trace mixes all cores.
2. Lane token format: `c<core>.l<lane>` for pipeline lanes and `c<core>.blk` for block rows.
3. `tid` in `I` records matches `core_id`.

## Validation gates

1. Stage/format checks:
   - `/Users/zhoubot/LinxCore/tools/konata/check_konata_stages.py`
2. Parser acceptance:
   - `/Users/zhoubot/Konata/onikiri_parser.js` (v0005 path)
3. CoreMark smoke example:
   - `/Users/zhoubot/LinxCore/tools/konata/run_konata_trace.sh /Users/zhoubot/LinxCore/tests/benchmarks/build/coremark_real.memh 1000`
