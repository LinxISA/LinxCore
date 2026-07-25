# FrontendInstructionBuffer

> **Architecture status — verification-only packet FIFO.** The production
> Instruction Buffer is an independent queue after I-F4 and before D1.
> It stores per-instruction 64-bit entries and supports up to four D1 reads per
> cycle. The current packet FIFO does not define that architecture.

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendInstructionBuffer.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/frontend/FrontendInstructionBufferSpec.scala`
- Previous pyCircuit owners:
  - `rtl/LinxCore/src/bcc/frontend/ibuffer.py`
  - `rtl/LinxCore/src/bcc/ifu/f3.py`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
- Contract IDs: `LC-IF-CHISEL-IB-001`

## Purpose

`FrontendInstructionBuffer` stores packet windows only for focused tests and is
excluded from the production graph. `InstructionBuffer` is the independent
queue after I-F4 and before D1; each row stores one 64-bit instruction
plus PC, original length, `BSTART`/`BSTOP`, fetch identity, epoch, and
prediction identity.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `push` | `FrontendDecodePacket` | `push.valid && pushReady` | Packet accepted from the test producer |
| output | `pushReady` | `Bool` | ready | High when the queue is not full and not being flushed |
| input | `popReady` | `Bool` | ready | Consumer readiness from the current fixture |
| output | `out` | `FrontendDecodePacket` | `out.valid && popReady` | Oldest buffered packet |
| output | `popFire` | `Bool` | fire | Oldest packet is consumed this cycle |
| input | `flushValid` | `Bool` | always sampled | Clears queue occupancy and masks visible output |
| output | `head` | `UInt(log2(depth).W)` | debug | Read pointer |
| output | `tail` | `UInt(log2(depth).W)` | debug | Write pointer |
| output | `count` | `UInt(log2(depth + 1).W)` | debug | Occupancy count |

The default depth is 8 entries and must remain a positive power of two.
`FrontendDecodePacket` identity includes `peId`, `threadId`, PC/window,
packet UID, and checkpoint ID; the FIFO stores and returns the whole record.

## State

- `entries[depth]`: buffered `FrontendDecodePacket` records.
- `head`: oldest valid packet pointer.
- `tail`: next allocation pointer.
- `count`: occupied entry count.

Reset clears `head`, `tail`, `count`, and every entry's `valid` bit.

## Logic Design

The module implements a single-push/single-pop FIFO:

- `pushReady` is high when `count < depth` and `flushValid` is low.
- A push writes the full `FrontendDecodePacket` at `tail` and advances `tail`.
- `out` reads the record at `head`; `out.valid` is additionally masked by
  `flushValid`.
- `popFire` is high when `out.valid && popReady`.
- A pop clears the old head entry valid bit and advances `head`.
- Simultaneous push and pop keep `count` stable.
- Full-state push is rejected even if the same cycle also pops. This matches
  the current conservative pyCircuit ready rule, where ready is based on the
  pre-cycle count.

The Chisel buffer deliberately stores `peId`, `threadId`, and `checkpointId`
with the packet. The current pyCircuit F3 queue stores PC/window/packet UID
and forwards some nearby control wiring, but the Chisel contract treats owner
and checkpoint identity as packet-owned at frontend ingress.

## Timing

`FrontendInstructionBuffer` is a registered packet FIFO and does not decode
opcodes or allocate backend resources. It feeds the verification-only
`F4DecodeWindow` fixture. Production uses `InstructionBuffer` with independent
64-bit instruction rows and four-entry D1 head access.

## Flush/Recovery

`flushValid` synchronously clears occupancy and masks visible output for the
cycle. A packet presented on `push` during a flush is not accepted because
`pushReady` is low. Future integration with recovery-to-I-F0 restart must own
restart PC and checkpoint selection outside this FIFO.

## Trace/Observability

The queue exposes `head`, `tail`, and `count` as debug outputs for early Chisel
bring-up. Stage-visible packet fields remain the `FrontendDecodePacket` fields;
slot-level pipeview metadata is produced by `F4DecodeWindow`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer`
- `bash tools/chisel/build_chisel.sh`

The focused tests cover FIFO ordering, packet identity retention, simultaneous
push/pop count behavior, full backpressure, flush clearing and output masking,
field widths, and Chisel elaboration.
