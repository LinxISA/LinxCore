# FrontendDecodeIngress

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendDecodeIngress.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/frontend/FrontendDecodeIngressSpec.scala`
- Previous pyCircuit owners:
  - `rtl/LinxCore/src/bcc/frontend/ibuffer.py`
  - `rtl/LinxCore/src/bcc/ifu/f3.py`
  - `rtl/LinxCore/src/bcc/ifu/f4.py`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
- Contract IDs: `LC-IF-CHISEL-FEING-001`, `LC-IF-CHISEL-IB-001`, `LC-IF-CHISEL-F4-001`

## Purpose

`FrontendDecodeIngress` is the Phase 2 frontend transport wrapper that connects
`FrontendInstructionBuffer` to `F4DecodeWindow`. It gives later frontend and
decode work a single ingress owner for buffered F3/F4 packets, F4 slot
visibility, and decode-side consumption backpressure.

The module deliberately does not perform opcode decode, macro-boundary decode,
ROB/BROB allocation, or D1/D2 uop construction. Those remain decode-owner and
backend-owner responsibilities.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `push` | `FrontendDecodePacket` | `push.valid && pushReady` | Packet from F3/F4 production |
| output | `pushReady` | `Bool` | ready | High when the instruction buffer can accept the packet |
| input | `decodeReady` | `Bool` | ready | Downstream D1/decode readiness for the current visible packet |
| input | `flushValid` | `Bool` | always sampled | Clears the instruction buffer and masks F4/D1 visibility |
| output | `d1` | `FrontendDecodePacket` | `d1.valid && decodeReady` | Oldest buffered packet after F4 flush masking |
| output | `slots[4]` | `F4Slot` | `slots(i).valid` | F4 length/offset/PC/raw/UID metadata for the oldest packet |
| output | `validMask` | `UInt(4.W)` | bit mask | Valid slots with slot 0 in bit 0 |
| output | `slotCount` | `UInt(3.W)` | derived | Popcount of `validMask` |
| output | `totalLenBytes` | `UInt(4.W)` | derived | Byte span consumed by valid slots in the 8-byte window |
| output | `popFire` | `Bool` | fire | The oldest buffered packet is consumed this cycle |
| output | `ibHead` | `UInt(log2(depth).W)` | debug | Instruction-buffer read pointer |
| output | `ibTail` | `UInt(log2(depth).W)` | debug | Instruction-buffer write pointer |
| output | `ibCount` | `UInt(log2(depth + 1).W)` | debug | Instruction-buffer occupancy |

Default `ibufDepth` is 8 and must remain a positive power of two.
The pushed and visible `FrontendDecodePacket` carries `peId`, `threadId`,
PC/window, packet UID, and checkpoint ID as one packet-owned identity record.

## State

The only state is the child `FrontendInstructionBuffer`:

- `entries[depth]`: buffered `FrontendDecodePacket` records.
- `head`: oldest packet pointer.
- `tail`: next push pointer.
- `count`: occupied entry count.

`F4DecodeWindow` is combinational and owns no state.

## Logic Design

The ingress wires `push` directly into `FrontendInstructionBuffer`. The oldest
buffered packet feeds `F4DecodeWindow`, which generates `d1`, slots,
`validMask`, `slotCount`, and `totalLenBytes`.

The instruction buffer pops only when both conditions are true:

```text
decodeReady && f4.d1.valid
```

This keeps an empty or flushed buffer from consuming, and it keeps a visible
packet resident while decode is stalled. Flush masks `f4.d1.valid`, so a flush
cycle cannot pop the packet; the buffer clears through its own flush path.

The push side keeps the `FrontendInstructionBuffer` conservative pre-cycle
occupancy rule: a full buffer deasserts `pushReady` even if decode would also
consume the head in the same cycle.

## Timing

`FrontendDecodeIngress` is a one-packet-per-cycle transport wrapper. A packet
pushed into an empty buffer is visible to F4 on a later cycle through the
registered FIFO state; there is no same-cycle push-to-D1 bypass.

The F4 slot outputs are combinational from the current instruction-buffer head.
Future registered D1/D2 decode stages may sample these outputs but must not move
opcode decode or uop construction back into this wrapper.

## Flush/Recovery

`flushValid` is forwarded to both children:

- `FrontendInstructionBuffer` clears occupancy and masks output.
- `F4DecodeWindow` clears `d1.valid`, slot valids, `validMask`, `slotCount`,
  and `totalLenBytes`.

The module does not choose a restart PC, owner, or checkpoint. Frontend restart
remains owned by the later FLS/F0 recovery handoff, while packet owner and
checkpoint identity are carried as `FrontendDecodePacket.peId`, `threadId`,
and `checkpointId`.

## Trace/Observability

`ibHead`, `ibTail`, and `ibCount` expose queue residency for bring-up. Slot
metadata remains the `F4Slot` contract:

- `slot.pc = d1.pc + slot.offsetBytes`
- `slot.uopUid = (d1.pktUid << 3) | slot`

These are frontend/DFX identifiers, not commit identity. Commit comparison still
uses `CommitTraceRow` and the neutral QEMU adapter.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress`
- `bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer`
- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/build_chisel.sh`

The focused reference tests cover FIFO residency before visibility,
decode-side hold and consume, F4 slot slicing with packet identity, flush
clearing/masking, ingress backpressure, IO widths, and Chisel elaboration with
the IB and F4 child modules.
