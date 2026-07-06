# LoadReplayReturnCompleteRepickSelect

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnCompleteRepickSelect.scala`
- Tests: covered through `ReducedLoadReplayLiqAllocPath` and
  `LinxCoreFrontendFetchRfAluTraceTop`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::pickL1`
    - `LDQInfo::receiveData`
    - `LDQInfo::handleSCBReceive`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::handleMerge`
    - `LDQInfo::returnData`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightRowMutationApply.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnDataExtract.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-009`

## Purpose

`LoadReplayReturnCompleteRepickSelect` selects the oldest resident scalar
`Repick` LIQ row that has enough source and data evidence to enter the
replay-return data extraction path. It is the post-mutation return selector:
source-return and row-data mutation may update a resident row first, then this
owner classifies whether that row is complete enough to feed
`LoadReplayReturnDataExtract`.

The model counterpart is the `LDQInfo::pickL1` to `LDQInfo::returnData`
boundary. A replay row can return only after the SCB/store sources have
returned, requested data bytes are complete, and the row is not a wait-store or
tile transfer. The Chisel owner exposes each predicate as a mask so generated
RTL can distinguish missing residency, missing source-return, missing byte
completion, and age-selection effects before enabling live publish policy.

R542 wires these masks to `LinxCoreFrontendFetchRfAluTraceTop` diagnostics and
bumps the Verilator sideband report to schema v17. The replay-loop probe
observed nonzero `repickMask` and `sourceReturnedMask`, but zero
`dataCompleteMask`, `requestCompleteMask`, `returnCandidateMask`, and
`returnValid`. This classifies the current replay-return blocker after the
local STQ source-return row mutation write: a row reaches source-returned
Repick state, but its row data/valid-mask completion is still absent at the
complete-repick selector.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Enables complete-repick selection. |
| `rows` | Current LIQ row image, including row status, source-return bits, line data, valid byte mask, wait-store state, and tile marker. |

### Outputs

| Signal | Description |
|---|---|
| `repickMask` | Resident rows that are valid and in `LoadInflightStatus.Repick`. |
| `sourceReturnedMask` | Repick rows with `sourcesReturned`, `scbReturned`, and `stqReturned` all true. |
| `dataCompleteMask` | Repick rows whose row `dataComplete` bit is set and whose valid-byte mask covers the request byte mask. |
| `requestCompleteMask` | Repick rows whose row valid-byte mask covers the computed request byte mask. |
| `returnCandidateMask` | Enabled, scalar, non-wait-store rows satisfying repick, source-return, and data-complete predicates. |
| `returnMask` | One-hot selected oldest return candidate. |
| `returnValid` | At least one return candidate was selected. |
| `returnIndex` | LIQ index of the selected row. |
| `candidateCount` | Number of complete return candidates before age selection. |
| `selected*` | Identity, address, size, destination, source traces, line data, valid mask, and sideband metadata copied from the selected row when `returnValid` is true. |

## Logic Design

The module computes the byte request mask from `addr[5:0]` and `size` for each
row, then derives ordered predicates:

```text
repick = row.valid && row.status == Repick
sourceReturned = row.sourcesReturned && row.scbReturned && row.stqReturned
requestComplete = requestMask != 0 && ((row.validMask & requestMask) == requestMask)
dataComplete = row.dataComplete && requestComplete
candidate = enable && repick && sourceReturned && dataComplete && !waitStore && !isTile
```

Age selection is by `(bid, loadLsId)` using the same store-queue ordering helper
used elsewhere in the reduced replay path, with LIQ index as a tie-breaker for
same-order rows. Only the selected candidate drives `selected*`; when no row is
complete, selected metadata is zeroed or disabled.

## R542 Evidence

The focused generated-RTL/QEMU run used:

```text
generated/r542-replay-return-complete-selector-diagnostics
```

The comparator passed with 9 QEMU rows, 9 DUT rows, 9 compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows. The sideband report recorded:

```text
liq_row_mutation_write_enable=4
liq_return_complete_repick_mask_nonzero=11
liq_return_complete_source_returned_mask_nonzero=3
liq_return_complete_data_complete_mask_nonzero=0
liq_return_complete_request_complete_mask_nonzero=0
liq_return_complete_candidate_mask_nonzero=0
liq_return_complete_mask_nonzero=0
liq_return_complete_valid=0
return_data_candidate_valid=0
return_publish_candidate_valid=4
lret_payload_candidate_valid=4
```

That result keeps the next owner local to row data/valid-mask completion after
source-return mutation. It does not justify changes to LRET FIFO capacity,
publish request fanout, return-data extraction, W2 residency, or replay-row
drain policy.

## Deferred Owners

- Row-mutation data/valid-mask completion for local STQ source-return rows.
- Cross-line and tile return selection.
- Live publish enable and LRET/mem-wakeup side effects after scalar row data is
  complete.

## Verification

- `python3 -m py_compile tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py`
- `git diff --check`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `FETCH_REPLAY_LIQ_REQUIRE_NONZERO=...liq_return_complete_valid LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r542-replay-return-complete-selector-diagnostics --fixture replay-ldi-sdi-ldi-loop --capture-rows 18 --reduced-store-replay-liq --disable-store-memory-mutation --expect-load-pcs 0x10002,0x1000a,0x10002,0x1000a,0x10002,0x1000a --expect-store-pcs 0x10006,0x10006,0x10006 --max-seconds 8`
- `python3 tools/chisel/validate_frontend_fetch_rf_alu_sideband_stats.py --expect-reduced-store-replay-liq generated/r542-replay-return-complete-selector-diagnostics/report/frontend_fetch_rf_alu_sideband_stats.json --require-nonzero replay_liq.wait_replay_capture_accepted --require-nonzero replay_liq.replay_queue_out_fire --require-nonzero replay_liq.liq_alloc_accepted --require-nonzero replay_liq.source_return_response_apply_valid --require-nonzero replay_liq.liq_row_mutation_write_enable --require-nonzero replay_liq.liq_return_complete_repick_mask_nonzero --require-nonzero replay_liq.liq_return_complete_source_returned_mask_nonzero`
