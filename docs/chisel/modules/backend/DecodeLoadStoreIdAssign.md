# DecodeLoadStoreIdAssign

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeLoadStoreIdAssign.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DecodeLoadStoreIdAssignSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/Decoder.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.h`
  - `model/LinxCoreModel/model/core/Bus.h`

## Purpose

`DecodeLoadStoreIdAssign` is the first Chisel owner for decode-side scalar
memory-order identity. It consumes one selected `DecodedUop`, the frontend
load/store sidebands, and the downstream accept event. It stamps every valid
row with the current 32-bit `lsID` snapshot before any counter increment,
matching `Decoder::DecodeInst()`. When a load, store, or DCZVA-like memory row
is actually accepted, it advances the reduced STID0 counters in model order.

The module also exposes 64-bit `load_id` and `sid` observability matching
`DCTop` counters, plus store-split intent matching the `SPERename` split
predicate. It does not yet carry `load_id`/`sid` through `DecodedUop`, allocate
LIQ/STQ rows, or mutate STA/STD dispatch queues.

## Interface

Inputs:

- `in`: selected decoded uop.
- `isLoad`, `isStore`, `isDczva`: decoded memory class sidebands. Store-like
  rows win if more than one class bit is asserted, matching the model's
  store/DCZVA branch.
- `isLoadStorePair`: suppresses store splitting for load/store-pair opcodes.
- `isStorePcr`: marks PCR stores whose address half preserves source 0 and
  whose data source index is 1 in the later split owner.
- `cacheMaintainNoSplit`: suppresses store splitting for cache-maintain rows.
- `storeSplitRequest`, `stackSetRequest`: model split sources used by
  `SPERename::InsertToStoreIEX`.
- `accept`: exact downstream acceptance point. Counters advance only when this
  signal and a memory-valid row are both true.
- `flushValid`, `restoreValid`, `restoreLsId`, `restoreLoadId`,
  `restoreStoreId`: reduced cleanup hook. The integrated block/header owner can
  later restore model-derived counters instead of resetting to zero.

Outputs:

- `out`: decoded uop with `lsid` replaced by the current LSID snapshot for
  every valid row and memory/split metadata carried into the queued row.
  Non-memory rows do not advance counters, but they still preserve the model
  retire watermark used by ROB/LDQ cleanup.
- `memoryValid`, `loadIdValid`, `storeIdValid`, `assignFire`: allocation event
  classification and advance observability.
- `assignedLsId`, `assignedLoadId`, `assignedStoreId`: IDs associated with the
  current selected row.
- `nextLsId`, `nextLoadId`, `nextStoreId`: current counter state that will be
  assigned to the next accepted matching row.
- `storeSplitIntent`: store should later be split into address and data halves.

## Logic Design

The module owns three counters for the reduced STID0 path:

- `nextLsId`: 32-bit backend `lsID`, matching the current common bundle
  contract.
- `nextLoadId`: 64-bit model `load_id` serial counter.
- `nextStoreId`: 64-bit model `sid` serial counter.

For every valid row, `out.lsid` reflects the pre-accept `nextLsId` value. For a
valid load, `assignedLsId` and `assignedLoadId` reflect the pre-accept counter
values, then `nextLsId` and `nextLoadId` increment when the row is accepted.
For a valid store or DCZVA-like row, `assignedLsId` and `assignedStoreId`
reflect the pre-accept values, then `nextLsId` and `nextStoreId` increment on
accept. Non-memory rows carry the current `lsID` snapshot without changing
counters.

`storeSplitIntent` is true only for store rows where either the decoded row
requests splitting or stack-set handling forces it, and where
`isLoadStorePair` and `cacheMaintainNoSplit` are both false. In the reduced
integrated path, those pair/PCR/cache-maintain sidebands now come from the
generated opcode metadata. The later store-split owner applies the same guard
again, then constructs STA/STD or ST_ALL payloads and applies `HandleSta`
PCR-source rules.

Flush has priority over accept. A flush with `restoreValid` loads the provided
counter image; a flush without restore resets all counters to zero. Full
block-header replay and per-STID counter recovery are deferred to the
block/decode owner that has `BlockCommand` context.

## Model Alignment

The C++ model has two related memory-order paths:

1. `Decoder::DecodeInst()` stamps `simInst->lsID` from decode state before
   incrementing it for loads, stores, and DCZVA.
2. `DCTop::SetLoadStoreID()` stamps `load_id` for `InstGroup::LOAD` and `sid`
   for `InstGroup::STORE`, then increments the matching per-STID counter.
3. `SPERename::InsertToStoreIEX()` later splits stores into `ST_ADDR` and
   `ST_DATA` clones when `storeSplit` or `STACK_SET` is active, except for
   load/store-pair opcodes.

This Chisel owner preserves the all-row pre-increment assignment rule and the
memory-row accept boundary. It deliberately keeps block command start IDs,
tile block split
counts, PCR store source rewriting, and downstream store execution/STQ
mutation in later owners.
R75 does not change memory-counter policy; it records that model row owner
sidecars (`inst->peID/stid`) pass through this memory-ID owner unmodified
before queue residency.

## Deferred Owners

- Width-wide slot-order allocation for multiple decoded memory uops in one
  cycle.
- Per-STID counters and block-header counter restore from `BlockCommand`
  context.
- Carrying `load_id`/`sid` through common uop bundles into LIQ/STQ owners.
- DCZVA opcode classification from generated decode metadata.
- Store execution, STQ insert readiness, and STQ composition behind
  `StoreDispatchToSTQ`.
- Enqueue-time ROB reservation before `DecodeRenameQueue`.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```
