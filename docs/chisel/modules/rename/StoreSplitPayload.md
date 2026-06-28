# StoreSplitPayload

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/StoreSplitPayload.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/rename/StoreSplitPayloadSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
    - `SPERename::InsertToStoreIEX`
    - `HandleSta`
  - `model/LinxCoreModel/isa/ISACommon/OpcodeManager.h`
    - `OpcodeIsStorePCR`
    - `GetStoreDataSrcIndex`
    - `IsLoadStorePair`
  - `model/LinxCoreModel/model/bctrl/spe/Decoder.cpp`
    - `Decoder::DecodeInst`
  - `model/LinxCoreModel/model/ModelCommon/SimInstInfo.h`

## Purpose

`StoreSplitPayload` is the first Chisel owner for the model store-dispatch
payload split after scalar rename. It consumes one `RenamedUop` carrying
memory-class and store-split metadata, then emits either:

- an atomic `STA` plus `STD` pair for split stores, or
- a single `ST_ALL` payload for unsplit stores.

The module deliberately stops at payload construction. It does not mutate STQ
state, allocate STQ rows, touch SCB/MDB state, or choose memory-side data
values. `STQEntryBank` already owns the complementary `ST_ADDR`/`ST_DATA`
merge rule, so this owner only prepares the dispatch payload shape and
backpressure decision. The reduced `DecodeRenameROBPath` instantiates this
owner for accepted renamed rows and feeds the resulting payloads into
`StoreDispatchQueues`.

## Interface

Inputs:

- `in`: renamed uop. Relevant metadata is `valid`, `isStore`,
  `storeSplitIntent`, `isLoadStorePair`, `isStorePcr`,
  `cacheMaintainNoSplit`, source operands, ROB/BID identity, and `lsid`.
- `staReady`: address-side store dispatch queue can accept a payload.
- `stdReady`: data-side store dispatch queue can accept a payload.

Outputs:

- `inReady`: upstream can advance. Non-store rows are always ready; split
  stores require both STA and STD readiness; unsplit stores require STA
  readiness.
- `fire`: accepted store payload event.
- `storeActive`: `in.valid && in.isStore`.
- `split`: final split decision after pair/cache-maintain suppression.
- `blockedBySta`, `blockedByStd`: dispatch backpressure diagnostics.
- `sta`: address-side payload with store type `Addr`.
- `std`: data-side payload with store type `Data`.
- `unsplit`: single store payload with store type `All`.

`StoreSplitStoreType` preserves the model `ST_*` order:

| Chisel | Value | Model |
|---|---:|---|
| `All` | 0 | `ST_ALL` |
| `Addr` | 1 | `ST_ADDR` |
| `Data` | 2 | `ST_DATA` |

## Logic Design

The final split predicate is:

```text
in.valid &&
in.isStore &&
in.storeSplitIntent &&
!in.isLoadStorePair &&
!in.cacheMaintainNoSplit
```

`storeSplitIntent` is expected to come from `DecodeLoadStoreIdAssign`, which
already folds the model `storeSplit || STACK_SET` sources for the reduced
path. `StoreSplitPayload` repeats the pair/cache-maintain guards so the split
boundary stays local even if an upstream owner is conservatively broad.

When `split` is true, `inReady` requires both `staReady` and `stdReady`, and
`fire` asserts only when both halves can be emitted in the same cycle. No
partial STA or STD payload is produced under backpressure. Both split payloads
copy the renamed uop identity, including `bid`, `gid`, `rid`, `blockBid`, and
`lsid`, so the halves share one store identity.

For ordinary split stores, the STA payload replaces `src(0)` with a zero
literal/invalid operand and raises `staSrc0Zeroed`. This mirrors model
`HandleSta`, which removes the store-data source from the address half. For
PCR stores, STA preserves `src(0)` and `dataSrcIndex` is `1`, matching
`GetStoreDataSrcIndex`. Ordinary stores use `dataSrcIndex = 0`.

When `split` is false for a store row, the module emits only `unsplit` with
store type `All` and requires only `staReady`, matching the C++ path that writes
the original store to the STA dispatch queue.

## Model Alignment

`SPERename::InsertToStoreIEX` computes:

- split when `(inst->storeSplit || inst->stack_type == STACK_SET)` and the
  opcode is not a load/store pair;
- stall when either STA or STD dispatch queue is unavailable;
- on split, clone the instruction into STA and STD rows, call `HandleSta`,
  mark the STA type as `ST_ADDR`, and mark the STD type as `ST_DATA`;
- on unsplit, write the original instruction to the STA queue.

`HandleSta` zeroes ordinary store source 0, keeps PCR store source 0, and
deep-copies source operands so later wakeup mutation cannot alias the original
store. This Chisel owner mirrors the externally visible source-selection
contract and keeps the deeper wakeup aliasing implications for the future
issue/ready-table owner.

`Decoder::DecodeInst` sets `storeSplit = false` for `CACHE_MAINTAIN`. The
generated Chisel opcode metadata now drives `cacheMaintainNoSplit`, while
load/store-pair and PCR-store metadata are carried beside it from decode
through scalar rename into this owner.

## Deferred Owners

- Stack rename payloads and explicit STA stack-type clearing / STD stack-type
  forwarding.
- STA/STD execution and STQ mutation behind `StoreDispatchQueues`.
- STQ allocation, complementary partial-store merge, and STQ residency
  counters.
- Issue wakeup aliasing, ready-table initialization, and real source readiness.
- Full `sid`/`load_id` carry into LIQ/STQ owners.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The focused tests cover ordinary split payload construction, PCR data source
selection, load/store-pair and cache-maintain suppression, no-partial-fire
backpressure, non-store no-op behavior, enum values, IO widths, and CIRCT
elaboration.
