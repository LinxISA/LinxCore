# Frontend Fetch Packet Source

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendFetchPacketSource.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/frontend/FrontendFetchPacketSourceSpec.scala`
- Downstream Chisel consumers:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendDecodeIngress.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
- Contract IDs: `LC-IF-CHISEL-FETCH-PACKET-001`

## Purpose

`FrontendFetchPacketSource` is the first Chisel owner that produces a live
`FrontendDecodePacket` rather than relying on a testbench to drive packet
fields directly. It owns the reduced request/response boundary between an
instruction-window provider and the existing F4 decode-window path.

This packet is an RTL substrate for later QEMU-vs-DUT work. It is not full
CoreMark or QEMU equivalence by itself because it does not yet load ELF memory,
model branch prediction, generate block-control fetch queues, or retire commit
rows.

## Interface

| Direction | Signal | Type | Purpose |
|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(64.W)` | Boot or re-arm the source and reset packet UID allocation. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(64.W)` | Redirect/recovery restart. Clears pending request and packet state but preserves packet UID progression. |
| input | `flushValid` | `Bool` | Clears pending request and packet state; without `startValid` or `restartValid`, disables the source. |
| input | `peId`, `threadId` | `UInt(8.W)` | Request-owned scalar PE and STID/thread sidecars copied into the emitted packet. |
| output | `reqValid`, `reqPc` | `Bool`, `UInt(64.W)` | In-order one-outstanding request for a 64-bit instruction window. |
| input | `reqReady` | `Bool` | Window provider accepted the request. |
| input | `respValid`, `respWindow` | `Bool`, `UInt(64.W)` | Window provider returned the requested 64-bit instruction window. |
| output | `respReady` | `Bool` | Source is waiting for the outstanding response and has no resident packet. |
| output | `out` | `FrontendDecodePacket` | Packet consumed by `F4DecodeWindow` or `FrontendDecodeIngress`. |
| input | `outReady` | `Bool` | Downstream accepted the packet. |
| input | `advanceBytes` | `UInt(4.W)` | Number of decoded bytes consumed from the packet, normally `F4DecodeWindow.totalLenBytes`. |
| output | diagnostics | `Bool`/`UInt` | `active`, `waitingResponse`, `packetValid`, fire bits, `advanceZero`, `currentPc`, `issuedPc`, `nextPktUid`. |

## Logic Design

The model flow behind this owner is:

- `PEIFU::GenFetchReq` picks a thread, captures `fetchTPC`, `bpc`, BID/GID,
  STID, `fid`, and first/last sidebands into `FetchReqBus`.
- `PEIFU::RunF0` moves requests from the run-ahead queue into the IFU pipe.
- `IFUICache::getCacheData` fills `FetchReqBus::data`, `isize`, and `tpcArr`,
  using `CheckMInstSize` for 2/4/6/8-byte instruction length.
- `PEIFU::InsertToF4` converts the filled request into a `DecodeBundle`.
- `PEIFU::InsertToF5` and `PEIFU::InsertToIB` move bundles under downstream
  buffer backpressure.

The Chisel owner implements the corresponding reduced shape:

1. `startValid` arms the source at `startPc` and resets `nextPktUid`.
2. With no resident packet and no outstanding response, `reqValid` presents
   `currentPc`.
3. On `reqValid && reqReady`, the source records `issuedPc`, `pktUid`, `peId`,
   and `threadId`, then waits for one in-order response.
4. On `respValid && respReady`, the source creates a resident
   `FrontendDecodePacket` with the returned 64-bit `respWindow`.
5. The packet remains valid until `outReady`.
6. On packet acceptance, `currentPc` advances by `advanceBytes`; a zero
   `advanceBytes` raises `advanceZero` and falls back to the 8-byte window size
   to prevent source deadlock during bring-up.

`checkpointId` is currently derived from the low bits of the packet UID. This
keeps checkpoint identity packet-owned at the source boundary, matching the
existing `FrontendDecodePacket` contract. Backend-visible start-marker
checkpoint remapping remains a later recovery/decode owner.

## Timing

The module is intentionally single-outstanding:

- request fire and response fire cannot occur in the same cycle;
- response fire creates a resident packet that becomes visible on the next
  cycle;
- packet fire clears the resident packet and updates `currentPc` for the next
  request.

This is narrower than the full model IFU, whose F0/F1/F2/F3 stages, RAHQ,
prefetch queues, and F4/F5 buffers can hold multiple requests. The one-request
shape is sufficient for the next Chisel top owner to replace testbench-driven
frontend packets without also implementing cacheline merge or branch prediction.

## Flush/Recovery

`flushValid` clears outstanding response and resident packet state. If paired
with `restartValid`, the source immediately restarts at `restartPc` and
preserves `nextPktUid`, matching the model pattern where recovery prunes
wrong-path fetch state but does not turn dynamic fetch identity into a reused
fixture value. A plain `startValid` re-arms the source and resets packet UID
allocation.

Full model `setFlush`, `flushFront`, `FlushGidFront`, block-control queue
rebasing, cacheline merge reset, and frontend restart-token ownership remain
future IFU/recovery packets.

## Trace/Observability

Diagnostics expose request, response, and packet fire bits plus the source PC
and next packet UID. They are for RTL/top harness visibility only. Architectural
QEMU comparison must still happen through committed rows emitted by a downstream
top and normalized by `tools/chisel/trace_schema_adapter.py`.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource
```

Adjacent gates before top integration:

```bash
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The focused tests cover in-order request/response packetization, backpressure,
decoded-byte PC advance, restart versus start UID behavior, flush-disable
behavior, interface widths, and Chisel elaboration.

## Deferred Work

- ELF/image memory loading and byte extraction.
- Cacheline boundary merge for instructions spanning an 8-byte window.
- Multiple outstanding fetches, RAHQ, F0-F3 timing, and prefetch.
- First/last block sidebands and BFU/SP branch prediction.
- A top-level live DUT JSONL writer that connects this packet source through
  F4/decode/rename/issue/execute/ROB retirement.
