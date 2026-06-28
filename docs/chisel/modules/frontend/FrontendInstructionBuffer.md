# FrontendInstructionBuffer

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

`FrontendInstructionBuffer` is the first stateful frontend Chisel module. It
stores fetched F4/D1 packets between F3/F4 production and backend/decode
consumption. The buffer preserves packet PC, 64-bit decode window, packet UID,
and fetch checkpoint identity as one record so later F4/D1/D2 work does not
reconstruct checkpoint state from external timing.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `push` | `FrontendDecodePacket` | `push.valid && pushReady` | Packet accepted from F3/F4 frontend production |
| output | `pushReady` | `Bool` | ready | High when the queue is not full and not being flushed |
| input | `popReady` | `Bool` | ready | Consumer readiness from F4/D1/decode side |
| output | `out` | `FrontendDecodePacket` | `out.valid && popReady` | Oldest buffered packet |
| output | `popFire` | `Bool` | fire | Oldest packet is consumed this cycle |
| input | `flushValid` | `Bool` | always sampled | Clears queue occupancy and masks visible output |
| output | `head` | `UInt(log2(depth).W)` | debug | Read pointer |
| output | `tail` | `UInt(log2(depth).W)` | debug | Write pointer |
| output | `count` | `UInt(log2(depth + 1).W)` | debug | Occupancy count |

The default depth is 8 entries and must remain a positive power of two.

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

The Chisel buffer deliberately stores `checkpointId` with the packet. The
current pyCircuit F3 queue stores PC/window/packet UID and forwards checkpoint
from nearby control wiring, but the Chisel contract treats checkpoint identity
as packet-owned at frontend ingress.

## Timing

`FrontendInstructionBuffer` is a registered F3/F4-owned queue. It does not
decode opcodes, allocate ROB/BROB IDs, or build D1/D2 uops. It is intended to
feed `F4DecodeWindow` or a future registered F4/D1 transport wrapper.

## Flush/Recovery

`flushValid` synchronously clears occupancy and masks visible output for the
cycle. A packet presented on `push` during a flush is not accepted because
`pushReady` is low. Future integration with `FLS -> F0` restart must still own
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
