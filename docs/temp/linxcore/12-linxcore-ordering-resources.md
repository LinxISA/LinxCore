# Ordering, Resources, and Forward Progress

This document consolidates LinxCore ordering invariants, resource parameters, backpressure rules, flush/rebase behavior, and forward-progress requirements.

## 1 Ordering Invariants

LinxCore must preserve these ordering invariants:

- ROB retirement is precise and instruction-ordered.
- BROB block completion and retirement are ordered by each STID's ring head,
  tail, occupancy, and wrap state; BID alone is only the slot index.
- Boundary redirect is selected at block boundary.
- `P` MapQ recovery is instruction-precise by `rid`.
- `T`/`U` lifetime is block-scoped and release is boundary-controlled.
- Scalar memory issue is LSID-ordered in the strict profile.
- Store visibility is commit/drain ordered.
- Traps, interrupts, and MMU faults report precise source state.
- Engine-backed side effects are visible only through architectural block, memory, and trace contracts.

## 2 Core Resource Evidence

Current LinxCoreModel `core.toml` evidence:

| Parameter | Value |
| --- | ---: |
| `block_rob_depth` | 256 |
| `threadCount` | 4 |
| `stdThreadCount` | 1 |
| `scalar_smt_thread` | 1 |
| `stdPeCount` | 1 |
| `simtPeCount` | 1 |
| `memPeCount` | 1 |
| `pe_fetch_width` | 16 bytes |
| `ggpr_count` | 128 |
| `ggpr_mapq_depth` | 256 |
| `local_reg_t` | 192 |
| `local_reg_u` | 64 |
| `scalar_local_reg_width` | 64 bits |
| `simtLane` | 64 |
| `scalar_lsu_num` | 1 |
| `scalar_lsu_load_windows` | 64 |
| `scalar_lsu_store_windows` | 256 |

## 3 IEX Resource Evidence

Current LinxCoreModel `iex.toml` evidence:

| Parameter | Value |
| --- | ---: |
| `max_iq_insert_num` | 4 |
| `iex_retire_width` | 4 |
| `iex_load_wb_width` | 4 |
| `ld_rscv_width` | 4 |
| `issuedPerIqLane` | 2 |
| `iex_dispatch_mode` | 1 |
| `iex_mdb_enable` | true |

Queue family depths:

| Queue | Depth | Count |
| --- | ---: | ---: |
| CMD | 24 | 1 |
| ALU | 24 | 8 |
| LDA | 32 | 4 |
| STA | 16 | 4 |
| STD | 16 | 4 |
| BRU | 32 | 4 |

## 4 LSU Resource Evidence

Current LinxCoreModel `lsu.toml` evidence:

| Parameter | Value |
| --- | ---: |
| `stq_depth` | 2048 |
| `scalar_stq_depth` | 256 |
| `max_sliding_windows` | 256 |
| `lsu_width` | 64 |
| `scalar_lu_clusters_depth` | 256 |
| `rslv_capcity` | 64 |
| `store_commit_count` | 64 |
| `l2_latency` | 10 |

These values guide implementation sizing. They do not override the normative single-scalar-LSU ordering contract.

## 5 BCTRL Resource Evidence

Current LinxCoreModel `bctrl.toml` evidence:

| Parameter | Value |
| --- | ---: |
| `brnu_rename_width` | 8 |
| `brnu_rename_get_width` | 32 |
| `brnu_rename_set_width` | 32 |
| `bissq_depth` | 32 |
| `bifu_bheader_fifo_depth` | 64 |
| `bctrl_bandwidth` | 4 |
| `block_rename_backpres_thres` | 80 |
| `issued_block_count` | 2 |
| `cubeIssueQDepth` | 32 |
| `ttransIssueQDepth` | 32 |
| `vecIssueQDepth` | 32 |
| `mtcIssueQDepth` | 32 |
| `tmaIssueQDepth` | 32 |
| `tauIssueQDepth` | 32 |
| `tileRenameEntriesDepth` | 256 |
| `tile_acc_tag_num` | 128 |
| `tile_tag_num` | 128 |

## 6 Flush and Rebase

Flush rules:

- instruction-level rename recovery cuts `P` MapQ by `rid`;
- block-carrying queues consume the selected BROB ring's kill mask or equivalent
  ring-age context; unsigned `bid <= flush_bid` / `bid > flush_bid` tests are
  illegal across wrap;
- speculative memory entries younger than the flush are dropped;
- LSID issue/complete pointers rebase to the allocation head;
- T/U block-private state must release or restore according to block boundary and trap snapshot rules;
- engine commands younger than the flush remain cancellable.

## 7 Backpressure

Backpressure may originate from:

- ROB or BROB capacity;
- MapQ or physical register exhaustion;
- T/U FIFO pressure;
- IQ capacity;
- RF read-port contention;
- LSID issue pointer blockage;
- STQ/load-window capacity;
- command queue or BISQ pressure;
- engine response/completion pressure;
- trap/interrupt serialization.

Backpressure must preserve program order where required and must not allow younger state to overtake older architecturally blocking state.

## 8 Forward Progress

Required forward-progress cases include:

- sustained branch recovery;
- repeated load miss with speculative wakeup;
- store drain pressure;
- `BID` wrap and flush;
- trap or interrupt during block-engine work;
- engine response delay;
- command queue full conditions;
- Device/MMIO serialization;
- template child-stream injection.

The core must not deadlock when these cases interact.

## 9 Open Questions

- Which model resource values should be frozen in the first public LinxCore spec table?
- Should `load_ooo_enable=true` and `bmdb_enable=true` be promoted as mandatory performance features or kept behind correctness-equivalent fallbacks?
- What minimum forward-progress test matrix is required before this draft can replace the ARM-oriented upgrade material for implementation planning?
