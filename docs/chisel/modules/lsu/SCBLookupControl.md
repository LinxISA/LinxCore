# SCBLookupControl

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBLookupControl.scala`
- Upstream selector: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEgressSelect.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/SCBLookupControlSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/l1/SCB.cpp`
  - `model/LinxCoreModel/model/l1/SCB.h`
  - `model/LinxCoreModel/model/l1/L1DCache.cpp`
  - `model/LinxCoreModel/model/l1/cluster.cpp`
  - `model/LinxCoreModel/model/core/Packet.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBEntryState.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala`
- Contract IDs: `LC-CHISEL-LSU-SCB-LOOKUP-001`

## Purpose

`SCBLookupControl` is the first Chisel owner for the abstract DCache/L2 outcome
after `SCBEgressSelect` chooses an SCB line. It consumes one lookup descriptor,
an abstract DCache result, and an L2 request-ready bit. It emits either:

- a DCache byte-update descriptor plus SCB free mask on a writable DCache hit,
  or
- an L2 ownership request descriptor plus SCB miss mask when the line is absent
  or present without write permission.

The module owns:

- the model `L1DCache::lookup(addr, write=true, &hit)` split between
  writable-line hit and tag hit,
- DCache hit update/free intent,
- upgrade request selection when the tag hit but the line is not writable,
- write request selection when the tag missed,
- model transaction tag encoding `(entryIndex << 2) | 2`.

It does not own actual DCache RAM mutation, L2/CHI queue storage, WriteResp
matching, SCB row mutation, SCB row free after a miss, MDB conflict prediction,
store-to-load forwarding, or final STQ free authorization.

## Interface

### Inputs

| Signal | Type | Description |
|---|---|---|
| `lookupRequest` | `SCBEgressLookupRequest` | Selected SCB line descriptor from `SCBEgressSelect`. |
| `dcacheReady` | `Bool` | DCache lookup port availability. |
| `dcacheWriteHit` | `Bool` | Model `lookup(..., write=true)` return value: tag exists and line has writable state. |
| `dcacheTagHit` | `Bool` | Model `phit`: tag exists even if line is not writable. |
| `l2RequestReady` | `Bool` | L2/CHI ownership request queue can accept a write or upgrade request. |

### Outputs

| Signal | Description |
|---|---|
| `lookupReady/lookupFire/lookupStall` | Handshake summary for the lookup-control boundary. |
| `acceptedMask` | One-hot SCB entry accepted from the selector. Future state owner moves this row out of `Valid` selection. |
| `freeMask` | Writable-hit row can be erased/freed after byte update intent is consumed. |
| `missMask` | Non-writable lookup row must remain resident in `Miss` until a WriteResp/UpgradeResp owner returns it to lookup. |
| `dcacheUpdate` | Entry index, line address, byte mask, 512-bit data, and broadcast-upgrade intent for writable hits. |
| `l2Request` | Entry index, line address, 64-byte size, encoded transaction id, and write/upgrade command bits. |

## State

`SCBLookupControl` is combinational. It emits state-transition masks but does
not mutate SCB entries. A later SCB state owner must apply:

- hit path: selected row leaves `Lookup`, updates DCache bytes, erases the
  combining map equivalent, and frees the entry,
- miss path: selected row becomes `Miss` after the ownership request is
  accepted,
- response path: WriteResp/UpgradeResp matching returns the row to `Lookup`
  before a second DCache lookup and eventual free.

## Logic Design

The model `SCBuffer::handleLookup()` calls
`dcache->lookup(blk_addr, true, &hit)`.

- If the return value is true, DCache already holds writable ownership. The
  model updates every valid byte with `dataUpdate`, erases the SCB combine-map
  entry, frees the SCB row, and calls `upgradeBroadcast` when at least one byte
  was updated.
- If the return value is false, the SCB row moves to `S_MISS` and
  `sendMemReq` emits either `Packet::createUpgrade(entryId)` when `hit` is
  true, or `Packet::CreateRWPkt(true, entryId)` when `hit` is false. The packet
  address is the line address, size is 64, and `tid` is `(entryId << 2) | 2`.

This Chisel packet preserves that split. A miss consumes the lookup descriptor
only when the L2 request queue is ready; a writable hit does not require L2
readiness. `dcacheReady` gates both paths.

## Timing

All outputs are combinational from the current lookup descriptor and abstract
DCache result. `lookupStall` holds the selected descriptor in the upstream
owner when DCache is unavailable or a miss cannot allocate an L2 request.

## Flush/Recovery

There is no flush input. SCB lookup control handles committed-store state.
Future global SCB invalidation or recovery-domain cleanup must be a separate
model-backed owner.

## Trace/Observability

`dcacheUpdate`, `l2Request`, `freeMask`, and `missMask` are local LSU
observability surfaces. They are not architectural commit rows and do not yet
participate in QEMU-vs-DUT trace comparison.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl`
- `bash tools/chisel/build_chisel.sh`
- `bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge`
- `bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused reference tests cover writable-hit update/free, tag-hit upgrade
request, tag-miss write request, L2 backpressure, DCache unavailability,
empty-byte hit free without broadcast, and Chisel elaboration.
