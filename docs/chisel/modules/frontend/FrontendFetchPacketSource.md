# Frontend Fetch Packet Source

> **Architecture status — verification-only fixture.** This single-outstanding
> request/response source is excluded from production I-SIDE. Production uses
> `LinxCoreIfu` with I-F0–I-F4, parallel ITLB/L1I, exact transaction identity,
> and ITLB-miss inner flush.

## Source Mapping

- Chisel:
  [FrontendFetchPacketSource.scala](../../../../chisel/src/main/scala/linxcore/frontend/FrontendFetchPacketSource.scala)
- Focused reference and elaboration tests:
  [FrontendFetchPacketSourceSpec.scala](../../../../chisel/src/test/scala/linxcore/frontend/FrontendFetchPacketSourceSpec.scala)
- Generated-RTL probe:
  [frontend_fetch_packet_source_probe_tb.cpp](../../../../tools/chisel/frontend_fetch_packet_source_probe_tb.cpp)
- Downstream Chisel consumers:
  - [F4DecodeWindow.scala](../../../../chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala)
  - [FrontendDecodeIngress.scala](../../../../chisel/src/main/scala/linxcore/frontend/FrontendDecodeIngress.scala)
- LinxCoreModel evidence:
  - [pe_ifu.cpp](../../../../../../tools/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp)
  - [pe_ifu.h](../../../../../../tools/LinxCoreModel/model/pe/ifu/iside/pe_ifu.h)
  - [FetchReqBus.h](../../../../../../tools/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h)
  - [DecodeBundle.h](../../../../../../tools/LinxCoreModel/model/pe/PECommon/DecodeBundle.h)
  - [DecodeUtiles.h](../../../../../../tools/LinxCoreModel/isa/ISACommon/DecodeUtiles.h)
- Accepted contract evidence:
  - focused reference and Chisel elaboration suite;
  - generated-RTL restart/start/flush × same-cycle/delayed response matrix.
- Contract ID: `LC-IF-CHISEL-FETCH-PACKET-001`

## Purpose and Ownership Boundary

`FrontendFetchPacketSource` is the first Chisel owner that produces a live
`FrontendDecodePacket` from a PC request and a returned 64-bit instruction
window. It owns:

- one accepted request at a time;
- the obligation to drain exactly one response for that accepted request;
- packet UID and packet checkpoint assignment;
- one resident response packet;
- sequential PC advance after downstream packet acceptance; and
- restart, start, and flush control at this reduced frontend boundary.

The instruction-window provider has no cancellation acknowledgement or request
epoch. Therefore, once `reqValid && reqReady` fires, the source retains
response ownership until `respValid && respReady` fires. Restart, start, and
flush can make that response stale, but they cannot erase the obligation to
accept and discard it.

This module is a reduced frontend transport owner. It does not implement the
production I-F0–I-F4 engine, RAHQ, cacheline merge, prediction, or
block-control fetch queues. Its unit and generated-RTL probes are module
evidence, not natural benchmark-completion evidence.

## Interface

| Direction | Signal | Type | Contract |
|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(pcWidth.W)` | Re-arm at `startPc`. Start resets the next packet UID to zero unless restart is asserted in the same cycle. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(pcWidth.W)` | Re-arm at `restartPc` while preserving packet UID progression. Restart wins over simultaneous start. |
| input | `flushValid` | `Bool` | Drop any resident packet and leave the source inactive unless start or restart is also asserted. An accepted request still has to drain. |
| input | `peId`, `threadId` | parameterized `UInt` | Sidecars sampled when a request fires and copied into its packet. |
| output | `reqValid`, `reqPc` | `Bool`, `UInt(pcWidth.W)` | Request for the current PC. Valid only when active, with no outstanding response, no resident packet, and no control input. |
| input | `reqReady` | `Bool` | Request-provider acceptance. |
| input | `respValid`, `respWindow` | `Bool`, `UInt(windowWidth.W)` | Response for the single accepted request. |
| output | `respReady` | `Bool` | Asserted while an accepted response is outstanding and no packet is resident, including restart/start/flush cycles and delayed stale-response hold cycles. |
| output | `out` | `FrontendDecodePacket` | Resident packet for the window-decoder test fixture. Control masks `out.valid`. |
| input | `outReady` | `Bool` | Downstream packet acceptance. |
| input | `advanceBytes` | `UInt(4.W)` | Bytes consumed from the packet. Zero raises `advanceZero` and uses the 8-byte window fallback. |
| output | `active` | `Bool` | Source is armed for a future request once retained ownership is clear. |
| output | `waitingResponse` | `Bool` | One accepted request still owns one response. |
| output | `packetValid` | `Bool` | A returned, non-stale packet is resident. |
| output | `reqFire`, `respFire`, `outFire` | `Bool` | Request, response, and packet handshakes. |
| output | `advanceZero` | `Bool` | A packet was accepted with zero `advanceBytes`; the source applies the 8-byte fallback. |
| output | `currentPc`, `issuedPc`, `nextPktUid` | parameterized `UInt` | Current request PC, accepted-request PC, and UID for the next request. |

`discardResponseReg` is private state. It is intentionally not a public
interface: consumers observe the retained obligation through
`waitingResponse`, `respReady`, and the absence of a packet or replacement
request.

## State

| State | Owner meaning | Set by | Cleared or replaced by |
|---|---|---|---|
| `activeReg` | Source may request when all retained work is clear. | start or restart | plain flush |
| `currentPcReg` | PC of the next legal request. | start/restart, or accepted packet advance | later start/restart or packet advance |
| `waitingResponseReg` | Exactly one accepted request still owns a response. | request fire | normal response fire or stale-response drain |
| `discardResponseReg` | The outstanding response became stale and must not become a packet. | control while a response remains outstanding | stale-response fire |
| `issuedPcReg` | PC sampled for the accepted request. | request fire | next request fire |
| `issuedUidReg` | UID sampled for the accepted request. | request fire | next request fire |
| `issuedPeIdReg`, `issuedThreadIdReg` | PE/STID sidecars sampled for the accepted request. | request fire | next request fire |
| `nextPktUidReg` | UID assigned to the next request. | reset/start, incremented on request fire | later start or request fire |
| `packetReg` | One returned, non-stale `FrontendDecodePacket`. | normal response fire | packet fire or any control |

The following invariants are normative:

1. An accepted request owns exactly one future response handshake.
2. `discardResponseReg` implies retained response ownership.
3. No replacement request is valid while `waitingResponseReg` or
   `packetReg.valid` is set.
4. `respReady` remains asserted for an outstanding response even when control
   makes it stale.
5. A stale response is consumed exactly once and never packetized.
6. A response without outstanding ownership sees `respReady=0` and cannot
   mutate state.
7. At most one of response ownership and resident packet ownership is active
   after a clock edge.

## Normal Request, Response, and Packet Flow

1. Start or restart arms the source and selects `currentPcReg`.
2. When active with no retained response or packet, `reqValid` presents
   `currentPcReg`.
3. Request fire captures PC, UID, PE ID, and thread ID, increments
   `nextPktUidReg`, and sets `waitingResponseReg`.
4. A non-stale response fire clears response ownership and creates
   `packetReg`. `checkpointId` is the low `checkpointWidth` bits of the
   request-owned UID.
5. The packet remains resident under downstream backpressure.
6. Packet fire clears the packet and advances
   `currentPcReg = packet.pc + advanceBytes`. A zero advance uses
   `F4DecodeWindow.WindowBytes` and asserts `advanceZero`.

There is no empty-packet bypass: response fire creates registered packet state,
and the packet becomes visible on the following cycle. Similarly, packet fire
does not issue the next request in the same cycle.

## Control and Response Priority

Control is `flushValid || restartValid || startValid`. Restart and start both
re-arm the source; restart has PC and UID-policy priority when both are high.

| Pre-cycle state and inputs | Same-cycle handshakes | Post-cycle state |
|---|---|---|
| No outstanding response; start | no request | active at `startPc`; next UID is zero |
| No outstanding response; restart | no request | active at `restartPc`; next UID is preserved |
| No outstanding response; plain flush | no request | inactive; resident packet cleared |
| Outstanding response; control; no response | `respReady=1`, no `respFire`, no request | keep `waitingResponse`; set discard intent; apply restart/start/flush active, PC, and UID policy |
| Outstanding response; control and response | `respReady=1`, exactly one `respFire`, no request | response is discarded without packetization; ownership clears; control active, PC, and UID policy wins |
| Outstanding stale response; later response | exactly one `respFire`, no request that cycle | clear waiting and discard state; create no packet |
| Restart/start after stale drain | request may fire only on a later cycle | exactly one request uses the selected PC and UID policy |
| Plain flush after stale drain | no request | source remains inactive until a later start or restart |
| Resident packet and any control | no packet fire from the masked output | resident packet is dropped; apply control policy |

`respReady` and `respFire` derive from pre-cycle response ownership. Therefore,
control can win next-state priority while the same cycle drains and discards
the owned response without packetizing it. Without a response, repeated
controls may update the eventual active state, selected PC, and UID policy, but
cannot remove ownership or create a replacement request.

## Hazards and Recovery

| Hazard | Required behavior and recovery |
|---|---|
| Accepted response has no cancellation channel or epoch | Never clear `waitingResponseReg` without `respFire`; retain discard intent and suppress replacement requests so the old response cannot be assigned new PC, UID, or sidecars. |
| Stale response is delayed | Hold `respReady=1`, `reqValid=0`, and no packet for arbitrarily many cycles. The selected restart/start PC and UID policy take effect on the first legal post-drain request. |
| Completed packet is backpressured | Hold the packet until `outReady` and suppress requests. Control may drop it because its upstream response has already handshaken. |
| Plain flush | Leave the source inactive without resetting UID state. A later start resets UID allocation; a later restart preserves it. |

Full model recovery-token sequencing, scoped frontend pruning, and R4-to-F0
restart transport are outside this module.

## LinxCoreModel Alignment

The model flow behind this owner is:

- `PEIFU::GenFetchReq` captures fetch PC and request identity into
  `FetchReqBus`;
- `PEIFU::RunF0` advances accepted requests into the IFU pipe;
- `IFUICache::getCacheData` returns instruction bytes and instruction-size
  metadata;
- `PEIFU::InsertToF4` constructs a `DecodeBundle`; and
- `PEIFU::InsertToF5` / `PEIFU::InsertToIB` retain bundles under downstream
  backpressure.

The Chisel source preserves the corresponding ownership principles:

- request identity is sampled at request acceptance;
- returned data is associated with that accepted identity;
- downstream backpressure retains the completed packet; and
- recovery cannot silently reassign an old response to a new request.

The Chisel one-outstanding shape is intentionally narrower than the model's
multi-stage, multi-entry IFU. `discardResponseReg` is a reduced transport
mechanism required by the provider interface, not a claim that the model uses
the same register encoding.

## Parameters and Elaboration Constraints

All widths come from `InterfaceParams` except `advanceBytes`, which is fixed at
4 bits.

- `windowWidth` must be 64.
- `checkpointWidth` must not exceed `uopUidWidth`.
- `pcWidth` sizes start, restart, request, and packet PCs.
- `peIdWidth` and `threadIdWidth` size request-owned sidecars.
- `uopUidWidth` sizes packet UID allocation.
- `checkpointWidth` selects the low UID bits carried as packet checkpoint ID.
- The implementation supports exactly one outstanding request and one resident
  packet; these are architectural module-shape limits, not configurable queue
  depths.

## Verification

Focused reference and elaboration gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource
```

The accepted focused suite contains eleven cases covering normal
request/response packetization, packet backpressure, decoded-byte advance,
same-cycle and delayed restart/start/flush response drain, repeated stale hold,
restart-over-start priority, UID policy, interface widths, and elaboration.

Generated-RTL response/control matrix:

```bash
bash tools/chisel/run_chisel_frontend_fetch_packet_source_probe.sh
```

The probe must end with:

```text
frontend-fetch-packet-source-probe: PASS
```

Adjacent integration gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
bash tools/chisel/run_chisel_tests.sh --only LinxCoreBenchmarkAutonomousTop
```

Documentation and whitespace checks that do not elaborate RTL:

```bash
rg -n "response ownership|respReady|restart wins|stale response" \
  docs/chisel/modules/frontend/FrontendFetchPacketSource.md
git diff --check -- docs/chisel/modules/frontend/FrontendFetchPacketSource.md
```

Natural Dhrystone or CoreMark runs are downstream system-integration gates.
They must not be claimed as passing from this module's focused tests or probe.

## Deferred Work

- Multiple outstanding fetches with explicit request/response transaction
  identity.
- A provider cancellation acknowledgement, if the instruction-memory
  interface later defines one.
- This fixture will remain outside I-F0–I-F4 timing, RAHQ, prefetch,
  cacheline-merge, and instruction-cache ownership.
- First/last block sidebands and BFU/SP prediction.
- Registered R4 recovery-token delivery to canonical I-F0.
- Full natural workload completion through decode, ROB, issue, execute, LSU,
  recovery, and retirement.
