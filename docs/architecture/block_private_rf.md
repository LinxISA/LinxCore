# Block-Private Register Model (Bring-Up)

## Scope

This phase keeps the global GPR rename/PRF model intact and enforces block-private lifetime through block metadata (`block_uid`, `block_bid`) and `BSTOP`-gated release paths.

## Current behavior

1. GPR (`R0..R23`) uses the normal rename + PRF path.
2. Block-local state (`T/U` stacks and block-argument semantics) remains represented through existing rename maps (`SMAP/CMAP`) with boundary-aware reset/release.
3. Block identity is now carried in ROB as:
   - `block_uid`
   - `block_bid`
4. `BSTOP` is the only architectural release point for block-private live state in commit-side rename handling.

## Notes

1. A fully physicalized tagged local RF bank keyed by `(block_uid, class, index)` is planned as the next step.
2. The current model is functionally aligned with block lifetime rules while preserving existing execute/commit behavior and co-sim contracts.
