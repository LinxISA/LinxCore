# LinxCoreFrontendFetchTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendFetchTraceTopSpec.scala`
- Gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh`
- Verilator harness: `rtl/LinxCore/tools/chisel/frontend_fetch_trace_top_tb.cpp`
- Child owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendFetchPacketSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/interface/CommitInfo.h`
- Contract IDs: `LC-IF-CHISEL-FETCH-TOP-001`, `LC-IF-CHISEL-XCHK-010`

## Purpose

`LinxCoreFrontendFetchTraceTop` is the first generated-RTL top that removes
the testbench-owned `FrontendDecodePacket` fixture from the frontend
cross-check path. It starts from a PC, issues a one-outstanding fetch-window
request through `FrontendFetchPacketSource`, accepts a 64-bit instruction
window response, slices that window through `F4DecodeWindow`, allocates and
renames one reduced scalar row through `DecodeRenameROBPath`, then emits the
same monitored commit-row stream used by the neutral QEMU-shaped comparator.

This is still a bring-up trace top. It proves the live source-to-F4-to-ROB
handoff and the generated-RTL JSONL plumbing. It does not prove full ELF,
CoreMark, or QEMU equivalence because memory loading, multi-slot packet
retirement, real execute/LSU ownership, branch prediction, and recovery are
not all live behind this wrapper.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(64.W)` | valid | Arms the fetch source and resets packet UID allocation at the boot PC. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(64.W)` | valid | Redirects the fetch source, clearing outstanding request/packet state while preserving UID progression. |
| input | `frontendFlushValid` | `Bool` | valid | Clears source packet state plus F4/decode-path frontend state. |
| input | `peId`, `threadId` | `UInt(8.W)` | sideband | Scalar PE and STID/thread sidecars copied into fetched packets. |
| output | `fetchReqValid`, `fetchReqPc` | `Bool`, `UInt(64.W)` | `fetchReqReady` | Request for a 64-bit instruction window at the current PC. |
| input | `fetchReqReady` | `Bool` | ready | Bounded memory-window provider accepted the request. |
| input | `fetchRespValid`, `fetchRespWindow` | `Bool`, `UInt(64.W)` | `fetchRespReady` | Instruction-window response for the outstanding request. |
| output | `fetchRespReady` | `Bool` | ready | Source is waiting for the response and has no resident packet. |
| input | `completeValid`, `completeRobValue` | `Bool`, `UInt` | valid | Temporary completion surrogate for the selected ROB row. |
| input | `deallocReady` | `Bool` | ready | Allows committed rows to deallocate. |
| output | `source*` diagnostics | `Bool`/`UInt` | pulse/state | Source active, request/response/out fire, advance bytes, PC, and UID observability. |
| output | `f4ValidMask`, `f4SlotCount` | `UInt` | combinational | F4 slot visibility for the current fetched packet. |
| output | `decodeReady`, `selectedValid`, `selectedRobValue` | mixed | combinational | Downstream readiness and selected row identity used by the surrogate completion driver. |
| output | `decRen*`, `renamed*`, `rob*`, `complete*` | mixed | pulse/state | Decode-to-rename, rename, ROB allocation, ROB update, and completion observability. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit stream for JSONL export. |
| output | `commit*Error`, `commitMonitor*` | monitor flags | combinational | Commit-trace structural diagnostics. |
| output | `empty`, `full`, `size`, `outstandingCount`, masks | state | combinational | Integrated ROB state. |
| output | `idle` | `Bool` | combinational | ROB empty and no source response/packet resident. The source may still be armed. |

## State

The wrapper itself owns no architectural state. State lives in child owners:

- `FrontendFetchPacketSource` owns `currentPc`, one outstanding fetch request,
  the resident frontend packet, and packet UID allocation.
- `F4DecodeWindow` is combinational and computes slot lengths plus
  `totalLenBytes`.
- `DecodeRenameROBPath` owns decode-to-rename queueing, scalar/T-U rename
  composition, ROB/BROB allocation, commit, and deallocation state.

`idle` deliberately does not require the source to be inactive. The source
remains armed after `startValid`; harnesses hold `fetchReqReady` low when they
want one-row-at-a-time execution.

## Logic Design

The top wires the source output directly into `F4DecodeWindow`; source
`outReady` is the downstream `DecodeRenameROBPath.decodeReady`, and source
`advanceBytes` is `F4DecodeWindow.totalLenBytes`. This preserves the R93
contract that the source advances by the number of decoded bytes consumed from
the packet, with the source-local zero-advance fallback reserved only for
bring-up deadlock avoidance.

`DecodeRenameROBPath` receives the F4 D1 packet, slots, and valid mask. The
same reduced tie-offs as `LinxCoreFrontendTraceTop` remain in place for
checkpoint restore, backend cleanup, store execution, and map-commit update.
`completeValid/completeRobValue` remains an explicit external completion
surrogate, so this top can retire rows before the live execute/LSU path owns
all completion payloads.

The generated-RTL harness uses a bounded memory-window fixture. Each response
contains exactly one expected instruction followed by an 8-byte instruction
escape at the next byte position when space remains. That makes F4 expose one
valid slot per response while still proving that the Chisel source uses F4's
decoded byte count to request the next PC.

## Model Alignment

The C++ model path is:

1. `PEIFU::GenFetchReq` captures `fetchTPC`, block/thread identity, `fid`,
   first/last sidebands, and fetch mask into `FetchReqBus`.
2. `PEIFU::RunF0` moves run-ahead requests into the IFU pipe.
3. `PEIFU::RunF2` and `IFUICache::getCacheData` fill instruction data and
   instruction sizes.
4. `PEIFU::InsertToF4` copies filled request fields into `DecodeBundle`.
5. `PEIFU::InsertToF5` and `PEIFU::InsertToIB` move bundles under downstream
   backpressure.
6. The scalar decode/ROB path later allocates ROB identity and emits commit
   rows with model `CommitInfo(bid,gid,rid)` identity plus separate hardware
   block identity.

The Chisel top preserves the reduced form of those boundaries: one
request/response source, packet-owned PC/UID/checkpoint, F4 instruction sizing
using `CheckMInstSize` semantics, queue-backed decode/rename/ROB allocation,
and monitored commit rows with separate 32-bit model identity and 64-bit
`blockBid`.

## Timing

The source is single-outstanding. Request fire, response fire, source packet
acceptance, rename/ROB update, completion, commit, and deallocation happen in
separate harness-observable steps. F4 slot generation and decode selection are
combinational once the source has a resident packet.

The top supports backpressure at request acceptance, response acceptance, and
decode-path acceptance. It does not yet model IFU F0-F5 multi-stage timing,
multiple outstanding requests, RAHQ, or prefetch.

## Flush/Recovery

`frontendFlushValid` clears source wait/packet state and the F4/decode path.
`restartValid` redirects the source to `restartPc` while preserving packet UID
progression. Full model `flushFront`, BID/GID queue pruning, cacheline merge
cleanup, recovery-source selection, precise traps, and frontend restart-token
ownership are later IFU/recovery packets.

## Trace/Observability

`tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh` emits this top,
builds all generated sibling SystemVerilog files with Verilator, runs
`tools/chisel/frontend_fetch_trace_top_tb.cpp`, writes DUT and QEMU-shaped
JSONL through `tools/chisel/commit_trace_jsonl.h`, and compares the normalized
streams with `tools/chisel/run_chisel_qemu_crosscheck.sh`.

The R94 evidence bundle is:

- report directory:
  `generated/chisel-frontend-fetch-trace-top-xcheck/report`
- manifest:
  `generated/chisel-frontend-fetch-trace-top-xcheck/report/crosscheck_manifest.json`
- manifest summary: `status=pass`, `compared_rows=3`, `mismatch_count=0`

This is live frontend packet evidence. It is not full QEMU equivalence because
the response windows are still supplied by a bounded fixture and completion is
still an explicit surrogate.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchTraceTop
```

Generated-RTL cross-check:

```bash
bash tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh
```

Adjacent regressions for promotion:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

## Deferred Work

- Dense multi-slot fetched packets and multiple commit rows from one 8-byte
  response.
- Real ELF/image memory provider instead of the bounded C++ fixture.
- Live ALU/RF/issue/LSU completion behind the fetch source top.
- Fetch redirect/recovery ownership beyond the reduced restart input.
- Cacheline merge, branch prediction, RAHQ, and F0-F5 timing fidelity.
