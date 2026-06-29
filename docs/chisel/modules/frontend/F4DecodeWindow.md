# F4DecodeWindow

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/frontend/F4DecodeWindowSpec.scala`
- Previous pyCircuit owners:
  - `rtl/LinxCore/src/bcc/ifu/f4.py`
  - `rtl/LinxCore/src/bcc/ifu/f2.py`
  - `rtl/LinxCore/src/common/decode.py`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/pe/ifu/iside/ifu_icache.cpp`
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
- Contract IDs: `LC-IF-CHISEL-F4-001`

## Purpose

`F4DecodeWindow` is the first Chisel frontend/decode handoff module. It accepts
the shared F4 packet, applies flush masking, and slices one 64-bit fetch window
into up to four sequential instruction slots without compaction. It does not
perform full opcode decode yet; it owns only instruction length, slot offset,
slot PC, raw instruction payload, and packet-derived uop UID metadata.

## Interface

### `F4DecodeWindowIO`

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `in` | `FrontendDecodePacket` | `in.valid` | F4 packet containing `peId`, `threadId`, `pc`, 64-bit `window`, packet UID, and checkpoint ID |
| input | `flushValid` | `Bool` | always sampled | Suppresses D1 visibility and all slot-valid bits for the current packet |
| output | `d1` | `FrontendDecodePacket` | `d1.valid` | Pass-through D1 packet with `valid = in.valid && !flushValid` |
| output | `slots[4]` | `F4Slot` | `slots(i).valid` | Per-slot decoded length/offset/PC/raw/UID metadata |
| output | `validMask` | `UInt(4.W)` | bit mask | Slot valid mask with slot 0 in bit 0 |
| output | `slotCount` | `UInt(3.W)` | derived | Popcount of `validMask` |
| output | `totalLenBytes` | `UInt(4.W)` | derived | Byte span consumed by the valid slots in the 8-byte window |

### `F4Slot`

| Field | Width | Description |
|---|---:|---|
| `valid` | 1 | Slot contains a complete instruction in the current 8-byte window |
| `pc` | 64 | `in.pc + offsetBytes` when valid, otherwise zero |
| `offsetBytes` | 4 | Byte offset inside the 8-byte window when valid, otherwise zero |
| `lenBytes` | 4 | Instruction length: 2, 4, 6, or 8 bytes when valid |
| `insnRaw` | 64 | Right-shifted and length-masked instruction payload when valid |
| `uopUid` | 64 | `(in.pktUid << 3) | slot` when valid |

## State

The module is combinational. F4 packet latching, IB residency, D1/D2 decode
state, and retry/restart sequencing remain owned by later frontend and decode
modules.

## Logic Design

Instruction length follows the LinxCoreModel `CheckMInstSize` rule:

- if bit 0 is `0` and bits `[3:1]` are not `111`, length is 2 bytes;
- if bit 0 is `0` and bits `[3:1]` are `111`, length is 6 bytes;
- if bit 0 is `1` and bits `[3:1]` are not `111`, length is 4 bytes;
- if bit 0 is `1` and bits `[3:1]` are `111`, length is 8 bytes.

Slot 0 starts at byte offset 0. Each later slot starts at the previous valid
slot's end offset. A slot is valid only when all earlier slots are valid and its
complete instruction fits in the 8-byte window. If a candidate instruction does
not fit, that slot and all later slots remain invalid; no later instruction is
compacted forward.

Full opcode decode, register extraction, immediate construction, macro-boundary
standalone behavior, and D1/D2 uop construction are intentionally deferred until
the Chisel opcode table and decode-owner modules exist.

## Timing

`F4DecodeWindow` is a combinational F4-to-D1 helper. It is safe to instantiate
inside a future registered F4 stage or an F4/D1 boundary wrapper, but it must
not absorb F0/F1/F2/F3 fetch ownership, IB queue state, or D1 opcode-decode
state.

## Flush/Recovery

`flushValid` clears `d1.valid`, `validMask`, `slotCount`, `totalLenBytes`, and
all slot-valid bits. Sideband fields in `d1` pass through so downstream
registered stages can retain packet metadata while masking visibility.

## Trace/Observability

The derived slot PC and packet-based UID seed later pipeview and LinxTrace
visibility:

- `slot.pc = in.pc + slot.offsetBytes`
- `slot.uopUid = (in.pktUid << 3) | slot`

These identifiers are not commit identity. Commit comparison still uses
`CommitTraceRow` and `tools/chisel/trace_schema_adapter.py`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/build_chisel.sh`

The focused test locks the LinxCoreModel length rule, four 16-bit slots, mixed
32/16-bit slots, 48/16-bit slots, single 64-bit instruction windows, truncated
candidate rejection, flush masking, slot field widths, and Chisel elaboration.
