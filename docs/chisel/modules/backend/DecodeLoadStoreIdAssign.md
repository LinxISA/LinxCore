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
load/store sidebands, and the downstream accept event. When a load, store, or
DCZVA-like memory row is actually accepted, it stamps the row with the current
32-bit `lsID` and advances the reduced STID0 counters in model order.

The module also exposes 64-bit `load_id` and `sid` observability matching
`DCTop` counters, plus store-split intent matching the `SPERename` split
predicate. It does not yet carry `load_id`/`sid` through `DecodedUop`, allocate
LIQ/STQ rows, or create separate STA/STD uops.

## Interface

Inputs:

- `in`: selected decoded uop.
- `isLoad`, `isStore`, `isDczva`: decoded memory class sidebands. Store-like
  rows win if more than one class bit is asserted, matching the model's
  store/DCZVA branch.
- `isLoadStorePair`: suppresses store splitting for load/store-pair opcodes.
- `storeSplitRequest`, `stackSetRequest`: model split sources used by
  `SPERename::InsertToStoreIEX`.
- `accept`: exact downstream acceptance point. Counters advance only when this
  signal and a memory-valid row are both true.
- `flushValid`, `restoreValid`, `restoreLsId`, `restoreLoadId`,
  `restoreStoreId`: reduced cleanup hook. The integrated block/header owner can
  later restore model-derived counters instead of resetting to zero.

Outputs:

- `out`: decoded uop with `lsid` replaced by the current LSID for memory rows.
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

For a valid load, `out.lsid`, `assignedLsId`, and `assignedLoadId` reflect the
pre-accept counter values. If the row is accepted, `nextLsId` and `nextLoadId`
increment. For a valid store or DCZVA-like row, `out.lsid`, `assignedLsId`,
and `assignedStoreId` reflect the pre-accept values, then `nextLsId` and
`nextStoreId` increment on accept. Non-memory rows pass through without
changing counters.

`storeSplitIntent` is true only for store rows where either the decoded row
requests splitting or stack-set handling forces it, and where
`isLoadStorePair` is false. This mirrors the front of
`SPERename::InsertToStoreIEX`; the later store-split owner must still clone
STA/STD uops and apply `HandleSta` PCR-source rules.

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

This Chisel owner preserves the pre-increment assignment rule and the accept
boundary. It deliberately keeps block command start IDs, tile block split
counts, PCR store source rewriting, and the actual STA/STD clone write in
later owners.

## Deferred Owners

- Width-wide slot-order allocation for multiple decoded memory uops in one
  cycle.
- Per-STID counters and block-header counter restore from `BlockCommand`
  context.
- Carrying `load_id`/`sid` through common uop bundles into LIQ/STQ owners.
- DCZVA opcode classification from generated decode metadata.
- Opcode-derived load/store-pair and cache-maintain split suppression.
- Store split rewrite into separate STA/STD uops plus PCR `HandleSta` source
  selection.
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
