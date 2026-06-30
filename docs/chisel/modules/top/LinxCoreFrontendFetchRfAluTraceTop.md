# LinxCoreFrontendFetchRfAluTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTopSpec.scala`
- Verilator driver: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- Live QEMU ELF gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`
- Live QEMU fixture builder: `rtl/LinxCore/tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh`
- Fixture memory helper: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_fixture_memory.py`
- Fixture expected-row helper: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_fixture_rows.py`
- QEMU expected-row helper: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_qemu_rows.py`
- ELF memory helper: `rtl/LinxCore/tools/chisel/frontend_fetch_elf_memory.py`
- Child owners:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/FrontendFetchPacketSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DenseSlotQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarRegisterFile.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssuePick.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
- Contract IDs: `LC-IF-CHISEL-TOP-006`, `LC-IF-CHISEL-XCHK-011`

## Purpose

`LinxCoreFrontendFetchRfAluTraceTop` is the R95 live-source successor to
`LinxCoreFrontendRfAluTraceTop`. It removes the testbench-owned
`FrontendDecodePacket` fixture from the RF-backed scalar path. A Chisel
`FrontendFetchPacketSource` now owns PC request/response packetization, feeds
F4, and advances its PC from F4's decoded byte count. The decoded row then
passes through reduced decode/rename/ROB allocation, reduced scalar issue,
RF-backed source reads, reduced ALU execute, RF writeback, and monitored ROB
commit.

The top proves one more replacement step on the CoreMark path: the harness now
serves instruction bytes through reusable binary or sparse fetch-memory image
paths, and the frontend packet row is created by Chisel logic. R97 adds a
little-endian ELF64 PT_LOAD extractor that writes a sparse address-to-byte
image for high-address or non-contiguous program segments. R98 moves the
expected PC/instruction/source/writeback rows out of the C++ harness and into a
QEMU-shaped JSONL file selected by `FETCH_EXPECTED_ROWS` or the default
`fixture.expected.jsonl`. R99 adds `FETCH_QEMU_TRACE`, which normalizes an
existing QEMU commit JSONL and extracts a strict sequential reduced-scalar
prefix into `qemu.expected.jsonl`. R100 adds a live direct-boot ELF wrapper
that captures a bounded QEMU commit prefix, validates the selected rows through
that same R99 reducer, and pairs the rows with the ELF sparse fetch-memory path
for matching PC/instruction bytes. R101 lets the same live ELF gate run without
PC filters for the legal entry and exit block markers: `C.BSTART` and
`C.BSTOP` rows are preserved in the expected stream as DUT-only skip rows,
consumed by the reduced `DecodeRenameROBPath`, and omitted from the
architectural QEMU/DUT commit comparison. R102 replaces the single-instruction
response terminator with a full 8-byte F4 response window and inserts
`F4DenseSlotQueue` between F4 and the serialized decode/ROB path. One source
response can now carry a mixed marker/scalar dense packet, while the reduced
backend still drains one original F4 slot per cycle. Block-header scalar-done
semantics, width-wide ROB allocation, full issue arbitration, LSU,
trap/recovery, and branch restart are still outside this reduced top.
R103 propagates the reduced ROB block-last to BROB lifecycle diagnostics to
this top: full block BID on the deallocated block-last row, scalar-done pulse,
and one-cycle-later block-retire pulse. These diagnostics prove the current
ROB/BROB sideband path, not full `BSTART`/`BSTOP` marker retirement.
R104 extends the same live fetch RF/ALU gate with reduced marker-owned block
lifecycle. `BSTART` skip rows allocate BROB-only active BIDs, scalar rows reuse
that active BID, and `BSTOP` skip rows complete and retire the current active
BID. This remains marker-consume timing, not full marker ROB retirement or
recovery-exact block control.
R107 adds the reduced direct-call header restart path: target-bearing
`BSTART` skip rows carry `boundaryTarget`, `BSTOP` skip rows can publish a
marker-stop redirect PC, and the top restarts the frontend source to that
target while flushing only the reduced frontend slot path. This is enough for
CoreMark's first `HL.BSTART.STD CALL`/`C.SETRET`/`C.BSTOP` prefix; it is not a
general branch, trap, or recovery redirect owner.
R111 adds the first reduced local T/U source value bridge needed by CoreMark's
`SLL` row after `HL.LUI`. T and U destination rows still avoid scalar RF
writeback, but their produced values are recorded in a small local overlay so a
later T/U-source row can issue and compare against QEMU. This overlay is a
temporary live-gate bridge until full local-bank data execution owns those
values.
R112 extends that bridge through the next same-window shift rows: one `SLL`
writes T0, then `SRL` reads local T/U sources and writes T0. The dense frontend
must capture both scalar slots when they share one 8-byte response window.
R113 extends the same reduced prefix through `OR` on local T/U sources and a
single zero-data `C.LDI` load sideband. The load support is only a bounded
CoreMark prefix bridge; it is not a data-memory or LSU implementation.
R114 adds the following compressed `C.ADD` local-source row, still using the
reduced local-value overlay and still suppressing local source fields in the
QEMU-shaped completion row. The expected-row reducer synthesizes the implicit
T writeback for this one row because current QEMU commit JSONL omits local
destination/writeback fields for `C.ADD`.
R115 extends the same dense packet through `OP_SRA` and `OP_SLLI`. The top now
stalls younger rename output while any reduced local T/U producer is pending,
so same-packet local consumers wait for the producer to complete before reading
the temporary local overlay.
R116 extends the next dense packet through mixed local/scalar `C.ADD`,
local-source `ADDI`, and scalar `C.MOVR`, with expected-row validation choosing
scalar or suppressed local source handling per encoded operand instead of per
opcode.
R117 extends through the following marker and scalar/local packet: `C.MOVR`
writes T tag `31`, local-source `ADDI` writes scalar x2, `C.LDI` reads local
T0 and uses the scaled doubleword byte offset from frontend decode, and
`C.SETC_NE` validates a local/scalar compare row without emitting writeback.
The top also exposes deeper T/U rename and retire-command diagnostics so the
live harness can distinguish active-bank allocation pressure from stale
retire-command terminal responses.
R118 extends through the first ordinary scalar `SDI` store-immediate sideband.
R119 extends through the first conditional loop edge: the ALU publishes the
`C.SETC_NE` branch decision, the top latches it until the following conditional
marker boundary, and the backend either allocates the fallthrough marker block
or redirects to the active block target without allocating a new marker block.
R120 carries the same loop through repeated direct-call target body iterations.
The backend now keeps scalar-created blocks active when the visible target body
has no target `BSTART` marker, and this reduced top opts into
`DecodeRenameROBPath.reducedStoreDispatchBypass` because stores are compared
from the ALU-produced sideband while STA/STD execution and STQ commit/free
feedback are not connected here.
R121 carries the loop through a 256-row live QEMU capture. It adds the 32-bit
`OP_LDI` zero-load sideband row, compact `OP_C_SETC_EQ` equality branch
sideband, and a Verilator harness tail-prefix rule: bounded captures may end
inside an 8-byte F4 window, so the harness can accept a DUT dense-packet
superset only for the final captured expected row while still comparing every
committed row in the captured prefix.
R122 replaces the zero-only `OP_LDI` shortcut with a reduced read-only load
lookup from the same sparse ELF image used for instruction fetch. The top
threads `loadLookupValid/loadLookupAddr/loadLookupData` between
`ReducedScalarAluExecute` and the Verilator harness, letting CoreMark loads
return nonzero ELF data without claiming an LSU, cache, store queue, or memory
mutation owner. The expected-row reducer also admits `OP_SETC_LTU` as a
no-writeback unsigned condition sideband and recognizes the next local-alias
ADDI/LDI/C.MOVR row shapes. The live proof is bounded before the next
redirecting `C.BSTART`: the 470-raw-row gate compares 315 normalized QEMU/DUT
rows with zero mismatches. A 486-row probe reaches a marker allocation policy
mismatch before the following `OP_SD` indexed store, making redirecting marker
allocation and store memory mutation the next packet frontier.
R123 fixes that redirecting marker frontier. The backend now records direct
and call active blocks as unconditional redirects at the next marker boundary,
so the `C.BSTART.DIRECT` at `pc=0x400055ca` can complete at the later
`C.BSTART.COND` at `pc=0x400055d4` without allocating the conditional marker's
own block. The Verilator harness also collects both slots of a legal
two-row commit window in order before comparing against QEMU. The promoted
486-raw-row CoreMark gate compares 323 normalized QEMU/DUT rows with zero
mismatches; the next frontier remains the following indexed `OP_SD` at
`pc=0x400055f2`, which must not be promoted without explicit store memory
mutation or real LSU/STQ ownership.
R124 adds that explicit reduced store mutation for the `OP_SD` indexed-store
frontier. `ReducedScalarAluExecute` emits the model-derived sideband
`addr = base + (index << 3)`, `wdata = SrcD`, `size = 8`, while the Verilator
harness updates the sparse memory image only after the expected committed store
row has matched the observed DUT row. The 544-raw-row CoreMark probe compares
357 normalized QEMU/DUT rows with zero mismatches. This remains a committed
store sideband and sparse-memory harness bridge, not store forwarding, cache
state, or STQ/LSU ownership.
R125 extends the same live CoreMark path through 1024 raw QEMU rows. The
packet adds reduced `SUBI`, compressed local `C.AND` with the same implicit-T
trace-gap synthesis as `C.ADD`, encoded local/scalar `ADD` source validation,
and compressed `C.SDI` store-immediate sideband handling. The 1024-raw-row
probe extracts 953 expected rows and compares 665 normalized QEMU/DUT rows
with zero mismatches. The store caveat is unchanged: `C.SDI` mutates the
sparse harness memory only after the committed store row matches QEMU and does
not claim LSU, STQ, cache, or forwarding ownership.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Arms the live fetch source at a starting PC. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Replaces the active fetch PC for a reduced restart. |
| input | `frontendFlushValid` | `Bool` | valid | Clears source packet state, F4, decode path, and reduced issue state. |
| input | `peId`, `threadId` | `UInt` | with source packet | Packet-owned PE/STID sidecars for decode/rename. |
| input | `fetchReqReady` | `Bool` | ready | Bounded fixture accepts a source PC request. |
| input | `fetchRespValid`, `fetchRespWindow` | `Bool`, `UInt(windowWidth.W)` | valid | Bounded fixture returns one instruction window for the issued PC. |
| input | `loadLookupData` | `UInt(64.W)` by default | combinational with `loadLookupValid` | Reduced read-only load data supplied by the harness from the sparse ELF memory image. |
| input | `rfInitValid`, `rfInitArchTag`, `rfInitData` | mixed | pulse | Preloads identity physical RF state for architectural scalar GPRs. |
| input | `deallocReady` | `Bool` | ready | Allows retired ROB rows to deallocate. |
| output | `fetchReqValid`, `fetchReqPc`, `fetchRespReady` | mixed | valid/ready | Live source PC request and response handshake. |
| output | `source*` | mixed | diagnostic | Source active, request, response, packet, PC advance, and packet UID observability. |
| output | `f4ValidMask`, `f4SlotCount`, `decodeReady` | mixed | diagnostic | F4 slot shape and downstream decode readiness. |
| output | `denseSlotQueueInFire`, `denseSlotQueueOutFire`, `denseSlotQueueInSlotCount`, `denseSlotQueueCount`, `denseSlotQueueHeadSlot`, `denseSlotQueueFull`, `denseSlotQueueEmpty` | mixed | diagnostic | Reduced dense-slot bridge capture/drain and occupancy observability. |
| output | `selectedValid`, `selectedRobValue`, `selectedBlockBid` | mixed | diagnostic | Reduced selected decoded slot and allocated identities. |
| output | `blockMarkerSkipFire`, `blockMarkerSkipValid`, `blockMarkerMixedPacket`, `blockMarkerBoundary`, `blockMarkerStop`, `blockMarkerPc`, `blockMarkerInsn`, `blockMarkerLen`, `blockMarkerTarget` | mixed | diagnostic | Reduced block-marker consume observability on a dense-slot drain. Marker slots advance without scalar ROB allocation or dec/ren push; older scalar issue activity may overlap in the same cycle. |
| output | `blockMarkerAllocReady`, `blockMarkerLifecycleConflict`, `blockMarkerAllocFire`, `blockMarkerAllocBid`, `blockMarkerActiveValid`, `blockMarkerActiveBid`, `blockMarkerActiveTarget`, `blockMarkerStopRedirectValid`, `blockMarkerStopRedirectPc` | mixed | diagnostic | Reduced marker lifecycle observability: BROB-only allocation readiness/fire for consumed `BSTART`, scalar-done conflict guard, active full BID/target reused by scalar rows, and marker-stop frontend restart target. |
| output | `decRenPushFire`, `decRenPopFire`, `decRenCount` | mixed | diagnostic | Decode-to-rename queue events and occupancy. |
| output | `decRenValid`, `decRenHeadPc`, `decRenHeadRidValid`, `decRenHeadRidValue` | mixed | diagnostic | Decode/rename queue head observability for live-gate stalls. |
| output | `renamedOutValid`, `renamedAccepted` | `Bool` | diagnostic | Rename output and issue-queue acceptance. |
| output | `rfReadReadyMask`, `rfAllReadReady`, `rfReadyMask` | mixed | diagnostic | Reduced physical RF read and ready-state observability. |
| output | `rfWriteValid`, `rfWriteTag`, `rfWriteData`, `rfStateError` | mixed | diagnostic | Execute-to-RF writeback and RF error reporting. |
| output | `localTReadyMask`, `localUReadyMask`, `localTPendingCount`, `localUPendingCount`, `localIncomingBlocked` | mixed | diagnostic | Reduced local T/U overlay readiness and producer-pending gate visible to issue selection and live-stall diagnostics. |
| output | `issueQueue*` | mixed | diagnostic | Reduced queue enqueue, pick, I1/I2, issue, cancel, release, occupancy, and block-cause signals. |
| output | `executeAccepted`, `executeBusy`, `executeCompleteValid`, `executeCompleteRobValue` | mixed | diagnostic | Reduced ALU handoff and completion. |
| output | `loadLookupValid`, `loadLookupAddr` | mixed | diagnostic | Reduced E-stage load lookup request for `OP_C_LDI`/`OP_LDI`. |
| output | `executeUnsupported`, `executeUnsupportedOpcode` | mixed | diagnostic | Unsupported reduced ALU opcode report. |
| output | `robAllocFire`, `robRenameUpdateFire`, `completeAccepted`, `completeIgnored` | `Bool` | pulse | ROB allocation, post-rename update, and completion acceptance events. |
| output | `decodeBlockedByRename`, `decodeBlockedByRob`, `decodeBlockedByOutput`, `decodeBlockedByTURename`, `tuRenameSourceUnderflowMask`, `robRenameUpdate*` | mixed | diagnostic | Reduced decode/rename/ROB backpressure and post-rename update diagnostics. |
| output | `tuRenameActiveBankValid`, `tuRenameBlockedByTAlloc`, `tuRenameBlockedByUAlloc`, `tuRenameTUsedEntries`, `tuRenameUUsedEntries` | mixed | diagnostic | Active T/U bank selection, mapQ pressure, and used-entry observability from the reduced rename path. |
| output | `tuRetireCommandValid`, `tuRetireCommandFire`, `tuRetireLocalBlockCommit*`, `tuRetireAccepted`, `tuRetireMiss`, `tuRetireReleaseMismatch`, `tuRetireUnsupported` | mixed | diagnostic | T/U retire serializer, local block-commit event, and terminal rename response observability. |
| output | `robDeallocBlockLastValid`, `robDeallocBlockLastBlockBid`, `blockScalarDoneFire`, `blockScalarDoneBid`, `blockRetireFire`, `blockRetireBid` | mixed | pulse/diagnostic | Reduced ROB block-last to BROB lifecycle sideband path with full 64-bit BID. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit rows with RF-sourced source data and ALU writeback. |
| output | `commit*`, `dealloc*`, `occupiedMask`, `completedMask`, `retiredMask`, `blockAllocatedMask`, `blockCompleteMask`, `blockPendingMask`, `idle` | mixed | diagnostic | Commit monitor, ROB/BROB lifecycle, pending-block masks, and reduced-top idle observability. |

## State

The wrapper owns composition wiring plus a temporary reduced local T/U value
overlay. Most state remains in child modules:

- `FrontendFetchPacketSource`: active PC, one-outstanding request state,
  response packet residency, packet UID/checkpoint, restart, and flush clearing.
- `F4DecodeWindow`: combinational instruction-window slicing, stop termination
  after valid `C.BSTOP`, and decoded byte count.
- `F4DenseSlotQueue`: compacted FIFO of all valid slots from one F4 packet,
  preserving each slot's original index while draining one slot at a time.
- `DecodeRenameROBPath`: decode-to-rename queue, scalar/T-U rename, reduced
  ROB/BROB allocation, completion, commit, and deallocation.
- `ReducedScalarRegisterFile`: physical scalar data and ready bits.
- `localTData/localUData` and `localTReady/localUReady`: four-entry reduced
  local T/U value overlays used only by the current CoreMark T/U-source prefix.
- `ReducedScalarIssueQueue` and `ReducedScalarIssuePick`: resident issue rows,
  source-ready snapshots, P1/I1/I2 timing, issued-entry lock, cancel, and W2
  release.
- `ReducedScalarAluExecute`: reduced scalar ADD/ADDI/SUBI/MOVR/MOVI/shift/OR/C.ADD/C.AND
  execute, read-only C.LDI/LDI load lookup sidebands,
  C.SETC_EQ/C.SETC_NE/SETC_LTU no-writeback rows and branch-decision
  sidebands, SDI/C.SDI store sidebands, and writeback-shaped completion.

## Logic Design

`FrontendFetchPacketSource` issues a PC request after `startValid` or
`restartValid`. The bounded Verilator fixture accepts the request, reads the
next eight bytes for that PC from a `FetchMemoryImage`, and returns a 64-bit
F4 window. The memory image can be a base-addressed binary blob or a sparse
address-to-byte map extracted from ELF PT_LOAD segments. The source captures
that response as a `FrontendDecodePacket` and presents it to F4 only when
`F4DenseSlotQueue` can accept every valid slot in the packet. F4's
`totalLenBytes` feeds back as `advanceBytes`, so the source advances once by
the whole decoded byte span of the dense window.

`F4DenseSlotQueue` captures all valid F4 slots in original slot order, then
drains exactly one slot per cycle into `DecodeRenameROBPath`. The path
reserves and updates ROB rows for scalar slots, then emits renamed scalar uops
into `ReducedScalarIssueQueue`. This top instantiates `DecodeRenameROBPath`
with `skipBlockMarkers=true`; a drained `BSTART` or `BSTOP` marker slot drives
`blockMarkerSkipFire` without scalar selection, scalar ROB allocation, or
dec/ren enqueue. A drained `BSTART` allocates a BROB-only active BID, and
following scalar slots are stamped with that active BID while the marker target
is retained. A drained `BSTOP` pulses scalar done for the current active BID,
clears the active state, and can request a reduced frontend restart to the
active marker target. The top applies that restart by flushing F4/dense-slot
state and driving `FrontendFetchPacketSource.restartValid` on the next cycle.
Because the issue queue is decoupled, a marker drain can overlap an older
scalar row's issue enqueue. The marker contract is no marker-owned scalar
selection, dec/ren push, or scalar ROB allocation, not global silence on
unrelated older issue activity.
For a conditional active block, the top captures
`ReducedScalarAluExecute.branchCondition*` and presents the pending decision to
`DecodeRenameROBPath` until the next marker boundary consumes it. The latch is
cleared on start/restart/frontend flush and after the boundary drains.
This top also constructs the backend path with `reducedStoreDispatchBypass`:
store rows still flow through decode, rename, issue, ALU execute, ROB
completion, and QEMU-shaped store sideband comparison, but the reduced STQ
shell is not allowed to accumulate resident stores without a connected
STA/STD execution and commit/free owner.
Rename acceptance remains queue-capacity driven. RF physical source readiness
is sampled by the issue queue and gates issue from resident rows, not frontend
packet acceptance. The P1/I1/I2 picker reads source data from
`ReducedScalarRegisterFile` for scalar P operands. For T/U local operands, the
issue queue uses `readOperandClass` plus `readRelTag` to suppress scalar RF
reads and select data from the local T/U overlay. Local readiness is sampled
through `localTReadyMask` and `localUReadyMask`, mirroring the same registered
issue-readiness timing used for scalar RF sources.

On completion, the ALU sends a completion row to `DecodeRenameROBPath`, writes
the destination physical tag in the reduced RF only for scalar GPR
destinations, pushes T/U destination data into the local overlay for tags
`31`/`30`, and releases the issue-queue entry by `(bid, rid, stid)`. The
monitored commit row then carries the original PC/instruction, scalar source
architectural tags and data, destination/writeback data, model `(bid,gid,rid)`
identity, and hardware `blockBid` sideband through the shared JSONL writer.

For reduced loads, the execute stage publishes a combinational lookup request
before it captures the E-stage result. The Verilator harness evaluates the DUT,
reads eight little-endian bytes from the sparse ELF image at `loadLookupAddr`,
drives `loadLookupData`, and reevaluates before the clock edge. Missing bytes
read as zero. R124/R125 add explicit committed-store mutation for compared
8-byte store rows: after a store row matches QEMU, the harness writes that little-endian
`mem_wdata` into the same sparse image so later reduced load lookups see the
program-order store effect. This is still not a general LSU/STQ, cache, or
store-forwarding implementation.

R123 also proves that live generated-RTL commit collection must accept the
native two-slot commit window. The harness preserves slot order by collecting
slot 0 followed by slot 1, then applies the same QEMU-shaped row comparison to
each committed row.

## Model Alignment

The C++ model flow being reduced here is:

1. IFU F0/F2/F3/F4/F5 stages create decode bundles from PC request/response
   state and instruction-size metadata.
2. `DCTop::Work` and `SPEROB::allocROB` allocate ROB identity before the row
   enters rename.
3. `GPRRename` maps scalar architectural sources to physical tags and
   allocates physical destinations.
4. The issue path keeps renamed rows resident, initializes source readiness
   from the ready table, selects ready rows, reads RF data in the issue pipe,
   and releases issued rows after pipe completion.
5. `ALUPipe::Work` publishes W2 writeback/completion; commit preserves
   `CommitInfo(bid,gid,rid)` separately from hardware block identity.

The reduced Chisel top preserves those ownership boundaries for a serialized
scalar smoke. The R96/R97 memory feeders remove direct instruction-word
injection from the response driver and allow sparse ELF-backed program bytes.
R98 separates expected row ownership from the harness: a JSONL row stream now
provides the PC, instruction, length, scalar source data, and writeback
expectations that the reduced top can execute. R99 allows that stream to come
from an already captured QEMU commit trace when the first rows are the strict
reduced scalar subset. The extractor rejects unsupported opcodes, memory or
trap rows, non-scalar GPR aliases, non-sequential `next_pc`, and result
mismatches before the Verilator harness sees the rows. R100 automates that
path for a direct-boot ELF: the wrapper captures a bounded live QEMU JSONL
prefix through a FIFO, optionally applies a PC filter to skip legal block
headers until this reduced top can execute them, then runs the existing
`FETCH_ELF` plus `FETCH_QEMU_TRACE` gate. R101 adds an
`--allow-block-markers`/`FETCH_QEMU_ALLOW_BLOCK_MARKERS=1` path that preserves
legal `BSTART`/`BSTOP` rows as `skip` entries. The harness still serves those
rows to the DUT and asserts marker diagnostics, but only scalar reduced ALU
rows are written into the QEMU/DUT comparator streams. R102 aligns the input
packet shape with model dense frontend bundles: one 8-byte response can carry
the entry marker, scalar body rows, and exit marker, and the reduced Chisel top
serializes those slots through `F4DenseSlotQueue` before the current one-row
ROB path. This top still does not model cacheline merge, branch prediction,
multiple outstanding fetches, width-wide decode/ROB allocation, full
oldest-ready issue preferences, read-port arbitration, bypass, load
speculative wakeup, LSU, precise traps, or architectural redirect restart.
R104 adds the first reduced block-lifecycle alignment for those marker slots.
The model allocates BROB on `BSTART`, stamps following scalar instructions with
the current block BID, and completes the current block on `BSTOP` through the
scalar PE ROB/BROB path. The top now checks the visible reduced contract:
`BSTART` creates an active full BID, scalar rows report that active BID, and
`BSTOP` completes the active BID before the architectural comparator sees only
the scalar commit rows.
R111 extends that live prefix through CoreMark `OP_SLL` at `pc=0x40005520`.
The prior U-destination `ADDI` produces U0=`32`, `HL.LUI` produces T0=`1`, and
`SLL` consumes T0/U0 to write U0=`0x100000000`. QEMU suppresses local-source
fields on that row, so the reduced top must feed execute from the local overlay
while `ReducedScalarAluExecute` leaves scalar source fields invalid.
R112 extends the same local-overlay contract through `SLL` at `pc=0x4000552a`
and `SRL` at `pc=0x4000552e`; both write architectural T tag `31`, and the
17-row CoreMark capture proves the shared F4 window carries both slots.
R113 adds `OR` at `pc=0x40005532`, which reads local U0/T0 and writes U0, plus
the following `C.LDI` at `pc=0x40005536`, which reads scalar x4, writes T0,
and emits an 8-byte load sideband with zero data for this prefix only. R114
adds `C.ADD` at `pc=0x4000553c`, whose compressed fields read T0/U0 and write
implicit T0. The reducer converts QEMU's no-writeback C.ADD trace row into the
model-derived implicit T writeback expected row before the comparator runs.
R115 adds `SRA` at `pc=0x4000553e`, which performs signed 64-bit arithmetic
right shift of local T0 by local U2 and writes T0=`1`, plus the paired `SLLI`
at `pc=0x40005542`, which shifts the new T0 left by immediate `3` and writes
T0=`8`. Because both rows are adjacent in the same dense frontend packet, the
reduced top orders local T/U producers before younger rename output while the
producer is pending.

## Trace/Observability

`tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` emits the
top, builds every emitted SystemVerilog file with Verilator, and runs
`frontend_fetch_rf_alu_trace_top_tb.cpp`. The driver:

- preloads initial reduced RF data from the first expected source read for any
  architectural register that has not already been produced by an earlier row;
- emits `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.fetch.bin`
  with dense little-endian instruction bytes unless `FETCH_MEMORY_BIN` points
  at another binary image;
- when `FETCH_ELF` is set, extracts ELF64 little-endian PT_LOAD segments into
  `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/elf.fetch.mem` or
  `FETCH_MEMORY_HEX`, then passes that sparse image to the harness;
- accepts `FETCH_MEMORY_HEX` directly for sparse address-to-byte memory tests;
- emits `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.expected.jsonl`
  unless `FETCH_EXPECTED_ROWS` points at another QEMU-shaped expected-row file;
- when `FETCH_QEMU_TRACE` points at a QEMU commit JSONL, runs
  `frontend_fetch_rf_alu_qemu_rows.py` to produce
  `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/qemu.expected.jsonl`;
- accepts `FETCH_QEMU_MAX_ROWS` to cap the extracted strict reduced-scalar row
  prefix, with `0` meaning all normalized input rows;
- accepts `FETCH_QEMU_ALLOW_BLOCK_MARKERS=1` to preserve legal `BSTART`/`BSTOP`
  rows as DUT-only skip entries in `qemu.expected.jsonl`;
- starts the live source at the first expected row PC;
- serves one 8-byte instruction window per source PC request by reading bytes
  from the fetch-memory image, padding only missing trailing bytes outside the
  current program image;
- serves reduced read-only load lookups from the same sparse memory image when
  `loadLookupValid` is asserted, returning zero for missing bytes;
- groups expected rows into dense F4 windows, then requires `sourceOutFire`,
  `denseSlotQueueInFire`, matching `f4ValidMask`, matching `f4SlotCount`, and
  `sourceAdvanceBytes` equal to the window's decoded byte span;
- drains every expected slot from `F4DenseSlotQueue` before fetching the next
  window;
- for skip rows, requires `blockMarkerSkipFire`, matching marker
  PC/instruction/length, boundary/stop diagnostics, marker target, and no
  marker-owned scalar ROB allocation or dec/ren push;
- for `BSTART` skip rows, requires `blockMarkerAllocFire` and records
  `blockMarkerAllocBid` plus `blockMarkerTarget` as the active full BID/target;
- for scalar rows while a marker-owned block is active, requires
  `selectedBlockBid` to match the active full BID;
- for `BSTOP` skip rows, requires `blockScalarDoneFire` with the active full
  BID, checks any non-sequential `blockMarkerStopRedirectPc`, and clears the
  active marker state after the drain;
- waits for commit rows for each scalar instruction after the window's slots
  have drained;
- writes QEMU-shaped reference and DUT JSONL through
  `tools/chisel/commit_trace_jsonl.h`;
- compares `ADD r3,r4,r5`, `ADDI r6,r3,0x7ff`, and `C.MOVR r5,r6` through the
  neutral comparator.

The default wrapper manifest at
`generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.

`tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh` builds a tiny
legal-entry direct-boot ELF:

```text
C.BSTART.STD; ADD; ADDI; C.MOVR; C.BSTOP
```

With `--long-body`, the same builder emits a longer legal-entry body using
only the currently supported reduced scalar instructions:

```text
C.BSTART.STD; ADD; ADDI; C.MOVR; ADDI; ADD; C.MOVI; C.MOVR; C.BSTOP
```

The R100 gate runs:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 3 \
  --capture-rows 3 \
  --pc-lo 0x10002 \
  --pc-hi 0x1000b \
  --max-seconds 5
```

The PC filter selects the scalar prefix after the legal entry `BSTART` until
block-header execution is live in this reduced top. QEMU termination by
signal after the bounded rows are captured is expected; the pass/fail source is
the manifest. The R100 manifest at
`generated/chisel-frontend-fetch-rf-alu-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.

The R101 block-marker gate runs the same ELF without PC filters:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 \
  --capture-rows 5 \
  --allow-block-markers \
  --max-seconds 5
```

The preview stream preserves five QEMU rows: `C.BSTART` at `0x10000` as a
skip row, scalar rows at `0x10002`, `0x10006`, and `0x1000a`, and `C.BSTOP`
at `0x1000c` as a skip row. The comparator manifest still records only the
three architectural scalar commits: `status: "pass"`, `compared_rows: 3`, and
`mismatch_count: 0`.

The R102 dense-slot gate keeps the same five-row live QEMU input stream but
lets F4 see natural 8-byte windows:

```bash
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh \
  --out-dir generated/r102-live-qemu-fixture
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r102-dense-qemu-elf-xcheck \
  --elf generated/r102-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 \
  --capture-rows 5 \
  --allow-block-markers \
  --max-seconds 5
```

The preview stream still preserves `C.BSTART`, three scalar rows, and
`C.BSTOP`. The DUT now captures mixed marker/scalar F4 packets through
`F4DenseSlotQueue` and drains them serially. The manifest at
`generated/r102-dense-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.

The R104 marker-lifecycle gate keeps the same live QEMU fixture but now checks
the marker-owned BROB lifecycle diagnostics:

```bash
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh \
  --out-dir generated/r104-live-qemu-fixture
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r104-marker-lifecycle-qemu-elf-xcheck \
  --elf generated/r104-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 \
  --capture-rows 5 \
  --allow-block-markers \
  --max-seconds 5
```

The preview stream still preserves `C.BSTART`, three scalar rows, and
`C.BSTOP`. The harness now also checks BROB-only marker allocation, scalar
active-BID reuse, and marker-driven scalar completion. The comparator manifest
at `generated/r104-marker-lifecycle-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.

The R105 long-body gate exercises the same marker lifecycle over multiple F4
windows and seven scalar commits:

```bash
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh \
  --out-dir generated/r105-long-body-fixture \
  --long-body
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r105-long-body-qemu-elf-xcheck \
  --elf generated/r105-long-body-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 \
  --capture-rows 9 \
  --allow-block-markers \
  --max-seconds 5
```

The manifest at
`generated/r105-long-body-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 7`, and `mismatch_count: 0`.

The R106 CoreMark ADDTPC gate uses the first actual CoreMark `_start` rows
through `ADDTPC` and stops before the next unsupported HL call marker:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 4 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The compared scalar rows are `C.MOVR`, `ADDTPC`, and the following `ADDI`;
`C.BSTART` is preserved as a skip marker. The next unsupported CoreMark row is
the 6-byte HL call marker at `pc=0x40005500`, which belongs to a later
block-control packet rather than this reduced scalar ALU extension. The
manifest at
`generated/r106-coremark-addtpc-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.

The R107 CoreMark gate extends that prefix through the first direct-call
header. The top now accepts target-bearing `BSTART` skip rows, lets
`F4DecodeWindow` terminate an 8-byte response packet at `C.BSTOP`, and applies
a reduced frontend-only restart to the active `BSTART` target after consuming
the marker stop. This matches QEMU's `HL.BSTART.STD CALL`/`C.SETRET`/`C.BSTOP`
sequence, where `C.SETRET` writes the return address and `C.BSTOP` redirects to
the call target instead of executing filler markers:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r107-coremark-hl-call-setret-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 8 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r107-coremark-hl-call-setret-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 4`, and
`summary.mismatch_count: 0`.

The R108 CoreMark gate extends the prefix through the first single-save
`FENTRY` macro-template row. The reduced path treats that row as a narrow
stack-prologue operation: read saved GPR and old SP internally, write updated
SP, and emit the one QEMU-shaped 8-byte store sideband for the comparator. It
does not implement full stack-template expansion or LSU state:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r108-coremark-fentry-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 11 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r108-coremark-fentry-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 6`, and
`summary.mismatch_count: 0`. A 12-row probe stops at the next unsupported row,
an `ADDI` that writes architectural tag `30`.

The R109 CoreMark gate extends the prefix through that U-destination `ADDI`.
`FrontendRegAliasClassify` maps destination alias `30` to
`DestinationKind.U`, `ScalarTURenameBridge` owns the T/U destination overlay,
and the reduced RF/issue/execute path now gates scalar RF clear/write side
effects to `DestinationKind.Gpr`. The commit row still carries QEMU-shaped
`dst_reg=30` / `wb_rd=30` / `dst_data=32` fields for comparison:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 12 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r109-coremark-u-dst-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 7`, and
`summary.mismatch_count: 0`. A 13-row probe stops at `OP_HL_LUI`
(`pc=0x4000551a`, `insn=0x1f97000e`, `len=6`).

The R110 CoreMark gate extends the prefix through that `HL.LUI` row. The
frontend extracts the 48-bit-format IMM32 as `1`, classifies destination tag
`31` as `DestinationKind.T`, and the reduced ALU emits the QEMU-shaped
architectural writeback row while suppressing scalar RF writeback:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 13 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r110-coremark-hl-lui-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 8`, and
`summary.mismatch_count: 0`. A 14-row probe stops at `OP_SLL`
(`pc=0x40005520`, `insn=0x01cc7f05`, `len=4`), the first row needing T/U
local-register sources rather than only T/U destinations.

The R111 CoreMark gate extends the prefix through that `OP_SLL` local-source
row and also covers the first reduced ROB wrap case. The `SLL` row is the
ninth scalar allocation in the generated 8-entry top, so
`DecodeRenameROBPath` must stamp the queued row with both `allocRobValue` and
`allocRobWrap`; otherwise the post-rename update names the right slot with the
wrong epoch and stalls before issue:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 14 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r111-coremark-sll-tu-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 9`, and
`summary.mismatch_count: 0`.

The R112 CoreMark gate extends the prefix through the next local shift pair.
After `HL.LUI` produces T0=`0`, the `SLL` row at `pc=0x4000552a` writes T0,
and the following `SRL` row at `pc=0x4000552e` shares the same 8-byte F4
response window:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 17 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 12`, and
`summary.mismatch_count: 0`. An 18-row probe stops at `OP_OR`
(`pc=0x40005532`, `insn=0x078e3f05`, `len=4`), another local-source row
reading U0/T0 and writing U0.

The R113 CoreMark gate extends the prefix through that `OP_OR` row and the
following narrow `C.LDI` zero-load row:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 19 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r113-coremark-or-c-ldi-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 14`, and
`summary.mismatch_count: 0`. A 20-row probe reaches another supported local
`SLL` at `pc=0x40005538` but ends mid-window; a 21-row extraction probe
identifies the next true blocker as `OP_C_ADD` (`pc=0x4000553c`,
`insn=0xe608`, `len=2`).

The R114 CoreMark gate extends the prefix through that `OP_C_ADD` row. The raw
QEMU trace omits the C.ADD local destination fields, so
`frontend_fetch_rf_alu_qemu_rows.py` synthesizes the model-derived implicit T
writeback from the local T/U queue state:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r114-coremark-c-add-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 21 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r114-coremark-c-add-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 16`, and
`summary.mismatch_count: 0`. A 22-row extraction probe identifies the next
unsupported row as `OP_SRA` (`pc=0x4000553e`, `insn=0x01ec6f85`, `len=4`),
which reads local T/U sources and writes T tag `31`.

The R115 CoreMark gate extends the prefix through `OP_SRA` and the same dense
packet's `OP_SLLI` row:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r115-coremark-sra-slli-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 23 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r115-coremark-sra-slli-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 18`, and
`summary.mismatch_count: 0`. A 24-row extraction probe identifies the next
frontier as a mixed local/scalar `C.ADD` at `pc=0x40005546`, `insn=0x2608`,
`len=2`: compressed `SrcL` is T0, compressed `SrcR` is scalar x4, and current
QEMU emits scalar `src1` but no destination/writeback fields.

The R116 CoreMark gate extends through the full dense packet starting at that
mixed `C.ADD` row. The packet contains `C.ADD` at `pc=0x40005546`, local-source
`ADDI` at `pc=0x40005548`, and scalar `C.MOVR` at `pc=0x4000554c`; a
`--capture-rows 24` gate cuts inside this 8-byte fetch window and fails the
dense-packet boundary check, so use `--capture-rows 26` for the promoted
packet:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r116-coremark-c-add-mixed-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 26 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r116-coremark-c-add-mixed-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 21`, and
`summary.mismatch_count: 0`. A 27-row probe still passes and shows the next raw
row as a zero-advance `C.BSTART` artifact at `pc=0x4000554e`, `insn=0x0004`.

The R117 CoreMark gate extends across that marker artifact and through the
next scalar/local packet. The promoted window includes `C.MOVR` at
`pc=0x40005550` writing T tag `31`, local-source `ADDI` at `pc=0x40005552`,
scaled local-base `C.LDI` at `pc=0x40005556`, no-writeback `C.SETC_NE` at
`pc=0x40005558`, and `C.BSTART.STD.FALL` at `pc=0x4000555a`:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r117-coremark-c-movr-c-ldi-setc-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 34 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r117-coremark-c-movr-c-ldi-setc-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `expected rows: 31`,
`summary.compared_rows: 25`, and `summary.mismatch_count: 0`. The next
frontier starts at `pc=0x4000555c`, `insn=0x13808315`, followed by
`0x10000395`, `0x4146`, and a marker at `0x40005566`.

The R118 CoreMark gate extends through the first ordinary scalar
store-immediate row. A wider 48-row probe identified `OP_SDI` at
`pc=0x4000556a`, `insn=0x0182b059`, with scalar x5 store data, suppressed
local T0 address base, zero scaled offset, 8-byte store size, and no
destination/writeback. The 41-row capture cuts inside the two-slot dense packet
starting at `pc=0x4000556a`, and the 43-row capture cuts inside the following
three-slot packet at `pc=0x40005572`; promote the dense-safe 42-row window:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 42 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r118-coremark-sdi-42-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 31`, and
`summary.mismatch_count: 0`. The next frontier is the dense packet beginning at
`pc=0x40005572`, which includes the no-writeback compare at `0x3a36` and a
redirecting `C.BSTART`/branch marker at `pc=0x40005574`.

The R119 CoreMark gate extends through that conditional marker edge and one
fall-through loop iteration. The first 48-row raw probe is intentionally not a
promotion gate because it ends inside the post-redirect dense F4 window at
`pc=0x4000556e`; use the dense-safe 50-row capture:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 50 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 36`, and
`summary.mismatch_count: 0`. The live harness now buffers commit rows observed
while dense slots are still draining so ROB retire pulses are compared in
program order rather than missed after the packet has drained.

The R120 CoreMark gate extends through repeated loop-body trips after the
conditional marker edge:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r120-coremark-scalar-block-store-bypass-128-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 \
  --capture-rows 128 \
  --allow-block-markers \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The manifest at
`generated/r120-coremark-scalar-block-store-bypass-128-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 88`, and
`summary.mismatch_count: 0`. A 256-row probe stops before RTL comparison
because the reduced QEMU-row selector does not yet support
`pc=0x40005576`, `insn=0xffe13319`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource`
- `bash tools/chisel/run_chisel_tests.sh --only F4DenseSlotQueue`
- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r100-live-qemu-fixture`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 3 --capture-rows 3 --pc-lo 0x10002 --pc-hi 0x1000b --max-seconds 5`
- `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r101-live-qemu-fixture`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
- `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r102-live-qemu-fixture`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r102-dense-qemu-elf-xcheck --elf generated/r102-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
- `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r104-live-qemu-fixture`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r104-marker-lifecycle-qemu-elf-xcheck --elf generated/r104-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
- `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r105-long-body-fixture --long-body`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r105-long-body-qemu-elf-xcheck --elf generated/r105-long-body-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 9 --allow-block-markers --max-seconds 5`
- `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --self-test`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 42 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-1024-frontier-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 4 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r107-coremark-hl-call-setret-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 8 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r108-coremark-fentry-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 11 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `FETCH_EXPECTED_ROWS=generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.expected.jsonl bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_QEMU_TRACE=generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.expected.jsonl bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `python3 tools/chisel/frontend_fetch_rf_alu_fixture_rows.py --self-test`
- `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --self-test`
- `python3 tools/chisel/frontend_fetch_elf_memory.py --self-test`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
