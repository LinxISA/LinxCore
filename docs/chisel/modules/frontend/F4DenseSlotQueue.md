# F4DenseSlotQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DenseSlotQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/frontend/F4DenseSlotQueueSpec.scala`
- Current top consumer:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
- Contract IDs: `LC-IF-CHISEL-F4-002`

## Purpose

`F4DenseSlotQueue` is the reduced dense-packet bridge between
`F4DecodeWindow` and the current one-row `DecodeRenameROBPath`. The C++ model
keeps multiple valid decoded slots in one `DecodeBundle` as it moves through
F4/F5/IB, and scalar decode can then allocate rows in slot order. The reduced
Chisel backend still accepts only one selected decoded row per cycle, so this
queue captures every valid F4 slot from one 64-bit window and drains exactly
one original slot per cycle.

This is not the final width-wide decode/ROB allocator. It is a compatibility
owner that lets live fetch evidence use dense 8-byte response windows without
dropping later scalar or block-marker slots.

## Interface

### `F4DenseSlotQueueIO`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `inD1` | `FrontendDecodePacket` | `inD1.valid` | D1 packet metadata from `F4DecodeWindow`. |
| input | `inSlots` | `Vec(decodeWidth, F4Slot)` | `inValidMask` bits | Non-compacted F4 slots for one fetch window. |
| input | `inValidMask` | `UInt(decodeWidth.W)` | bit mask | Valid slot mask from F4. |
| output | `inReady` | `Bool` | ready | Asserted when the queue can accept all valid slots in the packet. |
| output | `outD1` | `FrontendDecodePacket` | `outD1.valid` | Packet metadata for the queue head slot. |
| output | `outSlots` | `Vec(decodeWidth, F4Slot)` | `outValidMask` one-hot | One original slot is driven at its original slot index; all other slots are zero. |
| output | `outValidMask` | `UInt(decodeWidth.W)` | one-hot | Valid bit for the queue head's original slot index. |
| input | `outReady` | `Bool` | ready | Downstream one-row decode path can consume the head slot. |
| input | `flushValid` | `Bool` | always sampled | Clears all queued slots and blocks same-cycle enqueue/dequeue visibility. |
| output | `inFire`, `outFire` | `Bool` | pulse | Packet capture and single-slot drain events. |
| output | `inSlotCount` | `UInt(log2Ceil(decodeWidth + 1).W)` | diagnostic | Popcount of `inValidMask`. |
| output | `count` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Number of queued slots. |
| output | `headSlotIndex` | `UInt(log2Ceil(decodeWidth).W)` | diagnostic | Original F4 slot index of the queue head. |
| output | `full`, `empty` | `Bool` | diagnostic | Occupancy status. |

`depth` must be a power of two and at least `decodeWidth`, so one full F4
window can always be represented when the queue is empty.

## State

The module owns a circular FIFO of compacted slot entries. Each entry stores:

- one copy of the D1 packet metadata,
- one `F4Slot`,
- the slot's original F4 slot index.

The stored slot index is part of the behavioral contract. It lets the reduced
one-row decode path see the same slot position that F4 produced, preserving
slot-derived UID and diagnostics while still draining serially.

## Logic Design

On `inFire`, the queue scans `inValidMask` in slot order. For each valid slot,
it writes one FIFO entry at `tail + PopCount(valid bits before this slot)`.
The queue advances `tail` by the total valid slot count. The input packet is
accepted atomically only when there is enough free space for every valid slot
in the current F4 window.

On `outFire`, the queue presents the head entry as a one-hot packet:

- `outD1.valid` is true,
- `outSlots(headSlotIndex)` carries the stored slot with `valid = true`,
- all other `outSlots` entries are zero,
- `outValidMask` is `1 << headSlotIndex`.

This shape intentionally matches the current `DecodeRenameROBPath` contract:
that path chooses one decoded slot without compaction and then owns ROB/BROB
allocation, marker classification, rename, issue enqueue, and commit.

`flushValid` clears the FIFO, head, tail, and count. It also suppresses input
acceptance and output validity for the flushed cycle.

## Model Alignment

`PEIFU::InsertToF4`, `InsertToF5`, and `InsertToIB` carry a `DecodeBundle`
with `mask`, `entry[]`, `isize[]`, and `tpcArr[]` through the frontend. The
model does not turn a dense fetch window into one testbench response per
instruction. `DCTop::Work` later iterates decoded rows and allocates scalar
ROB entries in order.

`F4DenseSlotQueue` preserves the frontend half of that contract in the reduced
RTL lane: all valid F4 slots from one window are retained, in order, before
the serialized backend consumes them. Width-wide scalar ROB allocation remains
a later owner.

## Trace/Observability

The top-level `LinxCoreFrontendFetchRfAluTraceTop` exposes queue diagnostics:

- `denseSlotQueueInFire`
- `denseSlotQueueOutFire`
- `denseSlotQueueInSlotCount`
- `denseSlotQueueCount`
- `denseSlotQueueHeadSlot`
- `denseSlotQueueFull`
- `denseSlotQueueEmpty`

Live QEMU dense-marker evidence uses these signals to prove that one F4 packet
can capture multiple slots, then drain scalar and marker slots without relying
on a single-instruction response window.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only F4DenseSlotQueue`
- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `BUILD_DIR=generated/r102-default-fetch-rf-alu-trace-top-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r102-dense-qemu-elf-xcheck --elf generated/r102-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`

R102 evidence records both generated-RTL cross-check manifests as
`status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
