# LSID Memory Ordering (Bring-Up)

## Goal

Enforce a strict, per-core load/store issue order without memory speculation.

## Mechanism

1. Dispatch allocates a monotonic `load_store_id` for every LSU-class uop.
2. ROB stores `load_store_id` per entry until commit/flush clear.
3. LSU lane checks:
   - current uop `load_store_id`
   - global `lsid_issue_ptr`
4. LSU can issue memory only when `uop.load_store_id == lsid_issue_ptr`.
5. On effective LSU issue, pointers advance:
   - `lsid_issue_ptr += 1`
   - `lsid_complete_ptr += 1`

## Flush/redirect behavior

1. Redirect/flush drops speculative younger memory uops.
2. To avoid pointer deadlock on dropped IDs, LSID issue/complete pointers are rebased to the allocation head on flush.

## Contract impact

1. No required external DUT/co-sim schema change.
2. `load_store_id` is exported as additive debug/trace metadata (`dispatch/issue/commit` taps).
