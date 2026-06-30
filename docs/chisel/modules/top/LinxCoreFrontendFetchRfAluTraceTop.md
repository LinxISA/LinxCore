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

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Arms the live fetch source at a starting PC. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Replaces the active fetch PC for a reduced restart. |
| input | `frontendFlushValid` | `Bool` | valid | Clears source packet state, F4, decode path, and reduced issue state. |
| input | `peId`, `threadId` | `UInt` | with source packet | Packet-owned PE/STID sidecars for decode/rename. |
| input | `fetchReqReady` | `Bool` | ready | Bounded fixture accepts a source PC request. |
| input | `fetchRespValid`, `fetchRespWindow` | `Bool`, `UInt(windowWidth.W)` | valid | Bounded fixture returns one instruction window for the issued PC. |
| input | `rfInitValid`, `rfInitArchTag`, `rfInitData` | mixed | pulse | Preloads identity physical RF state for architectural scalar GPRs. |
| input | `deallocReady` | `Bool` | ready | Allows retired ROB rows to deallocate. |
| output | `fetchReqValid`, `fetchReqPc`, `fetchRespReady` | mixed | valid/ready | Live source PC request and response handshake. |
| output | `source*` | mixed | diagnostic | Source active, request, response, packet, PC advance, and packet UID observability. |
| output | `f4ValidMask`, `f4SlotCount`, `decodeReady` | mixed | diagnostic | F4 slot shape and downstream decode readiness. |
| output | `denseSlotQueueInFire`, `denseSlotQueueOutFire`, `denseSlotQueueInSlotCount`, `denseSlotQueueCount`, `denseSlotQueueHeadSlot`, `denseSlotQueueFull`, `denseSlotQueueEmpty` | mixed | diagnostic | Reduced dense-slot bridge capture/drain and occupancy observability. |
| output | `selectedValid`, `selectedRobValue`, `selectedBlockBid` | mixed | diagnostic | Reduced selected decoded slot and allocated identities. |
| output | `blockMarkerSkipFire`, `blockMarkerSkipValid`, `blockMarkerMixedPacket`, `blockMarkerBoundary`, `blockMarkerStop`, `blockMarkerPc`, `blockMarkerInsn`, `blockMarkerLen` | mixed | diagnostic | Reduced block-marker consume observability on a dense-slot drain. Marker slots advance without scalar ROB allocation or dec/ren push; older scalar issue activity may overlap in the same cycle. |
| output | `blockMarkerAllocFire`, `blockMarkerAllocBid`, `blockMarkerActiveValid`, `blockMarkerActiveBid` | mixed | diagnostic | Reduced marker lifecycle observability: BROB-only allocation for consumed `BSTART` and active full BID reused by scalar rows. |
| output | `decRenPushFire`, `decRenPopFire`, `decRenCount` | mixed | diagnostic | Decode-to-rename queue events and occupancy. |
| output | `renamedOutValid`, `renamedAccepted` | `Bool` | diagnostic | Rename output and issue-queue acceptance. |
| output | `rfReadReadyMask`, `rfAllReadReady`, `rfReadyMask` | mixed | diagnostic | Reduced physical RF read and ready-state observability. |
| output | `rfWriteValid`, `rfWriteTag`, `rfWriteData`, `rfStateError` | mixed | diagnostic | Execute-to-RF writeback and RF error reporting. |
| output | `issueQueue*` | mixed | diagnostic | Reduced queue enqueue, pick, I1/I2, issue, cancel, release, occupancy, and block-cause signals. |
| output | `executeAccepted`, `executeBusy`, `executeCompleteValid`, `executeCompleteRobValue` | mixed | diagnostic | Reduced ALU handoff and completion. |
| output | `executeUnsupported`, `executeUnsupportedOpcode` | mixed | diagnostic | Unsupported reduced ALU opcode report. |
| output | `robAllocFire`, `robRenameUpdateFire`, `completeAccepted`, `completeIgnored` | `Bool` | pulse | ROB allocation, post-rename update, and completion acceptance events. |
| output | `robDeallocBlockLastValid`, `robDeallocBlockLastBlockBid`, `blockScalarDoneFire`, `blockScalarDoneBid`, `blockRetireFire`, `blockRetireBid` | mixed | pulse/diagnostic | Reduced ROB block-last to BROB lifecycle sideband path with full 64-bit BID. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit rows with RF-sourced source data and ALU writeback. |
| output | `commit*`, `dealloc*`, `occupiedMask`, `completedMask`, `retiredMask`, `idle` | mixed | diagnostic | Commit monitor, ROB lifecycle, and reduced-top idle observability. |

## State

The wrapper owns only composition wiring. State remains in child modules:

- `FrontendFetchPacketSource`: active PC, one-outstanding request state,
  response packet residency, packet UID/checkpoint, restart, and flush clearing.
- `F4DecodeWindow`: combinational instruction-window slicing and decoded byte
  count.
- `F4DenseSlotQueue`: compacted FIFO of all valid slots from one F4 packet,
  preserving each slot's original index while draining one slot at a time.
- `DecodeRenameROBPath`: decode-to-rename queue, scalar/T-U rename, reduced
  ROB/BROB allocation, completion, commit, and deallocation.
- `ReducedScalarRegisterFile`: physical scalar data and ready bits.
- `ReducedScalarIssueQueue` and `ReducedScalarIssuePick`: resident issue rows,
  source-ready snapshots, P1/I1/I2 timing, issued-entry lock, cancel, and W2
  release.
- `ReducedScalarAluExecute`: reduced scalar ADD/ADDI/MOVR/MOVI execute and
  writeback-shaped completion.

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
following scalar slots are stamped with that active BID. A drained `BSTOP`
pulses scalar done for the current active BID and clears the active state.
Because the issue queue is decoupled, a marker drain can overlap an older
scalar row's issue enqueue. The marker contract is no marker-owned scalar
selection, dec/ren push, or scalar ROB allocation, not global silence on
unrelated older issue activity.
Rename acceptance remains queue-capacity driven. RF physical source readiness
is sampled by the issue queue and gates issue from resident rows, not frontend
packet acceptance. The P1/I1/I2 picker reads source data from
`ReducedScalarRegisterFile` and presents confirmed data to
`ReducedScalarAluExecute`.

On completion, the ALU sends a completion row to `DecodeRenameROBPath`, writes
the destination physical tag in the reduced RF, and releases the issue-queue
entry by `(bid, rid, stid)`. The monitored commit row then carries the original
PC/instruction, source architectural tags and data, destination/writeback data,
model `(bid,gid,rid)` identity, and hardware `blockBid` sideband through the
shared JSONL writer.

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
- groups expected rows into dense F4 windows, then requires `sourceOutFire`,
  `denseSlotQueueInFire`, matching `f4ValidMask`, matching `f4SlotCount`, and
  `sourceAdvanceBytes` equal to the window's decoded byte span;
- drains every expected slot from `F4DenseSlotQueue` before fetching the next
  window;
- for skip rows, requires `blockMarkerSkipFire`, matching marker
  PC/instruction/length and boundary/stop diagnostics, and no marker-owned
  scalar ROB allocation or dec/ren push;
- for `BSTART` skip rows, requires `blockMarkerAllocFire` and records
  `blockMarkerAllocBid` as the active full BID;
- for scalar rows while a marker-owned block is active, requires
  `selectedBlockBid` to match the active full BID;
- for `BSTOP` skip rows, requires `blockScalarDoneFire` with the active full
  BID and clears the active marker state after the drain;
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
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 4 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
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
