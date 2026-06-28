# SCBResponseDecode

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseDecode.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBResponseDecodeSpec.scala`
- Downstream state owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBStateUpdate.scala`
- Upstream queue owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBResponseBuffer.scala`
- Composition owner:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
    - `SCBuffer::sendMemReq`
    - `SCBuffer::setMemResp`
    - `SCBuffer::handleMemResp`
  - `model/LinxCoreModel/model/l1/SCB.h`
- Contract IDs: `LC-CHISEL-LSU-SCB-RESP-DECODE-001`

## Purpose

`SCBResponseDecode` is the raw response-tag owner for SCB WriteResp and
UpgradeResp completion. The LinxCoreModel sends memory ownership requests with
`pkt->tid = (entryIndex << 2) | 2`; the later response path must recover the
SCB row id before `SCBuffer::handleMemResp()` moves that row from `S_MISS` to
`S_LOOKUP`.

The module owns:

- model response tag recognition for `(entryIndex << 2) | 2`,
- WriteResp versus UpgradeResp type validation,
- non-power-of-two SCB index range checking,
- stale target detection when the decoded row is not a valid `Miss`,
- conversion from a raw response to `SCBStateUpdate.memResp*` inputs.

It does not own CHI/L2 queue storage, response FIFO state, DCache RAM
mutation, MDB conflict policy, store-to-load forwarding, or the final SCB row
register.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `entries` | `Vec[SCBLineEntry]` | Current SCB row image used to validate the decoded target. |
| `rawValid` | `Bool` | One raw response is present. |
| `rawTxnId` | `UInt(log2Ceil(scbEntries)+2)` | Raw model transaction id carrying `(entryIndex << 2) | 2`. |
| `rawWriteResp` | `Bool` | Response is a write-ownership completion. |
| `rawUpgradeResp` | `Bool` | Response is an upgrade-ownership completion. |

### Outputs

| Signal | Description |
|---|---|
| `memRespValid/memRespEntryIndex` | Legal decoded response for `SCBStateUpdate`. |
| `decodedMask` | One-hot decoded row mask for legal tag/type/index candidates. |
| `tagMatch` | Raw transaction id uses the SCB response low-bit tag `2`. |
| `responseTypeValid` | Exactly one of WriteResp or UpgradeResp is asserted. |
| `indexInRange` | Decoded entry index names an implemented SCB row. |
| `targetMiss` | Decoded target row is valid and in `SCBEntryState.Miss`. |
| `typeIllegal/tagIllegal/indexIllegal` | Response type, tag, or index rejection flags. |
| `stateIllegalMask` | Decoded in-range response targeted a row outside valid `Miss`. |
| `illegal` | Combined response decode error. |

## State

The module is combinational. `SCBRowBank` stores the next row image after
`SCBStateUpdate` consumes the legal decoded response.

## Logic Design

The model flow is:

1. `SCBuffer::sendMemReq()` emits a write or upgrade request and tags it as
   `(entryIndex << 2) | 2`.
2. The memory response path calls `SCBuffer::setMemResp()` with a decoded SCB
   row id.
3. `SCBuffer::handleMemResp()` asserts that row is `S_MISS`, moves it to
   `S_LOOKUP`, and queues it for another lookup attempt.

`SCBResponseDecode` maps that software assertion boundary into RTL error
surfaces. A response is accepted only when it is valid, has exactly one
supported response type, matches the SCB response tag namespace, decodes to an
implemented row, and targets a valid `Miss` row. Legal responses drive
`memRespValid` and `memRespEntryIndex`; every other raw response is suppressed
from `SCBStateUpdate` and reported through the illegal flags.

## Timing

The decode is combinational over the head of `SCBResponseBuffer` in the same
abstract cycle as `SCBRowBank` state update. Illegal heads are not consumed by
the buffer, so stale-target reporting remains visible until the error is
handled by a later composition owner.

## Flush/Recovery

There is no flush input. SCB responses target committed-store rows. Future
global SCB invalidation or nuke behavior must add a separate owner that defines
how in-flight raw responses are dropped or reported.

## Trace/Observability

The decoded mask and illegal flags are LSU-local debug surfaces. They are not
architectural trace rows and do not participate in QEMU comparison until the
full top emits live memory-event trace payloads.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode`
- `bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer`
- `bash tools/chisel/run_chisel_tests.sh --only SCBRowBank`
- `bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_verilator_lint.sh`

Focused reference tests cover legal WriteResp, legal UpgradeResp, wrong
low-bit tag, absent or ambiguous response type, out-of-range index, stale
non-`Miss` target, and Chisel elaboration with raw and decoded response
signals.
