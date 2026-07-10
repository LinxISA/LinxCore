# Rename and Dispatch

This document covers LinxCore rename and post-rename dispatch for LinxISA `P`, `T`, `U`, and `CARG` operands.

## 1 Rename Goals

Rename must eliminate false dependencies for global architectural state while preserving block-local state semantics:

- `P` destinations use normal physical-register map rename.
- `T` and `U` destinations allocate from block-local FIFO-backed physical pools.
- `CARG` is resolved through `BID` and is not independently renamed.
- Rename recovery is instruction-precise for `P` and block-lifecycle precise for block-private state.

This is the major migration from the ARM-oriented rename text. ARM architectural register classes are not the organizing principle; Linx operand class is.

The structure itself remains ISA-neutral: SMAP, CMAP, free list, and MapQ are preserved as the scalar global-register rename mechanism. The Linx-specific change is the architectural namespace bound to that mechanism: scalar `P` uses it, while `T`, `U`, and `CARG` do not.

## 2 Operand Classes

| Class | Source form | Destination form | Rename behavior |
| --- | --- | --- | --- |
| `P` | architectural `atag` | architectural `atag` | Map-based rename through `SMAP`, `CMAP`, and `MapQ`. |
| `T` | relative `t_rel` | relative `t_rel` | Allocated from `T_FIFO`; resolved to `ttag`. |
| `U` | relative `u_rel` | relative `u_rel` | Allocated from `U_FIFO`; resolved to `utag`. |
| `CARG` | implicit block argument/condition | none | Resolved by `bid` into the block-scoped CARG file. |

`D2` preserves semantic operand identity. `D3` adds backend tags:

- `P`: `atag + ptag`;
- `T`: `t_rel + ttag`;
- `U`: `u_rel + utag`;
- `CARG`: materialized value from the CARG file indexed by `bid`.

## 3 P Rename

`P` rename uses a conventional speculative and committed map:

- `CMAP` is the committed map.
- `SMAP` is the speculative map visible to rename.
- `MapQ` records speculative destination remaps against `CMAP`.
- `MapQ` applies only to `P` destinations.
- MapQ cut points are keyed by `rid`, not only by `bid`.
- Default flush replay keeps mappings up to and including the flushing instruction (`<= flush_rid`) for the younger-squash redirect path.
- Same-cycle inserts use stable FIFO order derived from `rid + decode_slot`.

LinxCoreModel evidence:

- `ggpr_count=128`;
- `ggpr_mapq_depth=256`;
- `ptag_stall_enable=false` in current config, meaning the model does not intentionally stall on ptag pressure through that switch.

### 3.1 ISA-Neutral Structure

The scalar `P` rename machinery keeps the classic OOO structure:

| Structure | Preserved role | Linx binding |
| --- | --- | --- |
| SMAP | Latest speculative architectural-to-physical map. | Indexed by scalar `P` architectural tags. |
| CMAP | Committed architectural-to-physical map. | Updated when ROB commit walks matching MapQ rows. |
| Free list | Pool of available physical tags. | Supplies scalar GGPR physical tags only. |
| MapQ | Ordered remap log for commit release and flush recovery. | Rows carry `stid`, `bid`, `rid`, decode-slot order, architectural `P` tag, new ptag, and previous ptag. Cross-block order comes from ROB/BROB ring context, not BID numerics. |

MapQ append, commit, and recovery behavior remains the same class of mechanism as the ARM source spec. The fields and recovery keys are upgraded for Linx block identity.

## 4 T and U Rename

`T` and `U` are block-local ClockHands temporaries. They do not use `CMAP`, `SMAP`, or `MapQ`.

Rules:

- `T` destinations allocate from `T_FIFO`.
- `U` destinations allocate from `U_FIFO`.
- Same-cycle allocations are in decode-slot order.
- The backend carries resolved `ttag` and `utag` tags after `D3`.
- Lifetimes are block-scoped and must compose with `BSTART`, `BSTOP`, template blocks, and flush.
- Current block-private RF documentation treats BSTOP as the release point for block-private live state unless a promoted contract narrows a special case.

LinxCoreModel evidence:

- scalar `local_reg_t=192`;
- scalar `local_reg_u=64`;
- scalar local register width is 64 bits;
- SIMT and MTC local T/U pools are configured separately at 512 entries each.

## 5 CARG Resolution

There is exactly one `CARG` domain per block. It is identified by `bid`.

`CARG` is used by block-control operations such as `SETC.*` and boundary-consumed control decisions. It does not remain as an IQ-visible operand payload after `D3`; it is materialized into the block-scoped CARG file.

This avoids treating block condition/target state as a general architectural register. That distinction is required for boundary-authoritative redirect.

## 6 D3 Renamed-Uop Latch

`D3` is the renamed-uop latch boundary. It preserves both:

- semantic operand information needed for trace/debug;
- resolved physical tags needed by issue, wakeup, and execution.

The latch must carry `rid`, `bid`, `lsid`, `pc`, `insn_raw`, `insn_len`, boundary metadata, execution class, source tags, destination tags, readiness hints, and exception/fault metadata.

## 7 S1 Dispatch Preparation

`S1` receives renamed uops from `D3`. It performs:

- execution-class classification;
- target physical IQ selection;
- source readiness query;
- block-boundary filtering;
- command/BBD routing decisions.

`BBD` boundary uops do not enter ordinary IQs. Their architectural visibility is handled before `S2` writes into the issue fabric.

Ready queries are non-speculative:

- `P`: `ready_table_p[ptag]`;
- `T`: `ready_table_t[ttag]`;
- `U`: `ready_table_u[utag]`.

Speculative readiness, such as load speculative wakeup, lives inside the IQ entry rather than in the global ready table.

## 8 S2 IQ Enqueue

`S2` performs actual enqueue. It must preserve program order within one decode/rename group when multiple uops target the same queue.

If more uops target a physical IQ than the queue can accept in one cycle, older decode slots win. Younger uops remain in the dispatch path or stall according to the local backpressure policy.

## 9 Physical IQ Routing

The promoted physical IQ set is:

| Physical IQ | Primary classes |
| --- | --- |
| `alu_iq0` | ALU first choice |
| `shared_iq1` | ALU spill, FSU, SYS, shared scalar work |
| `bru_iq` | BRU/control execute work |
| `agu_iq0` | AGU first choice |
| `agu_iq1` | AGU spill |
| `std_iq0` | Store-data first choice |
| `std_iq1` | Store-data spill |
| `cmd_iq` | Command and block-fabric work |

External trace and tooling must preserve the architectural class mapping such as `issq_alu`, `issq_bru`, `issq_agu`, `issq_std`, `issq_fsu`, `issq_sys`, and `issq_cmd` even if physical queues are later merged or split.

## 10 Capacity Evidence

LinxCoreModel `iex.toml` provides the current executable-reference queue evidence:

| Queue family | Depth | Count | Write ports | Picker |
| --- | ---: | ---: | ---: | ---: |
| CMD | 24 | 1 | 4 | 1 |
| ALU | 24 | 8 | 4 | 1 |
| LDA/AGU | 32 | 4 | 4 | 1 |
| STA | 16 | 4 | 4 | 1 |
| STD | 16 | 4 | 4 | 1 |
| BRU | 32 | 4 | 4 | 1 |

The promoted architecture contract describes two enqueue/write ports per physical IQ in the current baseline. The table above records model capacity, not necessarily the narrowed RTL target.

## 11 Open Questions

- Should scalar `T`/`U` release be specified only at BSTOP, or should call/template-return boundaries have an explicit earlier release rule?
- Should `cmd_iq` receive all descriptor instructions (`B.IOR`, `B.IOT`, `B.DIM`, `B.TEXT`) or only the subset not consumed by BCTRL during decode/header ingestion?
- Which queue capacity table should be normative for the first RTL target: the promoted two-port physical-IQ contract or the wider LinxCoreModel queue-family config?
