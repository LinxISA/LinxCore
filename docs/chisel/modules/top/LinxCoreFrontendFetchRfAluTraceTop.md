# LinxCoreFrontendFetchRfAluTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Reduced-store replay-LIQ harness: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop.scala`
- Marker-row harness: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluMarkerRowsTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTopSpec.scala`
- Verilator driver: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
- Gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- Marker-row smoke: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_marker_rows_smoke.sh`
- Marker-row smoke driver: `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_marker_rows_trace_top_tb.cpp`
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
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreMemoryOverlay.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLretSink.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDrainPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnRobResolveDataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnLaneCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTloadCompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnFinalMetadataCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnTimingStatsCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnIexPipeInsertCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencySlot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeResidencyAdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW1AdvanceCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2Slot.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CompletionCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectLiveControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AtomicLiveRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectCompletionPermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupSinkReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectPayloadPlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectIssuePermit.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireVector.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ResolveArbiterInput.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RobCompleteSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowCandidate.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2CommitRowTraceSource.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RowFillEnableControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ReplayRowClearRequest.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearCommitGuard.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WritebackArbiterInput.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupFirePayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2WakeupArbiterInput.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SideEffectFireComplete.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2ClearIntent.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2PromotionControl.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2RefillReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2SlotReplacePlan.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnPipeW2AdvanceControl.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/pe/PECommon/DecodeBundle.h`
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
  - `model/LinxCoreModel/model/bctrl/BCtrl.cpp`
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/iex/rtable.cpp`
  - `model/LinxCoreModel/model/iex/iex_rf.cpp`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
  - `model/LinxCoreModel/model/iex/iex.cpp`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
  - `model/LinxCoreModel/model/pe/PECommon/PROBCommon.cpp`
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
R136 tightens the bounded-prefix harness rule for the final captured dense
window. If the final QEMU row ends before the last decodable F4 slot, the
harness may accept the packet superset, compare only the expected prefix rows,
and stop before draining post-capture slots into the reduced backend. This
does not relax any compared row fields; it only prevents the tail-prefix gate
from demanding idle state for instructions outside the requested capture.
R137 recorded the next frontier as a model/QEMU semantic divergence rather than
a top-level implementation gap. R138 resolves that local contract by aligning
QEMU scalar `CSEL`, LLVM MC lowering, the reduced row reducer, and
`ReducedScalarAluExecute` to the LinxCoreModel/Sail rule:
`SrcP != 0` selects `SrcL`. The top remains bounded by the current two-source
QEMU-shaped trace row; the promoted CSEL shape uses the reduced local overlay
for the observed T/U predicate and false-source aliases.
R139 carries the next byte-load packet through `OP_LBUI`. The top's sparse
memory lookup still supplies an aligned 64-bit word, while
`ReducedScalarAluExecute` masks the low byte for the unsigned byte result and
emits a 1-byte load sideband. The promoted 1642-raw-row CoreMark gate extracts
1518 expected rows and compares 1114 normalized QEMU/DUT rows with zero
mismatches. The 1660-row probe reaches the next top-level frontier: a
conditional-marker drain at `pc=0x40005d94` while execute/issue still hold a
row from the active block.
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
R126 extends the same path through the PCR return sequence and byte-store
prefix around `pc=0x40005700`. The packet adds unshifted PCR load immediates,
`HL.LD.PCR`/`LD.PCR` read-only load lookup, `C.SETC.TGT`/`SETC.TGT` dynamic
target latch, `FRET.STK` execute-owned frontend redirect and backend active
marker-context clear, ranged `FENTRY` save address calculation, `ADDW`,
scalar-source `SLLI`, and `SBI` one-byte store sidebands. The common
QEMU/DUT comparator now treats side-effect-free macro/template rows as
metadata only when they are sequential, so `FRET.STK` remains in the compare
stream and its redirected `next_pc` is checked. The promoted 1415-raw-row
CoreMark gate extracts 1303 expected rows and compares 927 normalized QEMU/DUT
rows with zero mismatches.
R127 carries the same reduced path to a 1461-raw-row CoreMark live capture.
The emitted live top now uses a 32-entry scalar mapQ, gates local-overlay
rename stalls only when the incoming head actually uses local T/U operands,
and splits marker redirects from scalar execute redirects: marker redirects
restart the frontend path, while scalar redirects also flush backend issue,
execute, rename/ROB cleanup, report queues, and the full-BID BROB block state.
`FRET.STK` takes its next PC from an explicit SETC target when present and
otherwise from the active marker target, `FENTRY` uses an internal SP shadow
for its macro stack update, ranged `FENTRY` store data is suppressed for the
reduced trace contract, and the issue picker preserves a selected-ready
snapshot so later RF/local readiness drops do not cancel an already selected
row. The BROB allocator now reuses `Flushed` slots. The promoted gate captures
1461 raw QEMU rows, extracts 1348 expected rows, and compares 966 normalized
QEMU/DUT rows with zero mismatches.
R128 carries the live capture through the first SETC immediate compare,
`OP_SETC_LTUI` at `pc=0x4000d1e4`. The reduced frontend suppresses the
generated shamt/register field as a visible destination, the ALU emits the
unsigned immediate branch-condition sideband, and the QEMU reducer normalizes
QEMU's `dst_valid=1`/`wb_valid=0` artifact into the no-writeback expected row.
The promoted gate captures 1477 raw QEMU rows, extracts 1361 expected rows,
and compares 976 normalized QEMU/DUT rows with zero mismatches. The next
frontier is `OP_ANDIW` at `pc=0x4000d210` (`insn=0x0ff1af35`).
R129 carries the live capture through that `OP_ANDIW` row. The reduced ALU
uses the model-derived word-immediate rule, `(src0 & imm)` truncated to
32 bits and sign-extended to 64 bits, while keeping architectural alias
`x30/U0` as a local-destination completion instead of a scalar RF writeback.
The promoted gate captures 1479 raw QEMU rows, extracts 1363 expected rows,
and compares 978 normalized QEMU/DUT rows with zero mismatches. A 1600-row
frontier probe then stops at `OP_MULW` at `pc=0x4000d21a`
(`insn=0x018e21c7`), a multicycle local-source row with `rd=x3`, `rs1=x28/U0`,
and `rs2=x24/T0`.
R130 carries the live capture through `OP_MULW` and the adjacent compressed
`OP_C_SUB` tail. `MULW` is still a reduced architectural-result bridge for the
current prefix, not the future multicycle owner. The following `C.SUB` row uses
scalar sources `x5`/`x2`, writes implicit `T0`, and relies on the same
QEMU-trace-gap destination synthesis used by `C.ADD`/`C.AND`. The promoted
gate captures 1481 raw QEMU rows, extracts 1365 expected rows, and compares
980 normalized QEMU/DUT rows with zero mismatches. A 1600-row frontier probe
then stops at `OP_ANDI` at `pc=0x4000d220` (`insn=0x003c2f15`).
R131 carries the live capture through the next immediate-ALU, store-word,
scalar subtract, scalar-visible shift, and multiply rows. The reduced ALU adds
`OP_ANDI`, `OP_ORI`, `OP_SUB`, `OP_MUL`, and `OP_SWI`; the QEMU reducer also
generalizes ordinary shift/logical source validation so rows such as
`OP_SLL` at `pc=0x4000d292` can read scalar-visible sources rather than only
local-overlay sources. The promoted gate captures 1595 raw QEMU rows, extracts
1475 expected rows, and compares 1079 normalized QEMU/DUT rows with zero
mismatches. A 1600-row frontier probe then reaches the richer `FRET.STK`
return/load packet at `pc=0x4000d2d4` (`insn=0x02a53041`).
R132 carries that return/load packet. The top forwards the latest reduced SETC
condition into `ReducedScalarAluExecute`; redirect-only `FRET.STK` rows still
take the active target when the condition is true, while the condition-false
template row loads `x10/ra` from sparse memory, writes reduced RF tag `x10`,
and redirects to the loaded RA. The QEMU reducer skips only the zero-advance
metadata prefix that is immediately followed by the same `FRET.STK` load row.
The promoted gate captures 1597 raw QEMU rows, extracts 1476 expected rows, and
compares 1080 normalized QEMU/DUT rows with zero mismatches. The next frontier
is the post-return `OP_ADDTPC` row at `pc=0x40005cb2` (`insn=0x00009f87`),
which writes local alias `x31/T0`.
R133 admits that post-return local materialization packet in the reducer. The
existing frontend decode, T/U rename bridge, local overlay, and execute
completion row already carry `OP_ADDTPC` and the following `OP_ORI` as
`x31/T0` writes; the only change is letting the QEMU reducer accept ADDTPC as a
legal reduced T/U destination. The promoted gate captures 1600 raw QEMU rows,
extracts 1479 expected rows, and compares 1082 normalized QEMU/DUT rows with
zero mismatches. A QEMU-only 1620-row probe identifies the next frontier as
`OP_HL_SD_PCR` at `pc=0x40005cce` (`insn=0x43a1b569000e`, `len=6`), an
8-byte PCR-relative store sideband.
R134 carries that high-long store-PCR packet. `FrontendOperandDecode` extracts
the split PCR-store immediate, `ReducedScalarAluExecute` emits no writeback and
stores `srcData(0)` to `pc + imm`, and the QEMU reducer validates both observed
`HL.SD.PCR` store rows. The promoted gate captures 1611 raw QEMU rows, extracts
1488 expected rows, and compares 1089 normalized QEMU/DUT rows with zero
mismatches. A QEMU-only 1640-row probe identifies the next frontier as
`OP_CMP_EQI` at `pc=0x40005d2a` (`insn=0x00060f55`, `len=4`), writing
`x30/U0` with value `1`.
R135 carries that compare-immediate row. `OP_CMP_EQI` is handled as a normal
ALU result producer, with `1` for equality against the signed 12-bit immediate
and `0` otherwise; it does not drive the reduced SETC condition sideband. The
promoted gate captures 1619 raw QEMU rows, extracts 1495 expected rows, and
compares 1093 normalized QEMU/DUT rows with zero mismatches. A QEMU-only
1660-row probe identifies the next frontier as `OP_LDI` writing `x31/T0` at
`pc=0x40005d2e` (`insn=0x0260bf99`, `len=4`).
R136 is scoped to that frontier: the live top continues using the R122
read-only sparse-ELF load lookup for `OP_LDI`, while the QEMU-row reducer now
admits the destination as local T instead of rejecting architectural alias
`31`. This preserves the reduced top boundary: load data still comes from the
harness lookup, and scalar RF writeback still occurs only for scalar GPR
destinations. The same live gate exposed a preceding `FRET.STK` side effect:
the visible commit row restores `x10/ra`, but architectural `x1/sp` also
advances to `stackPointerData + imm`. The top consumes the execute sideband to
update its scalar-SP shadow and sources later architectural `x1` reads from
that shadow, avoiding a second RF write port in the reduced bring-up top. The
promoted gate captures 1620 raw QEMU rows, extracts 1496 expected rows, and
compares 1094 normalized QEMU/DUT rows with zero mismatches.
R137/R138 resolve the following `CSEL` semantic frontier by aligning QEMU,
LLVM MC, the reduced row reducer, and Chisel execute to the LinxCoreModel/Sail
true-to-`SrcL` rule when `SrcP != 0`. R139 promotes the unsigned byte-load
frontier through `OP_LBUI`. R140 proves the next conditional-marker drain was
a harness budget issue, not a new RTL semantic failure.
R141 promotes the reduced live top through the next CoreMark RF/ALU prefix by
matching LinxCoreModel's 128-entry GGPR capacity in the emitted live top,
widening physical tags to 7 bits, and surfacing GPR free/mapQ counts plus
execute source physical-tag diagnostics. The same packet keeps the reduced SP
shadow as the data source for architectural `x1`, fixes `FRET.STK` arbitration
so pending SETC/marker targets outrank RA-load return unless a valid false
condition selects the load path, and verifies the 1188-row normalized
QEMU/DUT prefix from the 1747-row CoreMark capture with zero mismatches. The
strict sequential extractor still stops before the dynamic loop backedge at
`pc=0x4000630c`, so loop-aware block-control extraction remains the next
frontier.

R142 adds the first loop-aware cross-check infrastructure rather than claiming
the loop in RTL. `frontend_fetch_rf_alu_qemu_rows.py` now has an opt-in
`--allow-block-loop-reentry` mode that preserves strict extraction by default
but can annotate model/QEMU FALL-block re-entry markers as `loop_reentry`
rows. A 1900-row CoreMark probe extracts 1766 expected rows with 11 annotated
re-entries. Replaying that stream against the current live top fails at the
frontend packet boundary (`pc=0x4000632a`): the reduced F4 path captures the
static continuation slot at `0x4000632e` while the model/QEMU stream resolves
back to `0x4000630c`. This makes the next RTL packet a model-derived block
body-boundary cut plus restart, not another scalar ALU row.

R143 adds a temporary reduced BFU body-cut sideband to close that reduced-top
packet boundary. The loop-aware expected-row stream drives `reducedBodyCut*`
metadata into the top; F4 masks off slots at and after the model body boundary,
the fetch source advances only to the cut PC, the clipped dense rows remain
visible to the comparator, and the source restarts at the annotated FALL
header without flushing those rows. The replay in
`generated/r143b-loop-reentry-rtl-replay/report` compares 1330 normalized
QEMU/DUT rows with zero mismatches. This is replacement evidence for the
reduced live top, not full BFU closure; a later packet must replace the harness
sideband with real static-predictor `bsize`/`hsize`/fallBPC metadata.

R144 moves that cut calculation behind `ReducedBfuBodyCutPredictor`. The
harness no longer drives exact `cutPc`/`restartPc` commands; it drives reduced
BFU geometry (`headerPc`, `hsize`, `bsize`) derived from the loop-aware stream,
and the top computes `cutPc = headerPc + 2 + bsize` plus
`restartPc = headerPc + hsize` internally. This preserves the R143 replay
behavior while creating the RTL module boundary that a real BFU static
predictor will drive later. The R144 replay in
`generated/r144-bfu-geometry-loop-replay/report` compares 1330 normalized
QEMU/DUT rows with zero mismatches.

R145 adds `ReducedBfuStaticGeometryProducer` as a diagnostic static-predictor
owner next to the cut predictor. The top still drives cuts only from the
external loop-replay geometry, because the producer intentionally does not own
general `hsize` and CoreMark's observed continuation at `0x4000632e` is
`OP_ACRC` rather than a boundary event. The producer diagnostics expose when
explicit boundary or `BSTOP` events would learn reduced `bsize` geometry.

R146 feeds that diagnostic producer with the same resolved body-end PC that the
R144 external geometry already implies. This mirrors the LinxCoreModel
`SetBsize` path that can learn an end PC outside explicit block-marker decode,
without routing the partial producer into control. Body cuts still use the
external `reducedBfu*` geometry until a real branch-resolution owner and `hsize`
contract are present.

R147 also feeds the diagnostic producer with the external `reducedBfuHSizeBytes`
payload. The observed LinxCoreModel static-predictor path leaves
`SPMInstInfo::hsize` at its default zero and only copies/consumes it later, so
the top still treats this as diagnostic payload and keeps cut/restart control
on the external geometry inputs.

R148 adds an explicit static-vs-external agreement diagnostic at that same
boundary. When the diagnostic producer emits geometry in the same cycle as the
temporary external BFU hint, the top compares `headerPc`, `hsizeBytes`, and
`bsizeBytes` against the external `reducedBfu*` row and exposes match/mismatch
bits. This proves whether the partial static producer reproduces the replay
geometry before any later packet lets it drive `ReducedBfuBodyCutPredictor`.
The generated-RTL harness fails on any field mismatch and prints the comparable
and matched diagnostic counts. Control still uses the external geometry inputs
in R148.

R149 inserts `ReducedBfuResolvedBodyEndOwner` between the external resolved
body-end hint and `ReducedBfuStaticGeometryProducer`. The owner matches the
resolved event to the active static header, carries resolved `hsize`, computes
model-style saturated `bsize`, and exposes rejected-event diagnostics. Control
still uses the external geometry inputs in R149; the new owner is a replaceable
handoff target for a later real branch/BFU resolver.

R150 lets `ReducedBfuBodyCutPredictor` consume static geometry payload through
`ReducedBfuGeometryPredictionLatch`. The external `reducedBfu*` row still
supplies the temporary cut-arm, resolved body-end event, and replay oracle
checked by the static/external diagnostics.

R152 factors the cut-arm acceptance rule into `ReducedBfuBodyCutArm`. The top
now sends the latched static prediction and the external replay arm into that
module, then forwards the accepted prediction-owned payload to
`ReducedBfuBodyCutPredictor`. The external row is still temporary, but it no
longer appears as inline top-level comparison logic.

R153 adds `ReducedBfuLocalBodyWindow` and changes body-cut eligibility to
resolved loop evidence. The local body window arms only when the learned header
is visible in F4 and holds that geometry until a cut fires. The prediction latch
now learns only from accepted `ReducedBfuResolvedBodyEndOwner` rows, not from
every static boundary geometry row, because the same conditional `BSTART` can
be taken backward or fall through at runtime. The body-cut predictor receives
local-window geometry when trained and resolved body-end geometry as a
same-cycle cold-cut fallback. `ReducedBfuBodyCutArm` remains a diagnostic
comparison between the remembered prediction and the temporary replay oracle.

R154 adds `ReducedBfuResolvedBodyEndSource` before the resolved owner. The
source still converts replay `headerPc`/`hsize`/`bsize` into the cold fallback
body-end PC, but it can prioritize RTL local-cut feedback once available.
R155 adds `ReducedBfuResolvedBodyEndPending` so that local-cut feedback is no
longer a one-cycle pulse. The pending owner holds `headerPc`/`hsize`/body-end
state until the next matching replay-qualified candidate consumes it, drops
stale mismatches, and exposes pending/consume/drop diagnostics. Replay is still
the timing oracle for this packet; the selected geometry can now come from
retained runtime state when the candidate proves it is the same body end.
R156 adds `ReducedBfuPendingRuntimeBodyEndCandidate` as a diagnostic-only
promotion checker. It exposes when retained runtime feedback already matches
the current active header and compares that replay-free candidate against the
temporary replay oracle, but it does not feed the source arbiter yet.
R157 promotes that candidate into `ReducedBfuResolvedBodyEndSource`, so local
runtime body-end feedback no longer waits for replay timing once it matches the
active header. `ReducedBfuPromotedRuntimeBodyEndOracle` keeps replay as the
proof surface by retaining promoted runtime events until a later replay row
matches or reports mismatch/overwrite.

R158 is a verification scale-out of the same promoted runtime source rather
than a new RTL owner. Live CoreMark captures at 6000 and 10000 raw QEMU rows
both pass the generated-RTL comparator. The 10000-row run compares 8851
normalized QEMU/DUT rows with zero mismatches and reports 588 promoted runtime
body-end oracle replay matches with no mismatches or overwrites. The manifest
records clean LinxCore, LinxCoreModel, and QEMU provenance; the superproject
entry is dirty only because unrelated bring-up files were left unstaged.

R159 extends the same reduced live CoreMark path to 20000 raw QEMU rows. The
generated-RTL comparator normalizes 18138 QEMU/DUT rows and passes with 18137
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 1302 promoted runtime body-end oracle replay comparisons
all match, with zero mismatches and zero overwrites.

R160 extends the same reduced live CoreMark path to 50000 raw QEMU rows. The
generated-RTL comparator normalizes 45995 QEMU/DUT rows and passes with 45994
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 3445 promoted runtime body-end oracle replay comparisons
all match, with zero mismatches and zero overwrites.

R161 extends the same reduced live CoreMark path to 100000 raw QEMU rows. The
generated-RTL comparator normalizes 92423 QEMU/DUT rows and passes with 92422
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 7017 promoted runtime body-end oracle replay comparisons
all match, with zero mismatches and zero overwrites.

R162 extends the same reduced live CoreMark path to 200000 raw QEMU rows. The
generated-RTL comparator normalizes 185281 QEMU/DUT rows and passes with
185280 compared rows and zero mismatches. BFU runtime-promotion diagnostics
remain clean at that depth: 14159 promoted runtime body-end oracle replay
comparisons all match, with zero mismatches and zero overwrites.

R163 extends the same reduced live CoreMark path to 400000 raw QEMU rows. The
QEMU capture produced 399866 reduced expected rows; after resuming the
fetch/Verilator stage from the captured trace, the generated-RTL comparator
normalizes 370995 QEMU/DUT rows and passes with 370994 compared rows and zero
mismatches. BFU runtime-promotion diagnostics remain clean at that depth:
85341 static external matches, zero body-cut arm mismatches, and 28445
promoted runtime body-end replay comparisons all match with zero replay
mismatches or overwrites.

R164 extends the same reduced live CoreMark path to 800000 raw QEMU rows. The
QEMU capture produced 799866 reduced expected rows, the generated-RTL
comparator normalizes 742423 QEMU/DUT rows, and the run passes with 742422
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 171057 static external matches, zero body-cut arm
mismatches, and 57017 promoted runtime body-end replay comparisons all match
with zero replay mismatches or overwrites.

R165 extends the same reduced live CoreMark path to 1600000 raw QEMU rows. The
QEMU capture produced 1599866 reduced expected rows, the generated-RTL
comparator normalizes 1485281 QEMU/DUT rows, and the run passes with 1485280
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 342483 static external matches, zero body-cut arm
mismatches, and 114159 promoted runtime body-end replay comparisons all match
with zero replay mismatches or overwrites.

R166 extends the same reduced live CoreMark path to 3200000 raw QEMU rows. The
QEMU capture produced 3199866 reduced expected rows, the generated-RTL
comparator normalizes 2970995 QEMU/DUT rows, and the run passes with 2970994
compared rows and zero mismatches. BFU runtime-promotion diagnostics remain
clean at that depth: 685341 static external matches, zero body-cut arm
mismatches, and 228445 promoted runtime body-end replay comparisons all match
with zero replay mismatches or overwrites.

R178 adds a named non-default marker-row harness,
`LinxCoreFrontendFetchRfAluMarkerRowsTraceTop`. The default live CoreMark top
still runs with `skipBlockMarkers=true` because the current Verilator/QEMU
comparison path treats legal `BSTART`/`BSTOP` markers as DUT-only skip rows.
The marker-row harness instantiates the same live fetch, dense F4, reduced
rename/ROB, RF, issue, and ALU path with `skipBlockMarkers=false` and
`useMarkerDecodeContext=true`, so admitted marker rows can reserve and
rename-update ROB rows while following scalar rows receive the decode-time
active BID selected by `BlockMarkerDecodeContext`. This wrapper is an
elaboration/proof surface for the full marker-row migration; it is not yet the
promoted live QEMU comparator target.

R179 adds the first generated-RTL smoke for that marker-row harness. The smoke
emits `LinxCoreFrontendFetchRfAluMarkerRowsTraceTop`, builds it with
Verilator, drives the first CoreMark dense fetch window, and requires that the
first selected row is admitted as a real marker row rather than consumed as a
skip row. It captures the marker row's selected full block BID and then proves
the following scalar row reuses the same BID. This is a focused marker-row
BID/admission proof, not a QEMU comparator migration: the default live
CoreMark wrapper still preserves skip-row semantics until marker rows have a
marker-aware compare/filter policy.

R180 adds that first marker-aware compare/filter policy behind an explicit
`--marker-rows` QEMU gate. The high-level QEMU reducer still tags legal block
markers as skip rows in the expected stream, but
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --marker-rows` now emits
`LinxCoreFrontendFetchRfAluMarkerRowsTraceTop`, passes
`--admit-marker-rows` to the shared generated-RTL driver, admits legal marker
rows into the ROB, validates their marker-shaped commits, and filters those
marker commits before comparing the scalar rows against the existing QEMU
reference stream. The first CoreMark prefix proof admits one marker row,
filters one marker commit, compares the following three scalar rows, and
passes with `marker_rows_admitted=1`, `marker_commits_filtered=1`,
`compared=3`, and `mismatches=0`. The default live top regression on the same
prefix remains in skip mode and passes with `marker_rows_admitted=0`,
`marker_commits_filtered=0`, `compared=3`, and `mismatches=0`. This is still a
non-default filtered comparator path, not the final live CoreMark top switch.
R192 extends marker-row mode through the repeated-loop 128-row CoreMark window.
While the marker-row wrapper is holding the dense queue at an admitted marker
drain barrier, the backend decode inputs are explicitly invalidated so older
scalar rows cannot be recommitted from the held dense slot payload. The
filtered marker-row gate now admits and filters 36 marker commits, compares 88
scalar/macro rows, and passes with zero mismatches. The default live top still
uses skip mode on the same short prefix, admits zero marker rows, and compares
the three-row prefix with zero mismatches.

R194 scales the same admitted-marker comparator to a 512-row CoreMark capture.
The first probe exposed a harness tail-prefix issue rather than an RTL
mismatch: the bounded expected stream can end on a legal admitted marker, and
that marker-shaped ROB commit may retire after the last compared scalar row.
The generated-RTL driver now drains only pending marker commits at end of
stream, validates them against the expected skip rows, and still fails if any
scalar commit appears past the captured prefix. The promoted 512-row marker-row
gate admits and filters 161 marker commits, compares 337 normalized QEMU/DUT
rows, and passes with zero mismatches.

R195 exposed a model-capacity mismatch after the full-BID cleanup fixes removed
the stale wrapped-BID rename failure. The 1024-row admitted-marker CoreMark gate
matched 157 normalized scalar rows, then stopped before QEMU row 227 at
`pc=0x4000557a` because the Chisel scalar GPR mapQ was full:
`decodeBlockedByRename=1`, `gprFree=63`, and `gprMapQFree=0`. LinxCoreModel
sizes GGPR rename independently with `ggpr_mapq_depth = 256`, while the reduced
top had tied scalar GPR mapQ depth to the local T/U sequence `mapQDepth = 32`.
R196 splits those capacities: local T/U ROBID sequences remain compact, and the
marker-row emitters instantiate `gprMapQDepth = 256` with `physRegs = 128`.

R197 shows the next infrastructure blocker for the exact same 1024-row gate.
The model-sized scalar GPR mapQ emits a large `GPRRenameCheckpoint.sv`; after
the release/live-mask rewrite the Chisel emit drops from roughly 108 seconds to
roughly 20 seconds and Verilator RSS drops from roughly 4.7 GB to roughly
1.6 GB, but Verilator still remains in front-end processing before creating
`obj_dir` for the 256-depth marker-row top. Treat this as a generated-model
compile-cost issue, not as a CoreMark comparator mismatch.

R198 resolves that compile-cost blocker by splitting the largest
`GPRRenameCheckpoint` replay and commit scans into helper modules. The
256-depth marker-row smoke builds in Verilator, reporting 46 generated modules
and 73 C++ files. The 1024-row admitted-marker CoreMark gate now reaches DUT
comparison and fails at the next functional frontier instead of the old
`gprMapQFree=0` stall: `pc=0x400055be`, `insn=0x31f6`, expected source
`x6 = 296`, observed source `x6 = 0`, source physical tags `(46,20,0)`, and
last writer for physical tag 20 at `pc=0x40005576`, `insn=0xffe13319`,
writing `x6 = 0`. The saved JSONL traces contain the first 181 matched rows;
the failing row is reported by the Verilator comparator before it is appended
to `traces/dut.chisel.jsonl`. The next packet should debug RF writeback,
readiness, or reduced source-value forwarding for the post-capacity marker-row
path, not scalar GPR mapQ capacity.

R202 closes that frontier. The R198 diagnostic printed source physical tags in
hex and was later superseded by ROB-keyed source diagnostics: the true stale
`x6` source was physical tag 32, while the latest architectural writer had
allocated a newer tag. The reduced marker-row path was restoring scalar GGPR
state from the checkpoint before the current block because block-stop cleanup
fed the current block BID into a checkpoint owner whose model rule restores
`flush.bid - 1`. The top now asks marker-stop cleanup to restore from the next
block BID, so `GPRRenameCheckpoint` restores the just-finished block's
post-rename checkpoint. The bridge also refreshes that checkpoint after every
accepted row in the reduced in-order path. This preserves adjacent
`C.SETRET` materialization through the first `FENTRY` save and lets the
1024-row marker-row CoreMark gate pass with 288 admitted marker commits
filtered and 665 normalized QEMU/DUT rows compared.

R227 scales the same admitted-marker path to a 6000-row CoreMark capture on
clean pushed LinxCore, LinxCoreModel, and QEMU SHAs. The gate captures 6000 raw
QEMU rows, extracts 5866 expected rows, admits and filters 728 marker commits,
normalizes 5138 QEMU/DUT rows, compares 5137 rows, and passes with zero
mismatches and no CBSTOP divergence. This is still reduced live fetch/RF/ALU
evidence rather than full LSU/full-DUT CoreMark completion, but it makes the
post-block checkpoint restore, marker-only BROB drain, and row-order cleanup
invariants the current larger-window baseline for the next Chisel loop packet.

R228 extends that baseline to an 8192-row CoreMark capture. The run extracts
8058 expected rows, admits and filters 885 marker commits, normalizes 7173
QEMU/DUT rows, compares 7172 rows, and passes with zero mismatches and no
CBSTOP divergence. BFU diagnostics also remain clean across the larger window:
1383 static/external matches, 1382 accepted body-cut arms with zero arm
mismatches, and 459 promoted runtime body-end oracle replay matches.

R229 extends the same admitted-marker baseline to a 12288-row CoreMark capture.
The run extracts 12154 expected rows, admits and filters 1177 marker commits,
normalizes 10977 QEMU/DUT rows, compares 10976 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
2259 static/external matches, 2258 accepted body-cut arms with zero arm
mismatches, and 751 promoted runtime body-end oracle replay matches.

R230 extends the same admitted-marker baseline to a 16384-row CoreMark capture.
The run extracts 16250 expected rows, admits and filters 1470 marker commits,
normalizes 14780 QEMU/DUT rows, compares 14779 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
3138 static/external matches, 3137 accepted body-cut arms with zero arm
mismatches, and 1044 promoted runtime body-end oracle replay matches.

R231 extends the same admitted-marker baseline to a 24576-row CoreMark capture.
The run extracts 24442 expected rows, admits and filters 2055 marker commits,
normalizes 22387 QEMU/DUT rows, compares 22386 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
4893 static/external matches, 4892 accepted body-cut arms with zero arm
mismatches, and 1629 promoted runtime body-end oracle replay matches.

R232 extends the same admitted-marker baseline to a 32768-row CoreMark capture.
The run extracts 32634 expected rows, admits and filters 2640 marker commits,
normalizes 29994 QEMU/DUT rows, compares 29993 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
6648 static/external matches, 6647 accepted body-cut arms with zero arm
mismatches, and 2214 promoted runtime body-end oracle replay matches.

R233 extends the same admitted-marker baseline to a 49152-row CoreMark capture.
The run extracts 49018 expected rows, admits and filters 3811 marker commits,
normalizes 45207 QEMU/DUT rows, compares 45206 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
10161 static/external matches, 10160 accepted body-cut arms with zero arm
mismatches, and 3385 promoted runtime body-end oracle replay matches.

R234 extends the same admitted-marker baseline to a 65536-row CoreMark capture.
The run extracts 65402 expected rows, admits and filters 4981 marker commits,
normalizes 60421 QEMU/DUT rows, compares 60420 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
13671 static/external matches, 13670 accepted body-cut arms with zero arm
mismatches, and 4555 promoted runtime body-end oracle replay matches.

R235 extends the same admitted-marker baseline to a 98304-row CoreMark capture.
The run extracts 98170 expected rows, admits and filters 7321 marker commits,
normalizes 90849 QEMU/DUT rows, compares 90848 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
20691 static/external matches, 20690 accepted body-cut arms with zero arm
mismatches, and 6895 promoted runtime body-end oracle replay matches.

R236 extends the same admitted-marker baseline to a 131072-row CoreMark capture.
The run extracts 130938 expected rows, admits and filters 9662 marker commits,
normalizes 121276 QEMU/DUT rows, compares 121275 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
27714 static/external matches, 27713 accepted body-cut arms with zero arm
mismatches, and 9236 promoted runtime body-end oracle replay matches.

R237 extends the same admitted-marker baseline to a 196608-row CoreMark capture.
The run extracts 196474 expected rows, admits and filters 14343 marker commits,
normalizes 182131 QEMU/DUT rows, compares 182130 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
41757 static/external matches, 41756 accepted body-cut arms with zero arm
mismatches, and 13917 promoted runtime body-end oracle replay matches.

R238 extends the same admitted-marker baseline to a 262144-row CoreMark capture.
The run extracts 262010 expected rows, admits and filters 19024 marker commits,
normalizes 242986 QEMU/DUT rows, compares 242985 rows, and passes with zero
mismatches and no CBSTOP divergence. BFU diagnostics remain aligned:
55800 static/external matches, 55799 accepted body-cut arms with zero arm
mismatches, and 18598 promoted runtime body-end oracle replay matches.

R239 starts the reduced-top LSU/STQ integration boundary. The top now
instantiates `ReducedStoreExecResultBridge`, which buffers reduced ALU store
completion sidebands and matches them to `StoreDispatchSTQPath` STA/STD queue
heads by `(bid,rid,stid)`. The path is gated by
`useReducedStoreDispatchStq=false` by default, so existing CoreMark reduced
gates still use the proven sideband-comparison path and keep the bridge
flushed.

R240 adds `ReducedStoreCommitFreeOwner` behind the same opt-in gate. It
observes committed store rows, matches them to resident STQ rows by ROB RID
wrap/value and STID, marks the STQ row committed, and frees it on a later
accepted free command. This is a reduced top-only lifecycle bridge. It prevents
the optional STQ path from filling permanently, but it does not issue stores to
SCB, mutate memory, implement store forwarding, or replace the full
`STQCommitDrain`/`STQSCBCommitPath` memory-side contract.

R241 replaces that top-level direct-free assumption with the existing
memory-side Chisel owners around the same `StoreDispatchSTQPath` bank. Accepted
marks enqueue `STQCommitDrain`; the reduced top gates drain issue with
`SCBRowBank.modelBatchReady`; and `StoreDispatchSTQPath.commitFreeMask` is
driven only from `SCBRowBank.commitFreeMask` for accepted `last` fragments.
The reduced top still ties the SCB environment to local-ready/write-hit values,
so this is an STQ lifecycle and SCB-admission packet, not full store memory
mutation, load forwarding, or MDB publication.

R242 adds `LinxCoreFrontendFetchRfAluReducedStoreTraceTop`, a generated-RTL
wrapper that sets `useReducedStoreDispatchStq=true` without changing the
default reduced CoreMark top or the marker-row top. The low-level harness
selects it with `FETCH_REDUCED_STORE_DISPATCH_STQ=1`, and the live QEMU ELF
wrapper selects it with `--reduced-store-dispatch-stq`. This switch is
intentionally mutually exclusive with marker-row mode until the reduced-store
and admitted-marker proof surfaces are combined deliberately. The first live
CoreMark/QEMU probe using this wrapper builds and reaches the first reduced
store-data check, where it fails at `pc=0x4000550e`: expected
`wdata=0x4000550a`, observed `wdata=0x0`. The next reduced-store packet should
repair that source-data provenance before using the opt-in path as replacement
evidence.

R243 fixes that provenance failure as redirect cleanup, not store formatting.
LinxCoreModel expands `FENTRY` into an `SDI` that stores architectural `R[m]`;
for the failing row, the save source was x10 written at `pc=0x40005506`. The
default skip-marker path had restarted fetch at the preceding marker stop but
also armed backend/rename cleanup from an invalid marker retire source, which
restored the scalar GPR map to identity and made `FENTRY` read physical tag 10.
The top now separates marker-only redirects from redirects that need backend
cleanup. In default skip-marker mode, marker-only redirects restart
fetch/F4/dense state only; execute redirects still clean backend state, and the
marker-row top can still clean from admitted marker-retire metadata. The
promoted reduced-store live CoreMark/QEMU gate captures 42 raw rows and passes
with 31 compared rows and zero mismatches.

R253 advances the same opt-in reduced-store path through the SDI store-pressure
frontier. The packet fixes indexed `OP_SD` operand order to match
LinxCoreModel (`SrcD`, `SrcL`, `SrcR`), projects OP_SD base/index into the
two-source QEMU row shape, bypasses reduced split-store T/U source sequence
lookup while preserving the sidecars for store/STQ owners, and changes
`ReducedStoreCommitFreeOwner` to match STQ rows by model `bid/gid/rid`
identity with a pending committed-store identity buffer. The live QEMU wrapper
`generated/r253-reduced-store-pending-commit-sdi-240-qemu-elf-xcheck` captures
240 raw rows and compares 162 normalized rows with zero mismatches. Reusing
the same generated RTL against the prior 726-row R252 expected stream consumes
231 marker skips, emits 495 architectural rows, and the neutral comparator
reports 495 compared rows with zero mismatches in
`generated/r253-reduced-store-pending-commit-sdi-240-qemu-elf-xcheck/report-768-direct`.

skill-evolve: update linx-core (OP_SD model-order source mapping plus reduced
store commit identity/pending-buffer triage are reusable LinxCore contracts).

R244 scales the same opt-in reduced-store path to a 96-row CoreMark/QEMU
prefix without further RTL changes. The gate captures 96 raw rows, reduces 92
expected rows, compares 67 normalized rows, and passes with zero mismatches.
This confirms the R243 cleanup guard carries the reduced-store STQ path past
the first FENTRY save; broader store memory mutation, forwarding, and MDB
publication remain future owners.

R245 doubles the same reduced-store live CoreMark/QEMU proof window to 192
captured rows. The gate captures 192 raw QEMU rows, reduces 188 expected rows,
normalizes and compares 131 QEMU/DUT rows, and passes with zero mismatches and
no CBSTOP divergence. This remains scale evidence for the optional reduced
STQ path; it does not change the ownership boundary for later memory mutation,
forwarding, or MDB publication work.

R246 doubles the same window again to 384 captured rows. The gate captures 384
raw QEMU rows, reduces 378 expected rows, normalizes and compares 258 QEMU/DUT
rows, and passes with zero mismatches and no CBSTOP divergence. The result
keeps the reduced-store path on the R243 cleanup invariant and preserves the
same future ownership split for memory mutation, forwarding, and MDB
publication.

R254-R257 resume from the R253 model-identity fix and scale the optional
reduced-store path without further RTL changes. R254 uses the live wrapper and
captures 1024 raw QEMU rows, reduces 953 expected rows, compares 665
normalized rows, and passes with zero mismatches. R255 reuses the R254
generated testbench against a fresh 2048-row bounded FIFO capture; strict
extraction reaches the known loop-re-entry boundary at raw row 1747, and the
existing `--allow-block-loop-reentry` mode then compares 1467 rows with zero
mismatches. R256 and R257 use the same reuse flow at 4096 and 8192 raw rows,
compare 3369 and 7172 rows, and pass with zero mismatches and no CBSTOP
divergence. These runs are scale evidence only; external memory mutation,
store-to-load forwarding, and MDB publication remain deferred owners.

skill-evolve: no-update (R254-R257 only scale the R253 reduced-store proof;
loop-re-entry extraction and the reusable store invariants are already
documented).

R258 replaces harness-owned store memory mutation in the optional reduced-store
path with `ReducedStoreMemoryOverlay`. The top records SCB-accepted committed
store fragments in RTL, routes execute-stage load lookup data through the
overlay, and adds `--disable-store-memory-mutation` to the shared Verilator
harness and QEMU wrapper so later loads must see store bytes from RTL state.
The 1024-row CoreMark/QEMU packet captured 1024 raw rows, reduced 953 expected
rows, skipped 288 marker rows, and compared 665 rows with zero mismatches in
`generated/r258-store-memory-overlay-1024-qemu-elf-xcheck/report-no-harness-store/crosscheck_manifest.json`.
This is still a reduced memory visibility bridge: accepted SCB fragments are
treated as committed/nonflushable state, but full cache state, store-forwarding
wait/replay, MDB publication, and TSO/fence completion remain deferred.

skill-evolve: no-update (R258 adds a module-local reduced memory overlay and
proof switch; the R253/R124 store contracts already cover the reusable
source-order and commit-identity rules).

R259 fixes the next no-harness-mutation failure by feeding committed store rows
directly into `ReducedStoreMemoryOverlay` without waiting for SCB acceptance. The
C++ model's `STQ::lookupForLoad` can assemble load bytes from committed
`storeCommitQ` state before `STQ::commit` has completed SCB acceptance, while
the R258 bridge only consumed SCB-accepted fragments. The top now creates up to
two overlay fragments per valid store commit row and still uses SCB accepted
`last` fragments as the only STQ free source. The 2048-row
no-harness-mutation CoreMark replay passes in
`generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/report/crosscheck_manifest.json`
with 1467 compared rows and zero mismatches.

skill-evolve: update linx-core (reduced store-memory overlays must include
ROB-committed/storeCommitQ-equivalent store visibility before SCB acceptance;
SCB accepted fragments alone are not sufficient for model-aligned load data).

R260 scales the same R259 RTL without source changes. Reusing the R259
generated testbench against the existing 4096-row loop-reentry expected stream
with `--disable-store-memory-mutation` normalizes 3370 QEMU/DUT rows, compares
3369 rows, and passes with zero mismatches in
`generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/report/crosscheck_manifest.json`.

skill-evolve: no-update (R260 only scales the R259 commit-row overlay proof;
no new reusable invariant, mandatory gate, or triage order changed).

R261 reuses the same R259 generated testbench against the existing 8192-row
loop-reentry expected stream with `--disable-store-memory-mutation`. The common
comparator normalizes 7173 QEMU/DUT rows, compares 7172 rows, and passes with
zero mismatches in
`generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/report/crosscheck_manifest.json`.

skill-evolve: no-update (R261 only scales the R259/R260 commit-row overlay
proof; no new reusable invariant, mandatory gate, or triage order changed).

R262 tightens the same overlay integration contract. The overlay helper applies
accepted request lanes in lane order, with later lanes overwriting overlapping
bytes. `STQCommitDrain` issues from the registered commit queue, so current
SCB accepted fragments are older than current ROB commit-row bypass fragments.
The reduced top therefore routes SCB accepted lanes before current commit-row
lanes while preserving R259's visibility rule that committed ROB stores can
feed load data before SCB acceptance.

skill-evolve: update linx-core (same-cycle reduced store-memory overlay lanes
must be routed old-to-young; current SCB drain lanes precede current ROB
commit-row bypass lanes even though commit-row visibility does not wait for
SCB acceptance).

R263 scales the R262 top without source changes using a fresh live QEMU
CoreMark capture and immutable sparse ELF memory. The optional reduced-store
wrapper captures 16384 raw QEMU rows, reduces 16250 expected rows, normalizes
14780 QEMU/DUT rows, compares 14779 rows, and passes with zero mismatches in
`generated/r263-reduced-store-overlay-old-to-young-16384-qemu-elf-xcheck/report/crosscheck_manifest.json`.

skill-evolve: no-update (R263 only scales the R262 reduced-store overlay proof;
no new reusable invariant, mandatory gate, or triage order changed).

R265 wires ready resident STQ forwarding after the committed-store overlay.
`ReducedScalarAluExecute` now publishes load lookup size, BID, and raw LSID
sidebands. The top converts the raw LSID into the same reduced `ROBID` shape
used by `StoreDispatchToSTQ`, feeds resident `StoreDispatchSTQPath` rows into
`ReducedStoreResidentForward`, and then supplies execute with the adapter's
load data. The adapter forwards only ready, scalar, non-cross-line resident
store bytes selected by model `(BID, LSID)` ordering. If the nearest older
resident store is not data-ready, the adapter reports wait diagnostics and
passes through the committed-overlay load data until a real replay owner exists.

skill-evolve: update linx-core (reduced resident store forwarding may feed
execute only for ready/no-wait cases; wait-hit loads must remain pass-through
until LIQ/LDQ replay control is wired).

R266 turns that wait diagnostic into reduced execute backpressure. A resident
wait-hit now drives `ReducedScalarAluExecute.loadLookupWaitBlocked`, keeping
the load resident in E and keeping the issue queue backpressured until the STQ
row's data becomes ready or a flush clears the pipe. This prevents the reduced
top from retiring committed-overlay pass-through data on wait hits while still
deferring real LIQ/LDQ row mutation and store-unit replay wakeup consumption.

skill-evolve: update linx-core (resident wait-hit loads must hold execute;
do not retire pass-through data while waiting for an older not-ready resident
store).

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `startValid`, `startPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Arms the live fetch source at a starting PC. |
| input | `restartValid`, `restartPc` | `Bool`, `UInt(pcWidth.W)` | pulse | Replaces the active fetch PC for a reduced restart. |
| input | `reducedBfuBodyValid`, `reducedBfuHeaderPc`, `reducedBfuHSizeBytes`, `reducedBfuBSizeBytes` | mixed | with live-F4 packet | Temporary reduced BFU body-geometry hint from loop-aware expected rows. R153 uses it as the resolved body-end source and oracle; accepted resolved geometry trains the prediction latch and can feed the body-cut predictor as a same-cycle cold-cut fallback. |
| output | `reducedBfuStaticGeometryValid`, `reducedBfuStaticHeaderActive`, `reducedBfuStaticLearnedFire`, `reducedBfuStaticResolvedLearnedFire` | `Bool` | diagnostic | Reduced static-predictor geometry diagnostics for the learned geometry feeding the BFU prediction latch. |
| output | `reducedBfuResolvedBodyEndAccepted`, `reducedBfuResolvedBodyEndHeaderMismatch`, `reducedBfuResolvedBodyEndInactiveDrop`, `reducedBfuResolvedBodyEndFlushDrop`, `reducedBfuResolvedBodyEndUnderflow` | `Bool` | diagnostic | R149 resolved body-end owner acceptance/drop diagnostics before the static producer consumes the normalized event. |
| output | `reducedBfuResolvedBodyEndSourceRuntimeSelected`, `reducedBfuResolvedBodyEndSourceReplaySelected`, `reducedBfuResolvedBodyEndSourceRuntimeReplay*` | `Bool` | diagnostic | R154/R155 resolved body-end source-selection and runtime/replay comparison diagnostics. |
| output | `reducedBfuResolvedBodyEndSourceRuntimePending*` | `Bool` | diagnostic | R155 pending runtime body-end feedback lifecycle, consume, drop-mismatch, and candidate comparison diagnostics. |
| output | `reducedBfuPendingRuntimeCandidate*` | `Bool` | diagnostic | R157 replay-free pending runtime candidate eligibility and same-cycle replay comparison diagnostics. This candidate now drives body-end source selection. |
| output | `reducedBfuPromotedRuntimeBodyEndOracle*` | `Bool` | diagnostic | R157 promoted runtime body-end replay oracle diagnostics: pending, capture, replay comparable/match/mismatch, and overwrite. |
| output | `reducedBfuStaticExternalComparable`, `reducedBfuStaticExternalMatch`, `reducedBfuStaticExternalMismatch`, `reducedBfuStaticExternalHeaderMismatch`, `reducedBfuStaticExternalHSizeMismatch`, `reducedBfuStaticExternalBSizeMismatch` | `Bool` | diagnostic | R148 agreement check between diagnostic static geometry and the external replay geometry that remains the temporary oracle. |
| output | `reducedBfuBodyCutArmComparable`, `reducedBfuBodyCutArmAccepted`, `reducedBfuBodyCutArmMismatch`, `reducedBfuBodyCutArmHeaderMismatch`, `reducedBfuBodyCutArmHSizeMismatch`, `reducedBfuBodyCutArmBSizeMismatch` | `Bool` | diagnostic | R153 diagnostic comparison between the latched resolved-body prediction and the temporary external oracle. It no longer controls body-cut eligibility. |
| output | `reducedBfuLocalBodyWindowActive`, `reducedBfuLocalBodyWindowArmFire`, `reducedBfuLocalBodyWindowReleaseFire`, `reducedBfuLocalBodyWindowArmSlot` | mixed | diagnostic | R153 local BFU body-window active, arm, release, and matched F4-slot observability. |
| input | `frontendFlushValid` | `Bool` | valid | Clears source packet state, F4, decode path, and reduced issue state. |
| input | `peId`, `threadId` | `UInt` | with source packet | Packet-owned PE/STID sidecars for decode/rename. |
| input | `fetchReqReady` | `Bool` | ready | Bounded fixture accepts a source PC request. |
| input | `fetchRespValid`, `fetchRespWindow` | `Bool`, `UInt(windowWidth.W)` | valid | Bounded fixture returns one instruction window for the issued PC. |
| input | `loadLookupData` | `UInt(64.W)` by default | combinational with `loadLookupValid` | Reduced base load data supplied by the harness from the sparse ELF memory image. In optional reduced-store mode this first passes through `ReducedStoreMemoryOverlay`, then through ready-only `ReducedStoreResidentForward`, before execute consumes it. |
| input | `rfInitValid`, `rfInitArchTag`, `rfInitData` | mixed | pulse | Preloads identity physical RF state for architectural scalar GPRs. |
| input | `deallocReady` | `Bool` | ready | Allows retired ROB rows to deallocate. |
| output | `fetchReqValid`, `fetchReqPc`, `fetchRespReady` | mixed | valid/ready | Live source PC request and response handshake. |
| output | `source*` | mixed | diagnostic | Source active, request, response, packet, PC advance, and packet UID observability. |
| output | `reducedBodyCutActive`, `reducedBodyCutFire`, `reducedBodyCutAdvanceBytes` | mixed | diagnostic | Temporary body-cut observability for the reduced loop-reentry bridge: active hint match, source handoff cycle, and cut-local source advance. |
| output | `f4ValidMask`, `f4SlotCount`, `decodeReady` | mixed | diagnostic | F4 slot shape and downstream decode readiness. |
| output | `denseSlotQueueInFire`, `denseSlotQueueOutFire`, `denseSlotQueueInSlotCount`, `denseSlotQueueCount`, `denseSlotQueueHeadSlot`, `denseSlotQueueFull`, `denseSlotQueueEmpty` | mixed | diagnostic | Reduced dense-slot bridge capture/drain and occupancy observability. |
| output | `selectedValid`, `selectedRobValue`, `selectedBlockBid` | mixed | diagnostic | Reduced selected decoded slot and allocated identities. |
| output | `gprFreeCount`, `gprMapQFreeCount` | mixed | diagnostic | Scalar GPR rename free-list and mapQ capacity counters surfaced for long CoreMark stall triage. |
| output | `executeCompleteSrcPhysValidMask`, `executeCompleteSrcPhysTag`, `executeCompletePc`, `executeCompleteInsn`, `executeCompleteWbReg` | mixed | diagnostic | Execute-completion physical-source and row identity diagnostics used to correlate QEMU/DUT divergences with rename map state. |
| output | `blockMarkerSkipFire`, `blockMarkerSkipValid`, `blockMarkerMixedPacket`, `blockMarkerBoundary`, `blockMarkerStop`, `blockMarkerPc`, `blockMarkerInsn`, `blockMarkerLen`, `blockMarkerTarget` | mixed | diagnostic | Reduced block-marker consume observability on a dense-slot drain. Marker slots advance without scalar ROB allocation or dec/ren push; older scalar issue activity may overlap in the same cycle. |
| output | `blockMarkerAllocReady`, `blockMarkerLifecycleConflict`, `blockMarkerAllocFire`, `blockMarkerAllocBid`, `blockMarkerActiveValid`, `blockMarkerActiveBid`, `blockMarkerActiveTarget`, `blockMarkerStopRedirectValid`, `blockMarkerStopRedirectPc` | mixed | diagnostic | Reduced marker lifecycle observability: BROB-only allocation readiness/fire for consumed `BSTART`, scalar-done conflict guard, active full BID/target reused by scalar rows, and marker-stop frontend restart target. |
| output | `robMarkerRetireSourceBid*`, `robMarkerRetireSourceRid*`, `robMarkerRetireSourceStid` | mixed | diagnostic | Marker-retire source identity used to debug restart cleanup ownership and distinguish block BID from row RID. |
| output | `decRenPushFire`, `decRenPopFire`, `decRenCount` | mixed | diagnostic | Decode-to-rename queue events and occupancy. |
| output | `decRenValid`, `decRenHeadPc`, `decRenHeadRidValid`, `decRenHeadRidValue` | mixed | diagnostic | Decode/rename queue head observability for live-gate stalls. |
| output | `renamedOutValid`, `renamedAccepted` | `Bool` | diagnostic | Rename output and issue-queue acceptance. |
| output | `rfReadReadyMask`, `rfAllReadReady`, `rfReadyMask` | mixed | diagnostic | Reduced physical RF read and ready-state observability. |
| output | `rfWriteValid`, `rfWriteTag`, `rfWriteData`, `rfStateError` | mixed | diagnostic | Execute-to-RF writeback and RF error reporting. |
| output | `localTReadyMask`, `localUReadyMask`, `localTPendingCount`, `localUPendingCount`, `localIncomingUsesLocal`, `localIncomingBlocked` | mixed | diagnostic | Reduced local T/U overlay readiness, whether the dec/ren head actually needs local operands, and producer-pending gate visible to issue selection and live-stall diagnostics. |
| output | `issueQueue*` | mixed | diagnostic | Reduced queue enqueue, pick, I1/I2, issue, cancel, release, occupancy, head row fields, source lane shape, and block-cause signals. |
| output | `executeAccepted`, `executeBusy`, `executeCompleteValid`, `executeCompleteRobValue` | mixed | diagnostic | Reduced ALU handoff and completion. |
| output | `loadLookupValid`, `loadLookupAddr`, `loadLookupPc`, `loadLookupDst*`, `loadLookupExecuteGranted`, `loadLookupReplayGranted` | mixed | diagnostic | Arbiter-selected reduced sparse-memory load lookup request. Execute has priority for `OP_C_LDI`/`OP_LDI` and resident wait-store replay-slot loads; replay-LIQ base lookup may drive the port only when execute is idle. The execute path also exposes the current load destination, and the replay path carries the row's BID/GID/RID internally. |
| output | `reducedStoreDispatchEnabled` | `Bool` | diagnostic | Static top parameter reflection for the optional reduced STQ dispatch path. |
| output | `reducedStoreExec*` | mixed | diagnostic | R239 reduced store execution-result bridge diagnostics: store completion capture, duplicate/blocked capture, STA/STD queue-head matches, valid mask, and buffer count. |
| output | `reducedStoreStaExecValid`, `reducedStoreStdExecValid` | `Bool` | diagnostic | R239 explicit STA/STD execution-result validity presented to `StoreDispatchSTQPath`. |
| output | `reducedStoreCommit*` | mixed | diagnostic | R240/R241 reduced commit owner diagnostics: store commit seen/matched/unmatched, matched STQ mask, pending mark/free masks and counts, mark command valid/index, SCB-backed free-mask valid/index compatibility signal, accepted/ignored mask results, and blocked flags. |
| output | `reducedStoreDrain*` | mixed | diagnostic | R241 reduced `STQCommitDrain` diagnostics: mark-accepted enqueue result, duplicate detect, issue mask/count, abstract drain early-free mask, queue count, empty, and order-error. |
| output | `reducedStoreScb*` | mixed | diagnostic | R241 reduced `SCBRowBank` diagnostics: model-batch readiness, accepted/stalled request masks, accepted-`last` commit-free mask/count, valid row mask, and entry count. |
| output | `reducedStoreMemoryValidMask`, `reducedStoreMemoryLineCount`, `reducedStoreMemoryLoadForwardMask`, `reducedStoreMemoryStoreDroppedMask` | mixed | diagnostic | R258/R259 reduced store-memory overlay occupancy, load-byte forward mask, and accepted-request drop diagnostics. The drop mask covers commit-row bypass lanes plus SCB accepted lanes. |
| output | `reducedStoreResidentForwardMask`, `reducedStoreResidentWaitMask`, `reducedStoreResidentEligibleMask`, `reducedStoreResidentReadyForward`, `reducedStoreResidentWaitBlocked`, `reducedStoreResidentWaitStore*`, `reducedStoreResidentLoadCrossesLine` | mixed | diagnostic | R265-R267 resident-STQ forwarding diagnostics after the committed-store overlay. Ready hits forward bytes; wait hits hold execute through `executeLoadWaitHold` and expose the selected not-ready store's index, BID, LSID, and PC for later replay wakeup wiring. |
| output | `reducedStoreResidentReplayWake*` | mixed | diagnostic | R268-R269 typed store-unit replay wakeup request diagnostics derived from a registered resident wait-store identity and live STQ row readiness. |
| output | `reducedLoadWaitReplay*` | mixed | diagnostic | R269/R271/R274/R275/R307/R311 registered reduced wait-store slot diagnostics. The slot remembers the held load and wait-store key, feeds that key back to `ResidentStoreReplayWakeup`, clears through `LoadReplayWakeup`, and publishes a one-cycle relaunch candidate carrying PC, address, size, replay-return signedness, destination, BID/GID/RID, reduced LSID, and forwarding snapshot `(youngestStoreId, youngestStoreLsId)` for future LIQ wiring; it does not drive a launch port. |
| output | `reducedLoadReplayQueue*` | mixed | diagnostic | R272-R283/R307/R311 finite relaunch-candidate queue diagnostics. The queue consumes the one-cycle wait-slot candidate, records pending/outgoing full load identity plus destination, replay-return signedness, and forwarding snapshot, and reports accepted/drop/full state. Default mode drains only exact held-load completion matches; opt-in replay-LIQ mode consumes only when `ReducedLoadReplayLiqAllocPath` accepts allocation. |
| output | `reducedLoadReplayDrain*` | mixed | diagnostic | R273/R274 diagnostic completion-drain match/mismatch signals used by the default reduced-store replay mode. Exact W2 load-completion matches consume the queued replay candidate; mismatches, including GID/RID sidecar mismatches, leave it pending for debug. |
| output | `reducedLoadReplayLiq*` | mixed | diagnostic | R279/R281/R282/R283/R294/R295/R296/R297/R298/R299/R300/R301/R302/R303/R304/R305/R307/R308/R309/R310/R311/R312/R313/R314/R315/R316/R317/R318/R319/R320/R321/R322/R323/R324/R325/R326/R327/R328/R329/R330/R331/R332/R333/R334/R335/R336/R337/R338/R339/R340/R341/R342/R343/R344/R345/R346/R347/R348/R349/R350/R351/R352/R353/R354/R355/R356/R357/R358/R359/R360/R365/R366/R367/R368/R369/R370/R371/R372 opt-in replay-LIQ allocation, launch-selector, replay-return W2 side-effect, row-fill, and lifecycle diagnostics. When the replay-LIQ wrapper is selected, the queue head feeds `ReducedLoadReplayLiqAllocPath`, queue dequeue is driven only by LIQ allocation acceptance, and `reducedLoadReplayLiqLaunch*` reports which resident rows would satisfy the model `pickL1` data-hit predicate. R282 adds a path-local launch drive gate, R283 feeds its E2 store vector from resident STQ snapshots, R294 exposes selected launch-row PC/address/size, load ID, BID/GID/RID, load LSID, and request-byte mask, R295 shapes the selected row's scalar base-data lookup response into 64-byte line-data diagnostics, R296 adds execute-priority lookup arbitration plus replay grant/block diagnostics, R297 exposes `LoadReplayLaunchReadiness` candidate/base-data/source/return blockers, R298 exposes launch-drive/accept, repick/miss/resolved masks, E4 outcome, and LHQ-record-valid diagnostics, R299 exposes `LoadReplaySourceReturnReadiness` local-store-snapshot/future-SCB diagnostics, R300 exposes `LoadReplayReturnReadiness` source/return-pipe diagnostics, R301 exposes `LoadReplayReturnPipeSelect` mask/select diagnostics, R302 exposes `LoadReplayReturnPipePermit` pipe-budget diagnostics, R303 exposes `LoadReplayReturnPipeBudget` live-budget-arm diagnostics, R304 splits that budget arm from consumer/wakeup readiness, R305 exposes selected-row `specWakeup`/`stackValid` plus `LoadReplayReturnConsumerReady` LRET/mem-wakeup sink diagnostics, R307 exposes selected-row `returnSignExtend`, R308 exposes `LoadReplayReturnDataExtract` data/blocker diagnostics fed by selected-row address/size/sign plus grant-gated line data, R309 exposes `LoadReplayReturnPublishReady` data-plus-consumer join diagnostics, R310 exposes `LoadReplayReturnLretPayload` identity/data/pipe/wakeup-required diagnostics, R311 carries one renamed destination sideband from execute capture through the replay queue, LIQ row, selected launch diagnostics, and LRET-payload diagnostics, R312 exposes a diagnostic GPR writeback candidate, R313 exposes a diagnostic ready-table/issue-wakeup candidate from that payload, R314 exposes the execute-priority reduced RF writeback arbiter while replay writes remain disabled, R315 exposes a side-effect readiness join for LRET/writeback/wakeup sinks, R316 exposes the explicit live-disabled publish-control fire point, R317 exposes the post-fire LRET/writeback/wakeup request vector, R318 exposes dormant LRET sink FIFO capacity/occupancy/blocker diagnostics behind that request vector, R319 exposes future IEX-side drain-permit diagnostics while keeping real FIFO drain tied low, R320/R321 expose pre-mutation `IEX::setMemData` admission diagnostics using the read-only ROB row-status lookup, R322 exposes the diagnostic E4 insert-shaped payload, R323 inserts a reduced scalar ROB resolve-data request boundary before that pipe-insert payload, R324 inserts the post-resolve lane-completion permit before pipe insert, R325 inserts the MEM-IEX TLOAD sub-instruction completion permit before pipe insert, R326 inserts the final load-return metadata boundary before pipe insert, R327 inserts timing/stat sideband intent before pipe insert, R328 exposes scalar LDA/vector AGU E4 residency intent after pipe insert, R329 adds a dormant one-entry E4 residency slot fed only by live unblocked R328 writes, R330 adds a dormant E4-to-W1 advance/clear diagnostic that observes the slot but keeps advance disabled, R331 adds a dormant W1 stage slot fed by future advance, R332 adds a dormant W1-to-W2 advance/clear diagnostic, R333 adds a dormant W2 stage slot, R334 adds a dormant W2 completion/clear owner, R335 adds the dormant W2 resolve/writeback/wakeup readiness join, R336 adds a live-disabled W2 resolve-sink readiness boundary, R337 adds a live-disabled W2 writeback-sink readiness boundary, R338 adds a live-disabled W2 wakeup-sink readiness boundary, R339 adds the post-completion W2 side-effect request vector, R340 adds the dormant W2 resolve payload request, R341 adds the dormant W2 reduced GPR writeback payload request, R342 adds the dormant W2 wakeup payload request, R343 adds the dormant W2 side-effect payload-plan mask checker, R344 adds the dormant post-plan side-effect issue permit, R345 adds the dormant pre-completion side-effect completion permit/equivalence diagnostic, R346 adds the dormant per-sink side-effect fire vector, R347 adds the dormant resolve-fire payload boundary, R348 adds the dormant writeback-fire payload boundary, R349 adds the dormant wakeup-fire payload boundary, R350 adds the dormant post-fire completeness checker, R351 adds the dormant W2 clear-intent join, R352 adds the dormant W2 refill-readiness comparison, R353 adds the dormant W2 slot-replacement plan, R354 adds a disabled W2 same-cycle clear/refill storage mode, R355 routes W1 advance and W2 `replaceOnClear` through the advance-control owner, R356 adds the shared live-promotion request owner, R357 replaces the three direct W2 sink live-enable tie-offs with one shared live-control owner, R358 adds a dormant writeback-arbiter input boundary, R359 adds a dormant resolve-arbiter input boundary, R360 adds a dormant wakeup-arbiter input boundary, R365/R366/R367 add clear/commit identity, commit-row candidate, and row-fill enable owners, R368/R369/R370 add the resolved-LIQ-row lifecycle guard, clear selector, and lifecycle request owner, R371 adds the lifecycle commit permit, and R372 adds the commit-row trace-source provider boundary while FIFO drain, ROB mutation, branch-recovery side effects, tile-SCB requests, pipe-cycle/stat storage, W2 side effects, W2 live clear/refill, live W2 same-cycle storage replacement, live RF replay writeback, live ROB/PE replay resolve, live ready-table/issue wakeup, replay-row lifecycle clear, and live pipe residency remain disabled; launch remains disabled because those sinks and the live publish gate are inactive. |
R375 extends the same replay-LIQ namespace with
`reducedLoadReplayLiqLaunchSelectedSourceTrace*` diagnostics. Execute captures
RF-derived load `src0/src1` operand traces, the reduced wait slot and relaunch
queue carry them as candidate fields, LIQ allocation stores them in row state,
and `LoadInflightLaunchSelect` republishes the selected row's source trace.
R376 carries that source-trace payload through `LoadReplayReturnLretPayload`,
the LRET sink entry, `LoadReplayReturnIexDataCandidate`,
`LoadReplayReturnIexPipeInsertCandidate`, the E4 residency slot, W1 slot, and
W2 slot, then exposes
`reducedLoadReplayLiqLretPayloadSourceTrace*` and
`reducedLoadReplayLiqLretPipeW2SlotSourceTrace*` diagnostics. R377 connects
that resident W2 source payload to the W2 commit-row trace-source provider
while leaving ROB-row source trace lookup disabled and row replacement gated by
the existing row-fill, identity, and lifecycle controls.
R361/R362 continue the same replay-LIQ diagnostic namespace by routing
pre-arbiter live enables through the shared W2 live-control owner and feeding
the reduced RF writeback arbiter's replay side from the W2 writeback boundary.
The shared live request remains false, so these additions do not enable live
RF replay writeback.
R334 adds `reducedLoadReplayLiqLretPipeW2Completion*` diagnostics under the
same replay-LIQ namespace. These signals observe the R333 W2 slot, classify
resolve/writeback/wakeup requirements, and report the dormant W2 clear point;
completion is held low because the R335 W2 side-effect readiness join still
consumes live-disabled resolve, writeback, and wakeup sink readiness owners.
R335 adds `reducedLoadReplayLiqLretPipeW2SideEffect*` diagnostics under the
same namespace. These signals expose the named W2 resolve/writeback/wakeup
readiness join that feeds W2 completion while all downstream W2 sinks remain
not-ready.
R345 adds `reducedLoadReplayLiqLretPipeW2SideEffectCompletionPermit*`
diagnostics under the same namespace. These signals expose the pre-completion
required mask, missing-ready mask, permit, and equivalence with the R335
readiness join without changing the W2 clear path.
R336 adds `reducedLoadReplayLiqLretPipeW2ResolveSink*` diagnostics under the
same namespace. These signals arm an abstract W2 resolve sink but keep its
ready output false because live W2 resolve mutation remains disabled.
R337 adds `reducedLoadReplayLiqLretPipeW2WritebackSink*` diagnostics under the
same namespace. These signals arm abstract scalar RF writeback capacity but
keep the W2 writeback ready output false because live replay writeback remains
disabled.
R338 adds `reducedLoadReplayLiqLretPipeW2WakeupSink*` diagnostics under the
same namespace. These signals arm abstract ready-table/issue-wakeup capacity
but keep the W2 wakeup ready output false because live W2 wakeup mutation
remains disabled.
R357 adds `reducedLoadReplayLiqLretPipeW2SideEffectLiveControl*`
diagnostics under the same namespace. This owner computes the required
side-effect sink mask and drives the R336/R337/R338 sink live-enable inputs
plus the R358/R359/R360 pre-arbiter live-enable inputs, but the R363 atomic live
request remains tied false, so all W2 sinks and arbiter inputs stay
live-disabled.
R363 adds `reducedLoadReplayLiqLretPipeW2AtomicLiveRequest*` diagnostics under
the same namespace. This owner drives both the R357 side-effect live request and
the R356 promotion request from one top-level request gate, which remains tied
false in the current top.
R362 routes the reduced RF writeback arbiter's replay side from the R358 W2
writeback-arbiter input. The arbiter's replay enable is the same disabled W2
writeback live-control output, so replay RF writes still cannot select the
single RF write port.
R339 adds `reducedLoadReplayLiqLretPipeW2SideEffectRequest*` diagnostics under
the same namespace. These signals expose the post-completion
resolve/writeback/wakeup request vector and invalid-shape blockers while
completion remains dormant behind live-disabled W2 sinks.
R340 adds `reducedLoadReplayLiqLretPipeW2ResolveRequest*` diagnostics under
the same namespace. These signals shape the future W2 `PEResolveBus` identity
and payload from the resident W2 slot, but remain dormant because the R339
resolve request never fires while W2 sinks are live-disabled.
R341 adds `reducedLoadReplayLiqLretPipeW2WritebackRequest*` diagnostics under
the same namespace. These signals shape the future reduced scalar RF writeback
request from the resident W2 slot, but remain dormant because the R339
writeback request never fires while W2 sinks are live-disabled.
R342 adds `reducedLoadReplayLiqLretPipeW2WakeupRequest*` diagnostics under
the same namespace. These signals shape the future ready-table/issue-wakeup
payload from the resident W2 slot, but remain dormant because the R339 wakeup
request never fires while W2 sinks are live-disabled.
R343 adds `reducedLoadReplayLiqLretPipeW2SideEffectPayloadPlan*` diagnostics
under the same namespace. These signals compare the required side-effect mask,
post-completion request mask, and shaped payload-valid mask without enabling
live sink mutation.
R344 adds `reducedLoadReplayLiqLretPipeW2SideEffectIssuePermit*` diagnostics
under the same namespace. These signals join the coherent payload plan with
live-gated resolve, writeback, and wakeup sink readiness without feeding W2
completion, W2 clear, or live sink mutation.
R346 adds `reducedLoadReplayLiqLretPipeW2SideEffectFireVector*` diagnostics
under the same namespace. These signals convert the accepted side-effect mask
into per-sink resolve, writeback, and wakeup fire pulses while suppressing all
fire outputs on request or payload mismatch.
R347 adds `reducedLoadReplayLiqLretPipeW2ResolveFirePayload*` diagnostics
under the same namespace. These signals join the R346 resolve fire pulse with
the R340 resolve request payload before future ROB/PE resolve mutation, but
remain observational and do not feed W2 clear or live side effects.
R359 adds `reducedLoadReplayLiqLretPipeW2ResolveArbiterInput*` diagnostics
under the same namespace. These signals observe the R347 fire payload at the
future ROB/PE resolve-arbiter boundary, but `liveEnable` comes from the R357
live-control owner whose request remains false, so no replay load can publish
a live resolve side effect.
R364 adds `reducedLoadReplayLiqLretPipeW2RobCompleteSource*` diagnostics and
`robCompleteArbiter*` diagnostics. These signals name the dormant replay W2
ROB completion-port source, feed its structural port readiness back to the W2
resolve sink, and merge execute/replay completion before `DecodeRenameROBPath`
with execute priority. Because R363 still ties the atomic live request off,
the replay source cannot complete a row in the integrated top.
R365 adds `reducedLoadReplayLiqLretPipeW2ClearCommitGuard*` diagnostics. These
signals check that the resident W2 slot RID, resolve-fire RID, and replay ROB
completion value agree before a future live clear can be treated as
commit-backed; they do not feed W2 clear or replay-row lifecycle.
R366 adds `reducedLoadReplayLiqLretPipeW2CommitRowCandidate*` diagnostics.
These signals shape the future replay load `CommitTraceRow` replacement from
resident W2 slot data plus deferred instruction/source metadata, feed its
currently false row-valid output into the replay ROB-complete source, and keep
row replacement disabled in the reduced top.
R372 adds `reducedLoadReplayLiqLretPipeW2CommitRowTraceSource*` diagnostics.
These signals name the deferred instruction metadata and source-trace provider
boundary feeding the R366 candidate. R373 adds a read-only ROB row commit-trace
lookup and feeds instruction raw/length into this boundary; R374 keeps optional
ROB-row source traces blocked before row completion because allocation rows do
not prove source operand data. R375 creates the row-owned replay-LIQ launch
source-trace diagnostics, R376 carries them through the replay-return pipe, and
R377 joins them from the resident W2 slot into this provider. Trace-source
`sourceReady` can now reflect row-owned RF-derived source provenance without
enabling ROB-row source-trace lookup.
R367 adds `reducedLoadReplayLiqLretPipeW2RowFillEnableControl*` diagnostics.
These signals replace the literal row-fill tie-off with a dormant owner that
requires the row candidate, side-effect fire completion, clear/commit identity,
live-clear readiness, and replay-row lifecycle readiness before row replacement
can be armed.
R368 adds `reducedLoadReplayLiqLretPipeW2ReplayRowLifecycle*` diagnostics.
These signals match the resident W2 slot identity against the current resolved
LIQ row image, expose the selected row-clear index when exactly one row matches,
and take final lifecycle readiness from the R369 clear-request owner.
R370 adds
`reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleRequestControl*`
diagnostics. These signals name the lifecycle clear request arm between the
disabled R363 atomic live request and the R369 clear-request selector.
R371 adds
`reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleCommitPermit*`
diagnostics. These signals name the final commit permit between R367 row-fill
enable and the R369 lifecycle clear mutation gate.
R369 adds `reducedLoadReplayLiqLretPipeW2ReplayRowClearRequest*` diagnostics.
These signals route the existing ResolveQ delayed LIQ clear request through a
named owner, keep it priority over a future W2 lifecycle clear request, and
keep the lifecycle request and commit permit dormant because R363 still
disables the atomic live request, so `clearResolvedValid` remains the existing
ResolveQ clear path.
R348 adds `reducedLoadReplayLiqLretPipeW2WritebackFirePayload*` diagnostics
under the same namespace. These signals join the R346 writeback fire pulse
with the R341 writeback request payload before future replay RF mutation, but
remain observational and do not feed writeback arbitration, RF state, W2 clear,
or live side effects.
R349 adds `reducedLoadReplayLiqLretPipeW2WakeupFirePayload*` diagnostics under
the same namespace. These signals join the R346 wakeup fire pulse with the
R342 wakeup request payload before future ready-table/issue-wakeup mutation,
but remain observational and do not feed wakeup mutation, W2 clear, or live
side effects.
R360 adds `reducedLoadReplayLiqLretPipeW2WakeupArbiterInput*` diagnostics
under the same namespace. These signals observe the R349 fire payload at the
future ready-table/issue-wakeup boundary, but `liveEnable` comes from the R357
live-control owner whose request remains false, so no replay load can wake
issue queues or mutate ready-table state.
R350 adds `reducedLoadReplayLiqLretPipeW2SideEffectFireComplete*`
diagnostics under the same namespace. These signals compare the R346 fire mask
with R347/R348/R349 fire-payload valids before future W2 clear and replay-row
lifecycle mutation, but remain observational and do not feed W2 clear or live
side effects.
R351 adds `reducedLoadReplayLiqLretPipeW2ClearIntent*` diagnostics under the
same namespace. These signals join the R334 pre-clear pulse, R345
pre-completion permit, and R350 post-fire completeness proof behind a
live-disabled clear enable, but remain observational and do not feed W2 slot
clear or replay-row lifecycle.
R356 adds `reducedLoadReplayLiqLretPipeW2PromotionControl*` diagnostics under
the same namespace. This owner feeds the live-clear enable for R351 and the
live-promotion enable for R355 from one top-level request bit, which remains
tied false through the R363 atomic live-request owner in the current top.
R352 adds `reducedLoadReplayLiqLretPipeW2RefillReady*` diagnostics under the
same namespace. These signals compare the current empty-only W1-to-W2 advance
gate with the future model-compatible empty-or-live-clear predicate, but remain
observational and do not feed W1 advance or W2 slot storage.
R353 adds `reducedLoadReplayLiqLretPipeW2SlotReplacePlan*` diagnostics under
the same namespace. These signals compare current W2 slot acceptance with the
future empty-or-live-clear write-accept predicate, but remain observational and
do not feed W1 advance, W2 clear, or W2 slot storage. R354 adds the W2 slot's
explicit `replaceOnClear` storage mode but ties it false in the top until live
W2 clear/refill promotion is ready.
R355 adds `reducedLoadReplayLiqLretPipeW2AdvanceControl*` diagnostics under
the same namespace. The control owner now drives the W1 advance enable and W2
slot `replaceOnClear` inputs. R356 adds
`reducedLoadReplayLiqLretPipeW2PromotionControl*` diagnostics and feeds R351
live clear plus R355 live advance promotion from the R363 atomic live request,
so the top still selects the current empty-only advance gate.

| output | `reducedLoadReplayResolveQueue*` | mixed | diagnostic | R285-R289 opt-in replay-LIQ ResolveQ diagnostics. `lhqRecord` from `ReducedLoadReplayLiqAllocPath` can append to `LoadResolveQueue`; the top exposes push, delayed clear, commit-window retire watermark, scalar-redirect precise flush identity, retire/prune mask/count, occupancy, valid-mask, and head conflict-row sidecars. Because launch remains disabled, the current fixture observes storage, retire, and recovery-prune wiring only; live MDB/recovery publication is deferred. |
| output | `reducedMdbConflict*` | mixed | diagnostic | R290 opt-in replay-LIQ MDB conflict diagnostics. The top feeds `MDBConflictDetect` with the accepted reduced STQ insert request, replay-LIQ resident rows, and ResolveQ conflict rows, then exposes store-valid, active/ResolveQ candidate masks, wait-store mask/count, selected source/index/ordinal, selected load/store BID and LSID identity, plus inner/nuke classification. These signals are diagnostic only; they do not yet drive recovery flush. |
| output | `reducedMdbFanout*` | mixed | diagnostic | R291-R293 opt-in replay-LIQ MDB fanout/learning diagnostics. Selected conflict records enqueue into `MDBQueueFanout.recordIn` with model confidence `1`; resident STQ rows feed the SU wakeup scan view; replay-LIQ launch acceptance forms a dormant `lookupIn` command boundary; and R293 exposes the still-inactive delete command/decay boundary plus phase-stall diagnostics. Delete producers remain tied off. The top exposes lookup ready/accept/process, delete ready/accept/process and decay result flags, LU/SU lookup-result hit/store-BID diagnostics, record ready/accept/process, BMDB report intent, SSIT valid mask, record errors, and SU match/wakeup diagnostics without mutating recovery or load wakeup state. |
| output | `executeLoadWaitHold` | `Bool` | diagnostic | R266 reduced execute hold for a load waiting on an older not-ready resident store. |
| output | `storeDispatch*`, `storeSta*`, `storeStd*`, `storeStq*` | mixed | diagnostic | R239-R241 store-dispatch queue, bridge-selection, STQ insert, mark/free, and resident-STQ observability from `DecodeRenameROBPath`. |
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
- `scalarSpValue`: reduced SP shadow initialized from RF preload of
  architectural x1, updated by visible scalar x1 writeback, restored by the
  `FRET.STK` RA-load sideband, used by `FENTRY`, and muxed into later scalar
  x1 source reads when the hidden restore is not represented as an RF write.
- `ReducedScalarIssueQueue` and `ReducedScalarIssuePick`: resident issue rows,
  source-ready snapshots, P1/I1/I2 timing, issued-entry lock, cancel, and W2
  release.
- `ReducedScalarAluExecute`: reduced scalar integer/immediate ALU, compressed
  ALU, reduced multiply, read-only C.LDI/LDI load lookup sidebands,
  C.SETC_EQ/C.SETC_NE/SETC_LTU no-writeback rows and branch-decision
  sidebands, PCR load lookup, SETC target/FRET.STK redirect, ADDW,
  SDI/SWI/SBI/C.SDI store sidebands, resident-load wait hold, and
  writeback-shaped completion.
- `ReducedStoreExecResultBridge`: optional R239 bridge from reduced ALU store
  completion sidebands to `StoreDispatchSTQPath` STA/STD execution-result
  inputs. It owns only capture and queue-head identity matching.
- `ReducedStoreCommitFreeOwner`: optional R240 bridge from committed store ROB
  rows to STQ mark commands. Its legacy direct-free mode is disabled in the
  reduced top after R241.
- `STQCommitDrain`: optional R241 ordered committed-store drain queue and
  request descriptor shaper around the existing `StoreDispatchSTQPath` bank.
- `SCBRowBank`: optional R241 SCB admission owner whose accepted `last`
  fragments produce the only STQ committed-row free mask in the reduced top.
- `ReducedStoreMemoryOverlay`: optional R258/R259 store-memory visibility
  bridge that records ROB-committed store fragments and SCB-accepted fragments,
  then overlays later reduced load lookup bytes.
- `ReducedStoreResidentForward`: optional R265 bridge that maps resident
  `StoreDispatchSTQPath` rows through `LoadStoreForwarding` and overlays ready
  resident store bytes after committed-store overlay data. It reports wait
  hits but leaves replay control to later LIQ/LDQ owner packets.
- `ResidentStoreForwardStoreSnapshot`: optional R283 reusable row-shape bridge
  that maps resident STQ rows into `LoadStoreForwardStore` vectors for both
  ready resident forwarding and replay-LIQ E2 store input plumbing.
- `LoadLookupArbiter`: optional R296 execute-priority shared sparse-memory
  lookup selector for reduced execute loads and replay-LIQ base-data requests.
- `LoadReplayBaseDataAlign`: optional R295 selected-row base-data shaper that
  converts a scalar sparse-memory response into the replay-LIQ
  `LoadForwardPipeline` line-data contract.
- `LoadReplayLaunchReadiness`: optional R297 parent launch arm predicate for
  replay-LIQ rows. It combines selector validity, replay lookup grant,
  base-data return, source return, and return-pipe readiness.
- `LoadReplaySourceReturnReadiness`: optional R299 source-return owner for
  replay-LIQ rows. It treats the local resident-store snapshot as the current
  reduced store source, keeps the future external SCB pending/returned boundary
  explicit, and feeds `LoadReplayLaunchReadiness.scbReturned`.
- `LoadReplayReturnConsumerReady`: optional R305 replay-return consumer owner
  for replay-LIQ rows. It keeps the always-required IEX LRET sink separate
  from the conditional mem-wakeup sink and feeds
  `LoadReplayReturnPipeBudget.consumerReady`.
- `LoadReplayReturnDataExtract`: optional R308 scalar replay-return data
  extractor for selected replay-LIQ rows. It consumes selected address, size,
  sign-extension, and grant-gated line data diagnostics.
- `LoadReplayReturnLretPayload`: optional R310 diagnostic LRET/MemReqBus
  subset formatter. It forwards selected identity, request, scalar return
  data, destination, pipe index, and wakeup-required sideband once extractor
  data is valid.
- `LoadReplayDestination`: optional R311 compact one-destination sideband for
  replay-return diagnostics. It is captured from the E-stage load uop and
  forwarded through wait-slot, replay queue, LIQ row, selected launch row, and
  LRET payload surfaces.
- `LoadReplayReturnWritebackCandidate`: optional R312 diagnostic writeback
  candidate owner. It converts valid LRET payload data plus a GPR destination
  into a future reduced RF write port shape without mutating the RF or ready
  table.
- `ReducedScalarWritebackArbiter`: R314 reduced RF write-port arbitration owner.
  Execute writeback has priority on the single reduced RF port. R362 wires the
  replay side from the W2 writeback-arbiter input, but replay selection remains
  disabled by the shared W2 side-effect live-control request until writeback,
  resolve, wakeup, clear/refill, and replay-row lifecycle mutation are promoted
  together.
- `LoadReplayReturnWakeupCandidate`: optional R313 diagnostic ready-table and
  issue-wakeup candidate owner. It converts valid LRET payload
  wakeup-required plus one destination into future wakeup kind/tag diagnostics
  without mutating readiness or issue queues.
- `LoadReplayReturnSideEffectReady`: optional R315 diagnostic side-effect
  readiness owner. It joins the always-required LRET sink with optional replay
  RF writeback and regular memory wakeup sink readiness, but its
  `sideEffectsReady` output is not yet fed into replay launch or publication.
- `LoadReplayReturnPublishControl`: optional R316 diagnostic publish-control
  owner. It joins payload validity, `LoadReplayReturnPublishReady`,
  `LoadReplayReturnSideEffectReady`, and an explicit live gate. The current top
  ties the live gate low, so `publishFire` cannot mutate replay-return state.
- `LoadReplayReturnPublishRequest`: optional R317 diagnostic publish-request
  vector owner. It fans out a future `publishFire` into LRET, reduced RF
  writeback, and wakeup request bits, but those requests stay false because the
  current top keeps `publishFire` disabled.
- `LoadReplayReturnLretSink`: optional R318 diagnostic LRET FIFO owner. It
  stores the typed LRET payload when a future post-fire LRET request is valid,
  exposes capacity and drain blockers, and keeps the drain side tied off until
  the IEX return-pipe consumer is implemented.
- `LoadReplayReturnIexDrainPermit`: optional R319 diagnostic IEX-side drain
  permit owner. It models the `receiveFromLSU`/`BackToPipe` predicate over
  FIFO-head validity and return-pipe occupancy, but the current top ties pipe
  occupancy full and does not feed the permit into the FIFO drain input.
- `LoadReplayReturnIexDataCandidate`: optional R320 diagnostic pre-mutation
  `IEX::setMemData` admission owner. It consumes the LRET sink head and drain
  permit, then requires a ROB current-row status lookup that is present and not
  need-flush before it copies returned-load fields into setMemData-shaped
  diagnostics. The current top still leaves the FIFO drain input low.
- `LoadReplayReturnRobResolveDataCandidate`: optional R323 diagnostic ROB
  resolve-data request owner. It consumes the admitted setMemData payload,
  emits reduced scalar all-destination data-valid, one-destination data-valid,
  and ret-lane increment intent, and leaves real ROB mutation plus vector lane
  accounting to future owner packets.
- `LoadReplayReturnLaneCompletionCandidate`: optional R324 diagnostic
  post-resolve lane-completion owner. It computes the future
  `retLaneAfterResolve` predicate and blocks scalar load-pair or vector/MEM
  multi-lane rows until `retLane >= realReqCnt`; the current reduced top ties
  one scalar lane complete.
- `LoadReplayReturnTloadCompletionCandidate`: optional R325 diagnostic TLOAD
  sub-instruction completion owner. It computes the future `subInstCnt`
  decrement, tile-SCB send/islast intent, and non-final TLOAD early-return
  predicate; the current reduced top ties ordinary non-TLOAD pass-through.
- `LoadReplayReturnFinalMetadataCandidate`: optional R326 diagnostic final
  load-return metadata owner. It marks the post-TLOAD candidate as load-return,
  records the currently no-op load-branch-resolve call point, and exposes
  pipe-cycle sideband validity before E4 insertion.
- `LoadReplayReturnTimingStatsCandidate`: optional R327 diagnostic timing/stat
  sideband owner. It copies MemReqBus return timing fields, stamps diagnostic
  `ldRntCycle`, and emits load-stat update intent before E4 insertion.
- `LoadReplayReturnIexPipeInsertCandidate`: optional R322 diagnostic E4
  insert-shaped payload owner. It consumes the admitted setMemData payload and
  IEX pipe choice while keeping the model MemReqBus `pipeID` sideband separate
  from the selected E4 insertion pipe.
- `LoadReplayReturnPipeResidencyCandidate`: optional R328 diagnostic LDA/AGU
  E4 pipe-residency owner. It consumes the R322 insert-shaped diagnostics,
  exposes scalar LDA versus vector AGU target intent and pipe-occupied blockers,
  and keeps live residency writes disabled in the current reduced scalar top.
- `LoadReplayReturnPipeResidencySlot`: optional R329 dormant returned-load E4
  residency state owner. It consumes only live unblocked R328 residency writes,
  captures the R322 insert-shaped payload in a one-entry slot, and exposes
  occupancy/blocker diagnostics while the top keeps the write path disabled.
- `LoadReplayReturnPipeResidencyAdvanceCandidate`: optional R330 dormant
  E4-to-W1 advance/clear owner. It observes the R329 slot, emits a slot clear
  only when future advance is enabled, and currently reports no-slot or
  advance-disabled diagnostics without changing fixture behavior.
- `LoadReplayReturnPipeW1Slot`: optional R331 dormant returned-load W1 stage
  owner. It consumes future R330 advance pulses plus the R329 slot payload and
  exposes W1 occupancy/blocker diagnostics while the top keeps advance disabled.
- `LoadReplayReturnPipeW1AdvanceCandidate`: optional R332 dormant W1-to-W2
  advance/clear owner. It observes the R331 W1 slot, emits clear only when a
  future W2 stage is ready, and currently remains advance-disabled.
- `LoadReplayReturnPipeW2Slot`: optional R333 dormant returned-load W2 stage
  owner. It consumes future R332 W1 advance pulses plus the R331 slot payload,
  feeds W2-empty readiness back to W1 advance, and holds the payload until
  later W2 side-effect completion can clear it.
- `LoadReplayReturnPipeW2CompletionCandidate`: optional R334 dormant W2
  completion/clear owner. It observes the W2 slot, classifies
  resolve/writeback/wakeup requirements, and emits W2 clear only when future
  side-effect sinks are ready.
- `LoadReplayReturnPipeW2SideEffectReady`: optional R335 dormant W2
  resolve/writeback/wakeup readiness join. It feeds W2 completion readiness
  from named sink predicates while those sinks remain tied not-ready.
- `LoadReplayReturnPipeW2SideEffectCompletionPermit`: optional R345 dormant W2
  pre-completion side-effect permit diagnostic. It mirrors the R335 readiness
  join as masks and a join-equivalence check without feeding W2 clear.
- `LoadReplayReturnPipeW2SideEffectLiveControl`: optional R357 dormant W2
  side-effect live-control owner. It computes the required mask and drives
  the R336/R337/R338 sink and R358/R359/R360 pre-arbiter `liveEnable` inputs
  from the R363 disabled atomic request.
- `LoadReplayReturnPipeW2AtomicLiveRequestControl`: optional R363 dormant W2
  live-request owner. It drives both R357 side-effect live request and R356
  promotion request from one disabled gate while exposing W2 side-effect, clear,
  and refill evidence diagnostics.
- `LoadReplayReturnPipeW2ResolveSinkReady`: optional R336 dormant W2
  resolve-sink readiness owner. It arms abstract resolve capacity but holds the
  actual ready output low until live W2 resolve mutation exists.
- `LoadReplayReturnPipeW2WritebackSinkReady`: optional R337 dormant W2
  writeback-sink readiness owner. It arms abstract scalar RF writeback
  capacity but holds the actual ready output low until live W2 replay writeback
  exists.
- `LoadReplayReturnPipeW2WakeupSinkReady`: optional R338 dormant W2
  wakeup-sink readiness owner. It arms abstract ready-table/issue-wakeup
  capacity but holds the actual ready output low until live W2 wakeup mutation
  exists.
- `LoadReplayReturnPipeW2SideEffectRequest`: optional R339 dormant W2
  post-completion request owner. It exposes resolve/writeback/wakeup request
  pulses and invalid-shape diagnostics while live W2 sinks remain disabled.
- `LoadReplayReturnPipeW2ResolveRequest`: optional R340 dormant W2 resolve
  payload owner. It validates W2 target and BID/GID/RID identity before future
  ROB/PE resolve mutation.
- `LoadReplayReturnPipeW2ResolveArbiterInput`: optional R359 dormant W2
  resolve-arbiter input boundary. It observes the R347 fire payload and keeps
  the future ROB/PE resolve side effect gated off behind the shared R357 live
  enable.
- `LoadReplayReturnPipeW2RobCompleteSource`: optional R364 dormant W2 ROB
  completion source. It turns the live-gated resolve RID into a replay
  completion-port request, reports execute-port pressure, and leaves
  `completeRowValid` false until a replay load commit-row fill owner exists.
- `ReducedRobCompletionArbiter`: R364 execute-priority completion-port arbiter.
  It merges ordinary execute completion with the dormant replay-W2 completion
  source before the path's marker-completion arbitration.
- `LoadReplayReturnPipeW2CommitRowCandidate`: optional R366 dormant replay load
  commit-row fill candidate. It shapes a future row replacement from resident
  W2 slot data, but instruction metadata and source trace remain absent.
- `LoadReplayReturnPipeW2CommitRowTraceSource`: optional R372 dormant
  instruction/source trace provider boundary. R373 feeds its instruction
  metadata from `ROBRowCommitTraceLookup`; R374 keeps ROB-row source traces
  completion-only and absent in this top; R375 source traces are visible only
  at the replay-LIQ launch diagnostic boundary.
- `LoadReplayReturnPipeW2RowFillEnableControl`: optional R367 dormant row-fill
  enable owner. It joins the shaped row candidate with side-effect fire,
  clear/commit identity, live-clear, and replay-row lifecycle prerequisites
  before allowing ROB row replacement; the current top keeps it disabled.
- `LoadReplayReturnPipeW2ReplayRowLifecycleReady`: optional R368 dormant
  replay-row lifecycle guard. It matches the W2 slot `(bid,gid,rid,loadLsId)`
  to exactly one resolved LIQ row and exposes row-clear diagnostics while the
  atomic live request remains disabled upstream.
- `LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl`: optional R370
  dormant lifecycle request owner. It derives the future lifecycle clear
  request arm from the atomic W2 live request plus row-fill candidate evidence.
- `LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit`: optional R371
  dormant lifecycle commit permit. It gates the selected lifecycle clear arm
  with row-fill enable before the clear selector may drive LIQ mutation.
- `LoadReplayReturnPipeW2ReplayRowClearRequest`: optional R369/R370/R371 dormant
  clear-request selector. It preserves existing ResolveQ delayed clear
  priority, exports lifecycle clear readiness to the R368 guard, and waits for
  the R371 commit permit before a future lifecycle clear can drive LIQ.
- `LoadReplayReturnPipeW2ClearCommitGuard`: optional R365 dormant W2
  clear/commit identity guard. It checks resident slot RID, resolve-fire RID,
  and replay ROB-completion value agree before a future live clear can be
  treated as commit-backed.
- `LoadReplayReturnPipeW2WritebackRequest`: optional R341 dormant W2 reduced
  RF writeback payload owner. It validates W2 target, BID/GID/RID identity,
  and scalar GPR destination before future replay RF mutation.
- `LoadReplayReturnPipeW2WritebackFirePayload`: optional R348 dormant W2
  writeback-fire payload boundary. It joins the R346 writeback fire pulse with
  the R341 writeback request payload before future replay RF mutation.
- `LoadReplayReturnPipeW2WritebackArbiterInput`: optional R358 dormant W2
  writeback-arbiter input boundary. It observes the R348 fire payload and keeps
  the future replay RF arbiter write gated off behind the shared R357 live
  enable.
- `LoadReplayReturnPipeW2WakeupRequest`: optional R342 dormant W2 wakeup
  payload owner. It validates W2 target, BID/GID/RID identity,
  wakeup-required state, and destination payload before future ready-table or
  issue-wakeup mutation.
- `LoadReplayReturnPipeW2SideEffectPayloadPlan`: optional R343 dormant W2
  side-effect atomicity checker. It joins required, requested, and shaped
  payload-valid masks before future live W2 sinks can clear the slot.
- `LoadReplayReturnPipeW2SideEffectIssuePermit`: optional R344 dormant W2
  side-effect issue accept diagnostic. It joins the coherent payload plan with
  live-gated resolve, writeback, and wakeup sink readiness, but remains
  observational to avoid feeding a post-completion payload path back into W2
  clear.
- `LoadReplayReturnPipeW2SideEffectFireVector`: optional R346 dormant W2
  per-sink fire-vector diagnostic. It converts the accepted issue mask into
  resolve/writeback/wakeup fire pulses only when request and payload masks
  still match, but remains observational and does not feed W2 clear.
- `LoadReplayReturnPipeW2ResolveFirePayload`: optional R347 dormant W2
  resolve-fire payload boundary. It joins the R346 resolve fire pulse with the
  R340 resolve request payload before future ROB/PE resolve mutation.
- `LoadReplayReturnPipeW2WritebackFirePayload`: optional R348 dormant W2
  writeback-fire payload boundary. It joins the R346 writeback fire pulse with
  the R341 writeback request payload before future replay RF mutation.
- `LoadReplayReturnPipeW2WakeupFirePayload`: optional R349 dormant W2
  wakeup-fire payload boundary. It joins the R346 wakeup fire pulse with the
  R342 wakeup request payload before future ready-table/issue-wakeup mutation.
- `LoadReplayReturnPipeW2WakeupArbiterInput`: optional R360 dormant W2
  wakeup-arbiter input boundary. It observes the R349 fire payload and keeps
  the future ready-table/issue-wakeup side effect gated off behind the shared
  R357 live enable.
- `LoadReplayReturnPipeW2SideEffectFireComplete`: optional R350 dormant W2
  post-fire completeness checker. It joins R346 fire mask with R347/R348/R349
  fire-payload valids before future W2 clear and replay-row lifecycle.
- `LoadReplayReturnPipeW2ClearIntent`: optional R351 dormant W2 clear-intent
  owner. It joins R334 pre-clear, R345 pre-completion permit, and R350
  post-fire completeness evidence behind the R356 live-disabled promotion
  control without feeding the W2 slot clear input.
- `LoadReplayReturnPipeW2RefillReady`: optional R352 dormant W2 refill-ready
  diagnostic. It compares the current empty-only W1-to-W2 advance gate with
  the future empty-or-live-clear predicate needed for same-cycle W2 clear and
  refill.
- `LoadReplayReturnPipeW2SlotReplacePlan`: optional R353 dormant W2
  slot-replacement plan. It observes the W1 write candidate, R351 clear
  intent, R352 future readiness, and current W2 slot acceptance before future
  same-cycle clear/refill storage mutation.
- `LoadReplayReturnPipeW2AdvanceControl`: optional R355 W2 advance-control
  owner. It selects current empty-only W1 advance versus future R352/R353
  same-cycle clear/refill readiness and drives W2 `replaceOnClear` from the
  R356 live-disabled promotion mode.
- `LoadReplayReturnPipeW2PromotionControl`: optional R356 dormant W2
  promotion switch. It drives R351 live clear and R355 live advance promotion
  from the R363 disabled atomic request.
- `LoadReplayReturnPublishReady`: optional R309 diagnostic join point between
  extracted data-valid and LRET/mem-wakeup consumer readiness. It does not feed
  launch, LRET, or wakeup sinks.
- `LoadReplayReturnPipeBudget`: optional R304/R305 budget/consumer-readiness
  owner for replay-LIQ return pipes. Current top wiring arms the budget under
  replay-LIQ enable and keeps both consumer sinks low.
- `LoadReplayReturnPipePermit`: optional R302 mask producer that consumes
  `LoadReplayReturnPipeBudget` and feeds `LoadReplayReturnPipeSelect`.
- `LoadReplayReturnReadiness`: optional R300 return-pipe readiness owner for
  replay-LIQ rows. It consumes source-return completion and a future IEX
  return-pipe availability signal, then feeds
  `LoadReplayLaunchReadiness.returnReady` and
  `ReducedLoadReplayLiqAllocPath.e2ReturnReady`.
- `ReducedLoadWaitReplaySlot`: optional R269/R271/R274/R275 one-row diagnostic bridge that
  registers a resident wait-store key while execute is held, feeds the key to
  `ResidentStoreReplayWakeup` after the live forwarder stops reporting a wait,
  consumes the typed wakeup through `LoadReplayWakeup`, and publishes the stored
  load PC/address/size plus BID/GID/RID/LSID identity and forwarding snapshot
  as a relaunch candidate when the wait clears.
- `ReducedLoadReplayRelaunchQueue`: optional R272 finite FIFO that consumes the
  one-cycle relaunch candidate, preserves FIFO order, reports full/drop
  diagnostics, and keeps the head pending until a later LIQ/issue consumer
  drives its dequeue handshake.
- `ReducedLoadReplayLiqAllocPath`: optional R279 allocation-only consumer for
  the relaunch queue head. It is enabled only by the replay-LIQ wrapper and
  consumes a queued candidate when `LoadInflightQueue` accepts allocation. R281
  also exposes its `LoadInflightLaunchSelect` diagnostics. R282 adds the child
  launch/E2/LHQ diagnostic boundary, and R283 wires a resident STQ snapshot
  into its E2 store vector while this top continues to tie `launchEnable` low.
- `LoadResolveQueue`: optional R285 replay-LIQ ResolveQ storage boundary. It
  accepts the path-local `lhqRecord` with top PE/STID/TID sidecars and exposes
  packed occupancy plus head `MDBConflictLoadEntry` diagnostics. R287 wires its
  retire input from the youngest committed ROB memory-order sidecar in the
  current commit window, using the stored pre-increment `lsID` snapshot as the
  ResolveQ retire watermark. R288 adds the queue-local precise `FlushBus`
  prune port. R289 drives that port from execute-owned scalar redirect cleanup
  when the redirecting uop supplies a valid reduced LSID sidecar, while
  LSID-less marker cleanup keeps the hard-clear fallback. R286 also makes the
  top own a one-entry delayed `clearResolved` request so the LIQ row is cleared
  only after ResolveQ accepts the resolved hit record. Live MDB publication
  remains a separate owner packet.
- `MDBConflictDetect`: optional R290 replay-LIQ diagnostic classifier. The top
  builds the store probe from `DecodeRenameROBPath.storeStqInsert` only when
  the reduced STQ insert is accepted, builds active load entries from
  replay-LIQ resident rows, and feeds resolved rows from `LoadResolveQueue`.
  The detector's selected record and masks are exposed as `reducedMdbConflict*`
  diagnostics without publishing recovery side effects.
- `MDBQueueFanout`: optional R291-R293 replay-LIQ diagnostic fanout/table owner. The
  selected conflict record becomes a bounded `recordIn` command with confidence
  `1`, resident STQ rows become the store-side wakeup scan view, and replay-LIQ
  launch acceptance builds a load-side `lookupIn` command. Delete commands stay
  inactive until failed-wait feedback producers exist, but the top now exposes
  delete queue readiness, process, decay result, and phase-stall diagnostics.
- `ReducedLoadReplayCompletionDrain`: optional R273/R274 diagnostic matcher that
  consumes the queued candidate when the same held load completes through W2
  with matching PC, address, size, BID, GID, RID, and reduced LSID.

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
Execute-owned scalar redirects, currently `FRET.STK`, share the reduced
frontend restart path and also pulse `DecodeRenameROBPath.scalarRedirectValid`
so stale active marker target state cannot leak into the return target body.
They additionally flush backend-only reduced state, publish ROB/rename cleanup,
and block-flush the redirected full BID. In default skip-marker mode,
marker-only redirects stay frontend-only: they restart fetch/F4/dense decode
state but do not discard older backend scalar work or synthesize rename cleanup
from a nonexistent marker retire source. In marker-row mode, admitted marker
rows can provide real retire-source metadata, so marker redirects may drive the
same backend cleanup path deliberately.
This top constructs the backend path with `reducedStoreDispatchBypass` by
default: store rows still flow through decode, rename, issue, ALU execute, ROB
completion, and QEMU-shaped store sideband comparison, but the reduced STQ
shell is not allowed to accumulate resident stores without a connected
STA/STD execution and lifecycle owner. R239 adds an opt-in
`useReducedStoreDispatchStq` parameter and `ReducedStoreExecResultBridge`.
When the parameter is true, ALU store completions are buffered and matched to
the store-dispatch queue heads as explicit `StoreDispatchExecResult` inputs.
R240 adds `ReducedStoreCommitFreeOwner`, which observes committed store ROB
rows and drives the STQ `markCommit` hook.
R241 wires accepted marks into `STQCommitDrain`, feeds drain memory request
descriptors into `SCBRowBank`, and drives the STQ `commitFreeMask` hook only
from SCB accepted `last` fragments. The top asserts the drain queue flush on
backend flush, start, restart, and while `useReducedStoreDispatchStq=false`.
When the parameter is false, the bridge is held flushed and captures no store
results, while the commit owner, drain, and SCB path are also held inactive.
R258 adds `ReducedStoreMemoryOverlay` after `SCBRowBank`, and R259 adds
commit-row bypass lanes in front of the SCB lanes. Each valid committed store
row can produce one or two overlay fragments depending on 64-byte-line
crossing; the later SCB accepted fragment for the same store is byte-identical
and idempotent. Reduced load lookup data is first formed by applying the
overlay over the immutable sparse ELF base data. R265 then feeds that result
through `ReducedStoreResidentForward`, which uses the load's `(BID, LSID)`
snapshot and resident STQ rows to overlay ready older store bytes. If the
resident selector reports a wait byte, the adapter still leaves data unchanged
but the top asserts `loadLookupWaitBlocked` into execute, so the load remains
resident in E and cannot retire pass-through data. R267 also exports the
selected not-ready resident store identity, including the STQ row index,
`(BID, LSID)`, and store PC copied from the renamed store uop. R268/R269 feed
a registered copy of that identity plus the live resident STQ row into
`ResidentStoreReplayWakeup`, which publishes a typed `LoadReplayWakeupRequest`
image when the selected row is still identity-matched and data-ready. The
registration is required because the live forwarder stops reporting a wait once
the store data becomes ready. `ReducedLoadWaitReplaySlot` then consumes that
typed request through `LoadReplayWakeup`, clears its diagnostic wait row, and
publishes a one-cycle relaunch candidate with the remembered load PC, address,
size, BID, GID, RID, reduced LSID, and forwarding snapshot
`(youngestStoreId, youngestStoreLsId)`. R272 feeds that pulse into
`ReducedLoadReplayRelaunchQueue`, so the reduced top owns a stable pending
relaunch-candidate boundary. In default mode, R273/R274 drains that queue only
when the same held load completes in W2 with matching PC, address, size, BID,
GID, RID, and reduced LSID; otherwise mismatch diagnostics leave the candidate
pending. In R279 replay-LIQ mode, the queue head instead feeds
`ReducedLoadReplayLiqAllocPath`, and `outReady` is asserted only when the child
`LoadInflightQueue` accepts allocation. The allocated LIQ row is diagnostic
residency only. R281 exposes selector diagnostics for the model `pickL1` row
eligibility predicates. R282 adds the path-local launch drive and E4/LHQ
diagnostic boundary. R283 feeds the path's E2 store vector from the same
`ResidentStoreForwardStoreSnapshot` conversion used by
`ReducedStoreResidentForward`, but the reduced top still does not relaunch the
load or wake dependent consumers. R285 wires the path-local `lhqRecord` output
into `LoadResolveQueue` and exposes queue/head diagnostics at the top. Since
`launchEnable` is still low, this proves the storage boundary and generated IO
without creating resolved load records in the current replay-LIQ fixture.
R294 exposes the selector's chosen row identity and 64-byte request mask at the
top boundary so later base-data lookup and arbitration packets can consume the
same row that `LoadInflightLaunchSelect` selected.
R295 instantiates `LoadReplayBaseDataAlign` for that selected row, converting
the shared 64-bit sparse-memory response shape into dormant
`e2BaseData/e2BaseValidMask` inputs plus base lookup diagnostics. That packet
kept `launchEnable`, `e2ScbReturned`, and `e2ReturnReady` inactive and did not
arbitrate replay against execute on the external load lookup port.
R296 inserts `LoadLookupArbiter` between execute and the replay base-data
request. Execute keeps priority on the shared sparse-memory lookup pins, while
replay-LIQ may drive those pins only when execute is idle. The top keeps raw
replay base-request diagnostics (`reducedLoadReplayLiqBaseLookupValid`) separate
from grant/data diagnostics (`reducedLoadReplayLiqBaseLookupGranted`,
`reducedLoadReplayLiqBaseLookupBlockedByExecute`, and
`reducedLoadReplayLiqBaseDataReturned`), and gates dormant
`e2BaseData/e2BaseValidMask/e2LoadDataReturned` with the replay grant.
R297 replaces the previous hardwired replay launch disable with
`LoadReplayLaunchReadiness`. That module now drives
`ReducedLoadReplayLiqAllocPath.launchEnable`, but that packet still tied
SCB/source return and return-pipe readiness low. R299 inserts
`LoadReplaySourceReturnReadiness` before the launch gate: local resident-store
snapshot readiness becomes the current reduced source-return sideband, the
future external SCB pending/returned boundary remains explicit, and return-pipe
readiness is separated from source return. R300 inserts
`LoadReplayReturnReadiness` after that source-return boundary. R301 inserts
`LoadReplayReturnPipeSelect` as the explicit pipe-mask/select owner feeding
that readiness gate. R302 inserts `LoadReplayReturnPipePermit` as the mask
producer. R304 splits `LoadReplayReturnPipeBudget` into a budget arm plus a
downstream consumer-readiness input. R305 inserts
`LoadReplayReturnConsumerReady` behind that input: every selected row needs the
future IEX LRET sink, and only rows that are not `specWakeup` and not
`stackValid` need the future mem-wakeup sink. The opt-in wrapper arms the
budget, while both sinks remain low until those IEX owners exist.
`launchEnable` therefore remains low while source-return, consumer, and
return-port blockers are visible as diagnostics.
R307 carries the opcode-derived `returnSignExtend` bit from
`ReducedScalarAluExecute` through the wait-slot candidate, replay queue, LIQ
allocation row, and selected launch-row diagnostics. That prepares the R306
`LoadReplayReturnDataExtract` input without enabling live LRET enqueue or
dependent wakeup publication.
R308 instantiates that extractor in the opt-in replay-LIQ top and feeds it from
the same selected row plus grant-gated base line data that would feed the
future relaunch E2 boundary. The extractor's request mask, complete/cross-line
checks, raw data, extended data, and blockers are diagnostic-only; the LRET
sink and wakeup sink remain tied low, so this does not make replay launch live.
R309 instantiates `LoadReplayReturnPublishReady` after the extractor and
`LoadReplayReturnConsumerReady`. It exposes whether the selected row has both
valid scalar return data and ready downstream consumers, but the resulting
publish-ready signal remains diagnostic-only and is not connected to
`launchEnable`, LRET enqueue, or mem-wakeup publication.
R310 instantiates `LoadReplayReturnLretPayload` beside the publish-ready join.
The payload owner forwards the selected row's BID/GID/RID, load LSID, PC,
address, size, extracted scalar data, return-pipe index, and wakeup-required
sideband once `LoadReplayReturnDataExtract.dataValid` is true. R311 adds the
compact replay destination sideband to that same payload surface by carrying
the renamed load destination from execute capture through the wait-slot,
relaunch queue, LIQ row, and selected launch diagnostics. The top still does
not enqueue LRET, wake dependents, or feed replay launch readiness.
R312 instantiates `LoadReplayReturnWritebackCandidate` after the LRET payload.
It surfaces a GPR-only writeback-shaped candidate (`writeValid`, physical tag,
and data) and explicit missing/non-GPR/disabled diagnostics. This is
diagnostic-only: `rf.io.writeValid` still comes only from
`ReducedScalarAluExecute.completeDstPhysValid`, and ready-table/issue wakeup
state remains untouched.
R313 adds the sibling `LoadReplayReturnWakeupCandidate` boundary. It consumes
the LRET payload's `wakeupRequired` predicate and destination sideband, then
surfaces raw wakeup kind/tag plus the current reduced-GPR subset. This is also
diagnostic-only: no ready table, issue queue, or LPV wakeup state is mutated.
R314 inserts `ReducedScalarWritebackArbiter` between execute/replay candidates
and the reduced RF write port. Execute retains priority and the replay side is
fed from `LoadReplayReturnWritebackCandidate` while `replayEnable` is tied low.
The arbiter's selected-source and replay-blocker diagnostics prove the
single-port owner without allowing replay returns to mutate RF state ahead of
live LRET/wakeup/ready-table sinks.
R362 retargets the replay side of that same arbiter to the R358
`LoadReplayReturnPipeW2WritebackArbiterInput`. The RF arbiter now sees the W2
writeback candidate and live-gated data fields, while `replayEnable` comes from
the disabled R357/R361 writeback live-control output.
R315 adds `LoadReplayReturnSideEffectReady` after the LRET payload,
writeback-candidate, and wakeup-candidate boundaries. It names the future
publish predicate: the LRET sink must be ready for every payload, the replay RF
writeback sink must be ready only when a reduced GPR writeback is present, and
the wakeup sink must be ready only when the payload requires regular memory
wakeup. R378 feeds the LRET sink predicate from the dormant FIFO's
`enqueueReady` capacity signal, while the current top still ties the wakeup
sink low and keeps the replay writeback arbiter disabled. The side-effect
predicate therefore reports real LRET capacity but remains unable to enable
live replay state mutation.
R316 adds `LoadReplayReturnPublishControl` as the final named publish fire
point. It requires a valid payload, the R309 publish-ready join, the R315
side-effect-ready join, and an explicit `liveEnable` bit before asserting
`publishFire`. The current top ties `liveEnable` low, so even a fully armed
diagnostic return cannot enqueue LRET, wake dependents, update ready state, or
write the replay side of the RF.
R317 adds `LoadReplayReturnPublishRequest` after the live-disabled publish
control. It shapes a future `publishFire` into LRET, reduced replay RF
writeback, and wakeup request diagnostics plus a compact mask. Because
`publishFire` remains false in the integrated top, these request bits remain
diagnostic-only and cannot drive side-effect sinks or replay-row lifecycle
state.
R318 adds `LoadReplayReturnLretSink` behind the R317 LRET request. It formats
the current `LoadReplayReturnLretPayload` outputs into a typed FIFO entry and
connects enqueue to the post-fire LRET request, while drain remains tied low
because the IEX return-pipe consumer is not yet owned. R378 feeds upstream
readiness from the FIFO `enqueueReady` capacity signal instead of the old
tied-low LRET sink flag. This is still capacity-only: `publishFire`,
`lretRequest`, and `drainReady` remain disabled, so the storage owner cannot
enqueue, drain, or enable replay launch before RF writeback, ready-table
update, issue wakeup, and replay-row lifecycle owners are live.
R319 adds `LoadReplayReturnIexDrainPermit` behind the R318 sink diagnostics.
It names the model `IEX::receiveFromLSU` drain predicate: a sink entry can move
only when replay-LIQ is enabled, flush is inactive, and at least one IEX return
pipe is free. The reduced top ties the current pipe-occupied mask full and
keeps `LoadReplayReturnLretSink.drainReady` low, so this packet exposes the
future pipe-full blocker without draining or discarding returned payloads.
R320 adds `LoadReplayReturnIexDataCandidate` behind the R319 permit. It names
the first `IEX::setMemData` admission checks after a future LRET drain:
payload validity, ROB row presence, and ROB row not-need-flush. R321 replaces
the former false ROB-row tieoff with the read-only `ROBRowStatusLookup` result
forwarded through `DecodeRenameROBPath`; the reduced top still leaves the FIFO
drain input low, so the outputs remain diagnostic-only and cannot update ROB
destination data, RF state, ready tables, issue wakeups, replay-row lifecycle,
or IEX E4 return-pipe residency.
R322 adds `LoadReplayReturnIexPipeInsertCandidate` behind the admitted
setMemData payload. It mirrors the model's later `inst->isLoadReturn` and
E4-insert payload shape, keeps the IEX insertion pipe selection separate from
the MemReqBus load-to-use `pipeID` sideband, and remains diagnostic-only
because LRET FIFO drain and real IEX pipe residency are still disabled.
R323 inserts `LoadReplayReturnRobResolveDataCandidate` between that
setMemData admission and the R322 pipe-insert diagnostic. This matches the
model ordering in `IEX::setMemData`: after ROB row checks, the model calls
`ROBState::resolveData` to mark instruction destinations data-valid and
increment `retLane`, then clones the instruction for E4 insertion. The reduced
top exposes the scalar resolve request, one-destination data-valid flag, and
ret-lane increment intent; the R322 insert candidate now consumes
`readyForPipeInsert` from this boundary instead of the raw setMemData valid.
No ROB row, RF, ready-table, issue queue, replay-row, FIFO, or E4 pipe state is
mutated by this diagnostic.
R324 inserts `LoadReplayReturnLaneCompletionCandidate` after that R323
resolve-data diagnostic. It names the model's `retLane < mem.realReqCnt`
early-return checks for scalar load-pair and vector/MEM multi-lane rows before
E4 insertion. The current reduced top ties `retLaneBefore=0`,
`returnedLaneCount=1`, `realReqCnt=1`, and both all-lane classifiers false, so
ordinary scalar replay continues to the R322 insert diagnostic only after the
post-resolve completion permit is valid.
R325 inserts `LoadReplayReturnTloadCompletionCandidate` after that lane
completion permit. It names the model's MEM-IEX `OP_TLD` block: decrement the
ROB row's `subInstCnt`, send a tile-SCB sequence with `islast`, and return
early until the final sub-instruction completes. The current reduced top ties
`isMemIex=false`, `isTload=false`, and `subInstCntBefore=0`, so ordinary scalar
replay passes through to the R322 insert diagnostic without mutating ROB
sub-instruction state or publishing a real tile-SCB request.
R326 inserts `LoadReplayReturnFinalMetadataCandidate` after TLOAD completion.
It names the model point where the cloned instruction is marked
`isLoadReturn`, `loadBranchResolve(inst)` is called, and memory-return timing
sidebands are copied before E4 insertion. Current LinxCoreModel
`loadBranchResolve` is a no-op, so the top exposes the call point while keeping
branch-recovery side effects false.
R327 inserts `LoadReplayReturnTimingStatsCandidate` after final metadata. It
names the model point where MemReqBus timing sidebands are copied into
`pipeCycle`, `iq_name` is copied, `ldRntCycle` is stamped, and load request
statistics are updated before E4 insertion. The current reduced top ties the
timing inputs to zero and exposes only diagnostic intent until a real cycle
source, timing sidecar storage, and stat counters exist.
R328 inserts `LoadReplayReturnPipeResidencyCandidate` after the R322
insert-shaped diagnostic. It names the model point where the returned-load
clone is assigned to the first free AGU E4 pipe for vector IEX machines or LDA
E4 pipe for scalar IEX machines. The current reduced top ties scalar mode and
`liveEnable=false`, so it reports LDA target intent plus occupied-pipe and
live-disabled blockers without mutating E4 pipe residency.
R329 inserts `LoadReplayReturnPipeResidencySlot` behind that diagnostic. The
slot captures the insert-shaped payload into a registered one-entry E4
residency state only when R328 emits a live unblocked write. The current top
still ties that write path off, so the slot reports dormant occupancy and
no-write diagnostics while preserving fixture behavior.
R330 inserts `LoadReplayReturnPipeResidencyAdvanceCandidate` behind the slot.
It names the LDA/AGU pipe `move()` point where E4 advances to W1 and E4 is
cleared, but the current top ties `advanceEnable=false` until a real W1/W2
stage owner exists.
R331 inserts `LoadReplayReturnPipeW1Slot` behind that advance point. It would
capture the R329 payload when R330 `advanceValid` fires, but the current top
keeps advance disabled while W1 clear comes from the R332 W1 advance
diagnostic.
R332 inserts `LoadReplayReturnPipeW1AdvanceCandidate` behind the W1 slot. It
names the model `w2_inst = w1_inst` transfer and W1 clear point, but the
current top only lets it advance when the R333 W2 slot is empty.
R333 inserts `LoadReplayReturnPipeW2Slot` behind that advance point. It would
capture the R331 W1 payload when R332 `advanceValid` fires, but the current top
still keeps upstream E4-to-W1 advance disabled.
R334 inserts `LoadReplayReturnPipeW2CompletionCandidate` behind the W2 slot.
It classifies the model `runW2` resolve, writeback, and wakeup requirements
and now owns the W2 clear pulse.
R335 inserts `LoadReplayReturnPipeW2SideEffectReady` behind the W2 completion
classifier. The current top ties its resolve, writeback, and wakeup sink
readiness inputs low, so completion and clear remain dormant until live sinks
are wired.
R336 inserts `LoadReplayReturnPipeW2ResolveSinkReady` behind the W2 completion
classifier and feeds its ready output into the R335 resolve input. The abstract
sink is ready, but the R357 live-control request is false, so W2 resolve still
blocks completion without mutating ROB state.
R337 inserts `LoadReplayReturnPipeW2WritebackSinkReady` behind the W2
completion classifier and feeds its ready output into the R335 writeback input.
The abstract scalar RF sink observes execute-port availability, but
the R357 live-control request is false, so W2 writeback still blocks
completion without mutating RF state.
R338 inserts `LoadReplayReturnPipeW2WakeupSinkReady` behind the W2 completion
classifier and feeds its ready output into the R335 wakeup input. The abstract
ready-table/issue-wakeup sink is ready, but the R357 live-control request is
false, so W2 wakeup still blocks completion without mutating ready-table or
issue-queue state.
R357 inserts `LoadReplayReturnPipeW2SideEffectLiveControl` behind the W2
completion classifier and feeds its live-enable outputs into the R336/R337/R338
sink owners and the R358/R359/R360 pre-arbiter input owners. The top keeps
R363's atomic live-request owner disabled, so this owner preserves the same
not-ready/dormant behavior while making the future live side-effect request
point explicit.
R339 inserts `LoadReplayReturnPipeW2SideEffectRequest` behind the W2
completion classifier and exposes the post-completion request vector. Because
R336-R338 still keep every live W2 sink disabled, the request outputs remain
dormant diagnostics and do not mutate ROB, RF, ready-table, or issue queues.
R340 inserts `LoadReplayReturnPipeW2ResolveRequest` behind the R339 resolve
request bit and feeds it from the resident W2 slot. Because R339 resolve
request remains low, the shaped resolve payload is diagnostic-only and does
not write the ROB or PE resolve array.
R341 inserts `LoadReplayReturnPipeW2WritebackRequest` behind the R339
writeback request bit and feeds it from the resident W2 slot. Because R339
writeback request remains low, the shaped reduced RF writeback payload is
diagnostic-only and does not write scalar RF state.
R348 inserts `LoadReplayReturnPipeW2WritebackFirePayload` behind the R346
writeback fire pulse and feeds it from the R341 writeback request payload.
Because the W2 sinks remain live-disabled, the fire-qualified writeback
payload stays diagnostic-only and does not feed scalar RF writeback
arbitration, RF state, or W2 clear.
R359 inserts `LoadReplayReturnPipeW2ResolveArbiterInput` behind the R347
resolve fire payload. It names the future replay side of the ROB/PE resolve
owner, reports disabled/flush/no-payload/live-disabled blockers, and keeps
`liveEnable` driven by the disabled R357 live-control request so no replay load
can mutate resolve state or publish branch/recovery side effects.
R364 inserts `LoadReplayReturnPipeW2RobCompleteSource` behind that resolve
arbiter input and inserts `ReducedRobCompletionArbiter` before
`DecodeRenameROBPath.complete*`. The replay source feeds the resolve sink's
structural `sinkReady` from execute completion-port availability and produces a
completion RID only from a live-gated resolve. `completeRowValid` is false, so
the ROB row image remains the allocation/rename-update row until a later replay
load commit-row fill owner is added. With R363 `requestEnable=false`, this path
is still dormant in generated RTL/QEMU gates.
R365 inserts `LoadReplayReturnPipeW2ClearCommitGuard` after R351/R364 evidence
is available. It checks that the resident W2 slot RID, the R347 resolve-fire
RID, and the R364 replay ROB-completion value agree before reporting
`commitClearReady` or `liveClearReady`. The guard is diagnostic-only: it does
not feed the current W2 slot `clear`, W2 advance/refill, replay RF writeback,
wakeup, resolve, ROB completion, or replay-row lifecycle.
R366 inserts `LoadReplayReturnPipeW2CommitRowCandidate` between the resident W2
slot and `LoadReplayReturnPipeW2RobCompleteSource`. It builds the future replay
load commit row shape from W2 identity, PC, address, size, destination, and
returned data. R372 inserts `LoadReplayReturnPipeW2CommitRowTraceSource` as
the candidate's instruction/source-trace owner. R373 feeds instruction
metadata from a read-only ROB row commit-trace lookup, while source traces
from the ROB lookup remain disabled and pre-completion ROB-row source traces
are explicitly blocked. R375 carries RF-derived source traces through
replay-LIQ launch
diagnostics, and R376 carries those traces through LRET, IEX insert, E4
residency, W1, and W2 slot state. R377 feeds that W2 slot source payload into
the trace-source provider while keeping `robCommitTraceLookupSourceTraceEnable`
false. The candidate can now observe complete source provenance from the W2
resident slot, but still cannot publish a replacement row until R367
inserts `LoadReplayReturnPipeW2RowFillEnableControl` as the candidate's
`rowFillEnable` owner. The control requires the R366/R372 row candidate, R350
side-effect fire completion, R365 clear/commit identity, live-clear readiness,
and replay-row lifecycle readiness. R368 inserts
`LoadReplayReturnPipeW2ReplayRowLifecycleReady` as that lifecycle owner. It
matches the resident W2 slot against resolved LIQ rows by
`(bid,gid,rid,loadLsId)`. R369 inserts
`LoadReplayReturnPipeW2ReplayRowClearRequest` between the existing ResolveQ
delayed clear and `ReducedLoadReplayLiqAllocPath.clearResolved*`; it gives the
existing clear priority, exports lifecycle clear readiness to R368, and gates
future lifecycle LIQ mutation with a commit permit. R370 inserts
`LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl` as the live lifecycle
request owner feeding that selector. It derives the request from R363
`requestActive` and the R366 row-fill candidate, so the integrated top remains
dormant because R363 still keeps the atomic live request disabled. R371 inserts
`LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit` between that selected
lifecycle arm and R367 row-fill enable before the R369 clear selector can
drive LIQ mutation. The
candidate output is still wired into the ROB completion source, proving the
ownership boundary while preserving `completeRowValid=false` in integrated
generated RTL.
R358 inserts `LoadReplayReturnPipeW2WritebackArbiterInput` behind the R348
writeback fire payload. It names the future replay side of the scalar RF
writeback arbiter, reports disabled/flush/no-payload/live-disabled blockers,
and keeps `liveEnable` driven by the disabled R357 live-control request so no
replay load can write RF state or contend with execute writeback.
R362 connects that R358 boundary into `ReducedScalarWritebackArbiter`, replacing
the older LRET-payload diagnostic writeback candidate as the arbiter's replay
source. Because the shared live-control request remains false, this source
switch is still observational.
R360 inserts `LoadReplayReturnPipeW2WakeupArbiterInput` behind the R349 wakeup
fire payload. It names the future replay side of the ready-table/issue-wakeup
owner, reports disabled/flush/no-payload/live-disabled blockers, and keeps
`liveEnable` driven by the disabled R357 live-control request so no replay load
can wake issue queues or mutate ready-table state.
R342 inserts `LoadReplayReturnPipeW2WakeupRequest` behind the R339 wakeup
request bit and feeds it from the resident W2 slot. Because R339 wakeup
request remains low, the shaped wakeup payload is diagnostic-only and does not
update ready-table state or wake issue queues.
R343 inserts `LoadReplayReturnPipeW2SideEffectPayloadPlan` behind the three
R340/R341/R342 payload modules. It compares the W2 required mask, R339 request
mask, and shaped payload-valid mask, but does not feed W2 completion, live sink
readiness, or W2 slot clear.
R344 inserts `LoadReplayReturnPipeW2SideEffectIssuePermit` behind R343 and the
R336/R337/R338 sink-ready owners. It reports whether a coherent side-effect
plan would be accepted by all required live-gated sinks, but does not feed W2
completion, live sink readiness, or W2 slot clear.
R345 inserts `LoadReplayReturnPipeW2SideEffectCompletionPermit` beside the
R335 readiness join and feeds it from the W2 completion classifier plus
R336/R337/R338 sink-ready owners. It names the pre-clear permit as masks and a
join-equivalence diagnostic, but leaves the existing R335-to-R334 readiness
path unchanged.
R346 inserts `LoadReplayReturnPipeW2SideEffectFireVector` behind the R344
issue permit and R343 payload plan. It exposes resolve/writeback/wakeup fire
pulses from the accepted issue mask only when request and payload masks match,
but does not feed W2 completion, live sink readiness, W2 slot clear, or any
live sink mutation.
R351 inserts `LoadReplayReturnPipeW2ClearIntent` after the R350 post-fire
checker. It observes the existing R334 W2 clear pulse, the R345 permit mirror,
and the R350 future-clear predicate, but does not feed the W2 slot clear input
or replay-row lifecycle.
R352 inserts `LoadReplayReturnPipeW2RefillReady` after R351. It observes the
current W1-to-W2 advance gate and the future live-clear predicate required by
the model `move()` order, but does not feed W1 advance or W2 storage.
R353 inserts `LoadReplayReturnPipeW2SlotReplacePlan` after R352. It observes
the W1 candidate before the current empty-only advance gate suppresses it,
compares current W2 slot acceptance with the future same-cycle replacement
predicate, and stays diagnostic until W2 storage semantics are updated.
R354 updates `LoadReplayReturnPipeW2Slot` with an explicit same-cycle
clear/refill storage mode and keeps `replaceOnClear=false` in the top, so the
new storage behavior is verified in module tests but cannot change live
top-level replay behavior yet.
R355 inserts `LoadReplayReturnPipeW2AdvanceControl` after R352/R353 and routes
both `LoadReplayReturnPipeW1AdvanceCandidate.advanceEnable` and
`LoadReplayReturnPipeW2Slot.replaceOnClear` through it. R356 inserts
`LoadReplayReturnPipeW2PromotionControl` before R351/R355 and routes live clear
plus live advance promotion through one request gate. R363 inserts
`LoadReplayReturnPipeW2AtomicLiveRequestControl` as the shared disabled request
owner for both R356 `promotionRequested` and R357 `liveRequested`. The top ties
R363 `requestEnable=false`, so the selected advance rule remains
`!W2Slot.occupied`, live clear remains disabled, and replacement remains
disabled until live W2 side effects, clear/refill, and replay-row lifecycle
mutation are promoted together. R357 replaces the direct false ties on the
R336/R337/R338 sink `liveEnable` inputs with
`LoadReplayReturnPipeW2SideEffectLiveControl`, and R361 routes the
R358/R359/R360 pre-arbiter `liveEnable` inputs through the same owner.
R362 uses the same disabled writeback live-control output as the RF arbiter
`replayEnable`, while the R358 candidate feeds `replayValid` and its live-gated
payload feeds `replayTag`/`replayData`.
R298 surfaces the replay-LIQ path's existing launch-drive, launch-ready,
launch-accepted, repick/miss/resolved masks, E4 update/miss/wakeup sidebands,
and `lhqRecordValid` at the top boundary. These are diagnostic-only in the
current fixture because return-pipe availability still keeps `launchEnable`
low.
R286 latches the accepted LHQ load slot and drives `clearResolvedValid` on the
following cycle, because `LoadInflightQueue` only accepts clear requests after
the E4 hit has become a resident `Resolved` row. R287 selects the youngest
valid `DecodeRenameROBPath.commitMemoryOrder` slot in the current commit window
as a reduced retire watermark and drives `LoadResolveQueue.retireValid`,
`retireBid`, and `retireLsId` from that source when replay-LIQ mode is enabled.
R289 builds a ResolveQ-specific copy of the scalar redirect cleanup flush,
overrides `req.lsId` with the redirecting row's reduced all-row LSID snapshot,
and drives `LoadResolveQueue.preciseFlush` only in replay-LIQ mode when that
LSID is valid. The generic path cleanup bus retains its ROB/rename RID
contract. Marker-only cleanup without an LSID still hard-clears the ResolveQ,
matching the pre-R289 conservative behavior. MDB conflict publication stays
deferred.
R290 instantiates `MDBConflictDetect` beside that ResolveQ path. The store side
comes from the accepted `StoreDispatchSTQPath` insert request exported by
`DecodeRenameROBPath`, so the probe preserves the store's row identity, LSID,
PC, address, size, PE/STID/TID, and scalar/tile shape. Active load rows are a
snapshot of replay-LIQ residency, using the top scalar `peId/threadId` sidecars
because the current reduced LIQ row does not yet carry independent PE/STID/TID
fields. Resolved rows come directly from `LoadResolveQueue.conflictRows`. The
result is surfaced only as `reducedMdbConflict*` diagnostics; no ready-store
wakeup, MDB queue fanout, or recovery flush consumes it yet.
R291 adds `MDBQueueFanout` behind that selected conflict record. The top maps
the load side of the conflict record to `ldInfo`, maps the store probe to
`stInfo`, sets `conf=1` to match `getMDBBus`, and enables `recordIn` only in
replay-LIQ mode when the detector selects a conflict. R292 maps the replay-LIQ
launch-accepted row into `lookupIn`: load PC/BID/LSID/address/size come from the
LIQ row, `stid` comes from the top thread sideband, and tile rows are suppressed.
Because the top still ties `launchEnable` low, this is a dormant producer
boundary in the current fixture. R293 keeps `deleteInValid` tied low but exposes
the fanout's delete-ready, accepted, processed, SSIT delete/decay result, and
lookup fanout phase-stall outputs. This matches the model `MDB::Work` phase
shape before a real failed-wait producer exists. `luDequeueReady` and
`suCheckReady` are tied ready so future lookup results drain without stalling
this diagnostic surface. The resident STQ row image feeds the fanout's SU wakeup
scan. The top exposes lookup acceptance/processing, delete boundary status,
LU/SU lookup result hit/store-BID identity, record acceptance, processed records,
BMDB report intent, SSIT valid mask, record error flags, and SU match/wakeup
diagnostics.

The overlay clears only on
run start/restart or when the optional reduced-store path is disabled;
ordinary backend redirects do not clear it because committed store bytes are
nonflushable state. The opt-in path is still for bounded integration debug: it
now owns STQ mark/drain/free lifecycle, committed store-byte visibility, ready
resident store-byte forwarding, and wait-hit execute hold for scalar load
lookup plus diagnostic replay ResolveQ storage/retire pruning, but not full
LIQ/LDQ replay row mutation, load relaunch, dependent consumer wakeup,
cross-line resident forwarding, cache state, TSO/fence completion, or MDB
conflict publication.
Rename acceptance remains queue-capacity driven. RF physical source readiness
is sampled by the issue queue and gates issue from resident rows, not frontend
packet acceptance. The P1/I1/I2 picker reads source data from
`ReducedScalarRegisterFile` for scalar P operands. For T/U local operands, the
issue queue uses `readOperandClass` plus `readRelTag` to suppress scalar RF
reads and select data from the local T/U overlay. Local readiness is sampled
through `localTReadyMask` and `localUReadyMask`, mirroring the same registered
issue-readiness timing used for scalar RF sources.
The live top only blocks a renamed row behind a pending local producer when the
queued row's operand classes actually use T/U local sources. This keeps scalar
macro/control rows such as `FENTRY` from waiting on unrelated local-overlay
state.

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
read as zero. R296 keeps execute as the priority lookup source and lets the
opt-in replay-LIQ base-data request use the same harness lookup only when
execute is idle; replay-LIQ base data is accepted only on the replay grant.
R124/R125 used explicit committed-store mutation in the harness:
after a store row matched QEMU, the harness wrote that little-endian
`mem_wdata` into the same sparse image so later reduced load lookups saw the
program-order store effect. R258 keeps that legacy mode available, but the
stricter `--disable-store-memory-mutation` gate leaves the sparse image
immutable and requires `ReducedStoreMemoryOverlay` to supply committed store
bytes from RTL state. R259 broadens that RTL state from SCB-accepted fragments
to include ROB-committed/storeCommitQ-equivalent fragments because the model
can forward committed store bytes before SCB acceptance. R265 adds ready
resident STQ forwarding after that committed overlay. R266 makes wait hits
hold execute instead of retiring pass-through data. R267 preserves store PC in
resident STQ rows and exports the selected wait-store identity. R268 adds the
producer-side store-unit replay wakeup request image for that identity. R269
registers one reduced wait-store row, feeds the remembered key back to the
wakeup producer, and clears the diagnostic row through `LoadReplayWakeup`.
R271 adds a relaunch-candidate diagnostic from that clear point. R272 consumes
that one-cycle candidate into a finite pending queue. R273 drains the queued
candidate on the current reduced in-place completion path only when the W2 load
completion matches the remembered identity. R274 expands that remembered
identity to the full model replay sidecar tuple: PC, address, size, BID, GID,
RID, and reduced LSID. R275 carries the forwarding snapshot
`(youngestStoreId, youngestStoreLsId)` explicitly in that candidate so a real
LIQ/LDQ row can be allocated later without deriving the snapshot from reduced
top aliases. Real relaunch, LIQ/LDQ row mutation, and dependent wakeup remain
deferred until a real LIQ/LDQ row can accept the candidate.
This remains a reduced memory visibility bridge, not a general LSU/STQ, cache,
replay, or MDB implementation.

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
R142 extends the QEMU-row source only: `--allow-block-loop-reentry` and
`FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1` allow dynamic FALL-header re-entry rows
to be emitted with `loop_reentry` metadata. The generated-RTL replay is still
expected to fail until the frontend/F4 path can cut at the model block body
boundary; see `CHISEL-ISSUE-007`.
R143 consumes that metadata through a temporary reduced body-cut interface in
the reduced top. R144 changes the interface to `reducedBfu*` geometry and lets
`ReducedBfuBodyCutPredictor` compute the cut/restart locally. The cut logic
only changes the source advance, F4 valid mask, and source-only restart for the
current loop-aware packet; it deliberately does not model the full BFU static
predictor or make `--allow-block-loop-reentry` a general architectural pass
criterion.
R149 adds `ReducedBfuResolvedBodyEndOwner` as the normalized resolved-event
handoff into the diagnostic static-geometry path. It does not change the body
cut control source.
R150 changes that control payload source to the static producer's latched
geometry, while preserving the external row as cut-arm, resolver surrogate, and
oracle.
R152 moves the latched-prediction/external-arm equality check into
`ReducedBfuBodyCutArm` and exposes accepted/mismatch diagnostics that the
generated-RTL harness treats as replay-proof invariants.

R153 prevents static-only body-cut arming. Explicit `BSTART` geometry may close
the previous body in the model, but branch direction is dynamic; a conditional
marker can reenter the loop body or fall through to the next block. The reduced
top therefore trains cut-eligible prediction only from accepted resolved
body-end geometry and uses that same resolved event as the cold same-cycle cut
fallback. Static geometry and `ReducedBfuBodyCutArm` stay diagnostic until a
real branch/BFU resolver replaces the replay sideband.
R154 adds `ReducedBfuResolvedBodyEndSource` before the resolved owner. The
source still converts replay `headerPc`/`hsize`/`bsize` into the cold fallback
body-end PC, but it can now prioritize a registered RTL local-cut feedback
event produced after `ReducedBfuLocalBodyWindow` fires. This starts moving
repeated body-end evidence into RTL-owned state without removing the replay
oracle needed for the first cold FALL re-entry.
R155 moves that feedback into `ReducedBfuResolvedBodyEndPending`. The runtime
event now stays pending until the next replay-qualified candidate has matching
`headerPc`, `hsize`, and body-end PC, then the source arbiter selects runtime
instead of replay for that candidate. Mismatched pending feedback is dropped
and replay remains the fallback. This fixes the R154 one-cycle feedback
lifetime without claiming full replay removal.
R156 adds `ReducedBfuPendingRuntimeBodyEndCandidate` beside the R155 owner.
It checks whether the pending payload would be source-eligible from active
header state alone and fails the generated-RTL harness on any comparable
candidate/replay mismatch. R157 uses that active-header candidate as the
runtime source and adds `ReducedBfuPromotedRuntimeBodyEndOracle` so replay can
still validate promoted events after the pending slot is consumed.
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
- accepts `FETCH_REDUCED_STORE_DISPATCH_STQ=1` to emit
  `LinxCoreFrontendFetchRfAluReducedStoreTraceTop` and route reduced store
  rows through the opt-in `StoreDispatchSTQPath`/`STQCommitDrain`/`SCBRowBank`
  lifecycle path;
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
  window, with a 64-cycle per-row budget so marker-only rows can wait for
  in-flight reduced RF/issue/execute work such as the `c.setc.eq` decision
  preceding a conditional `BSTART`;
- stops after the compared prefix, without requiring full top idle, when the
  final captured dense window deliberately contained post-prefix slots;
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
R151 extends the common manifest provenance for this harness path with
`git.linxcore_model` and `git.qemu` entries alongside `git.linxcore` and
`git.superproject`, so later BFU/ROB packets can cite the C++ model and
emulator SHAs from the same evidence bundle as the comparator result.

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
`mismatch_count: 0`; R151 and later manifests also record LinxCoreModel and
QEMU repository provenance.

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

The R192 marker-row manifest at
`generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 88`, and
`summary.mismatch_count: 0` after admitting and filtering 36 marker commits.
The paired default skip-mode manifest at
`generated/r192-default-skip-regression-qemu-elf-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`,
`summary.mismatch_count: 0`, and zero marker-row admissions.
The R279 replay-LIQ fixture manifest at
`generated/r279-replay-liq-fixture-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R285 replay-LIQ ResolveQ fixture manifest at
`generated/r285-replay-liq-resolveq-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R286 replay-LIQ delayed clear fixture manifest at
`generated/r286-replay-liq-resolve-clear-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R290 replay-LIQ MDB diagnostic fixture manifest at
`generated/r290-replay-liq-mdb-detect-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R291 replay-LIQ MDB fanout diagnostic fixture manifest at
`generated/r291-replay-liq-mdb-fanout-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R292 replay-LIQ MDB lookup diagnostic fixture manifest at
`generated/r292-replay-liq-mdb-lookup-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R293 replay-LIQ MDB delete-boundary diagnostic fixture manifest at
`generated/r293-replay-liq-mdb-delete-boundary-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R294 replay-LIQ selected-launch-row diagnostic fixture manifest at
`generated/r294-replay-liq-launch-row-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R295 replay-LIQ base-data align diagnostic fixture manifest at
`generated/r295-replay-liq-base-align-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R296 replay-LIQ load-lookup arbiter diagnostic fixture manifest at
`generated/r296-replay-liq-load-lookup-arb-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R297 replay-LIQ launch-readiness diagnostic fixture manifest at
`generated/r297-replay-liq-launch-readiness-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R298 replay-LIQ launch/E4 observability fixture manifest at
`generated/r298-replay-liq-launch-e4-observe-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R299 replay-LIQ source-return readiness fixture manifest at
`generated/r299-replay-liq-source-return-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R300 replay-LIQ return-readiness fixture manifest at
`generated/r300-replay-liq-return-readiness-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R301 replay-LIQ return-pipe selection fixture manifest at
`generated/r301-replay-liq-return-pipe-select-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R302 replay-LIQ return-pipe permit fixture manifest at
`generated/r302-replay-liq-return-pipe-permit-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R303 replay-LIQ return-pipe budget fixture manifest at
`generated/r303-replay-liq-return-pipe-budget-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R304 replay-LIQ return consumer-budget split fixture manifest at
`generated/r304-replay-liq-return-consumer-budget-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R305 replay-LIQ return consumer sink split fixture manifest at
`generated/r305-replay-liq-return-consumer-ready-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R311 replay-LIQ destination-sideband fixture manifest at
`generated/r311-replay-destination-sideband-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R312 replay-LIQ writeback-candidate fixture manifest at
`generated/r312-replay-return-writeback-candidate-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R313 replay-LIQ wakeup-candidate fixture manifest at
`generated/r313-replay-return-wakeup-candidate-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R314 replay-LIQ writeback-arbiter fixture manifest at
`generated/r314-replay-writeback-arbiter-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R362 replay-LIQ W2 writeback-to-RF-arbiter fixture manifest at
`generated/r362-replay-pipe-w2-writeback-rf-arbiter-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R315 replay-LIQ side-effect readiness fixture manifest at
`generated/r315-replay-side-effect-ready-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R316 replay-LIQ publish-control fixture manifest at
`generated/r316-replay-publish-control-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R317 replay-LIQ publish-request fixture manifest at
`generated/r317-replay-publish-request-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R318 replay-LIQ LRET sink fixture manifest at
`generated/r318-replay-lret-sink-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R319 replay-LIQ LRET drain-permit fixture manifest at
`generated/r319-replay-lret-drain-permit-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R320 replay-LIQ IEX data-candidate fixture manifest at
`generated/r320-replay-iex-data-candidate-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R321 replay-LIQ ROB row-status fixture manifest at
`generated/r321-replay-rob-row-status-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R322 replay-LIQ IEX pipe-insert fixture manifest at
`generated/r322-replay-iex-pipe-insert-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R323 replay-LIQ ROB resolve-data fixture manifest at
`generated/r323-replay-rob-resolve-data-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R324 replay-LIQ lane-completion fixture manifest at
`generated/r324-replay-lane-completion-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R325 replay-LIQ TLOAD completion fixture manifest at
`generated/r325-replay-tload-completion-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R326 replay-LIQ final metadata fixture manifest at
`generated/r326-replay-final-metadata-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
The R327 replay-LIQ timing/stat sideband fixture manifest at
`generated/r327-replay-timing-stats-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource`
- `bash tools/chisel/run_chisel_tests.sh --only F4DenseSlotQueue`
- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r279-replay-liq-fixture-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r285-replay-liq-resolveq-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r286-replay-liq-resolve-clear-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r305-replay-liq-return-consumer-ready-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r311-replay-destination-sideband-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r312-replay-return-writeback-candidate-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r313-replay-return-wakeup-candidate-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r316-replay-publish-control-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r323-replay-rob-resolve-data-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r324-replay-lane-completion-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r325-replay-tload-completion-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r326-replay-final-metadata-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r327-replay-timing-stats-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r317-replay-publish-request-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r290-replay-liq-mdb-detect-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r293-replay-liq-mdb-delete-boundary-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r291-replay-liq-mdb-fanout-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r292-replay-liq-mdb-lookup-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r294-replay-liq-launch-row-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r295-replay-liq-base-align-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r296-replay-liq-load-lookup-arb-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r300-replay-liq-return-readiness-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r301-replay-liq-return-pipe-select-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r302-replay-liq-return-pipe-permit-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r303-replay-liq-return-pipe-budget-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `BUILD_DIR=generated/r141-diagnostic-replay-1747-fret-target-priority FETCH_EXPECTED_ROWS=generated/r141-logical-local-1747-qemu-elf-xcheck/qemu.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
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
- `bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r196-gpr-mapq-256-marker-row-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r197-gpr-mapq-mask-optimized-marker-row-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `BUILD_DIR=generated/r198-helper-split-marker-row-smoke bash tools/chisel/run_chisel_frontend_fetch_rf_alu_marker_rows_smoke.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r198-helper-split-marker-row-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r202-marker-stop-restore-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r227-row-order-6000-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 60 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r228-row-order-8192-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 8192 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 75 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r229-row-order-12288-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12288 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 120 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r230-row-order-16384-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 16384 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 150 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r231-row-order-24576-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 24576 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 240 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r232-row-order-32768-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 32768 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 420 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r233-row-order-49152-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 49152 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 720 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r234-row-order-65536-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 65536 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 1200 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r235-row-order-98304-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 98304 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 1800 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r236-row-order-131072-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 131072 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 2400 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r237-row-order-196608-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 196608 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 3600 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r238-row-order-262144-marker-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 262144 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 4800 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 42 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `FETCH_REDUCED_STORE_DISPATCH_STQ=1 BUILD_DIR=generated/r242-reduced-store-fixture-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-default-skip-regression-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 3 --capture-rows 16 --allow-block-markers --max-seconds 10 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-1024-frontier-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedStoreMemoryOverlay`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward`
- `bash tools/chisel/run_chisel_tests.sh --only ResidentStoreForwardStoreSnapshot`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r258-store-memory-overlay-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --reduced-store-dispatch-stq --disable-store-memory-mutation --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --qemu-trace generated/r258-store-memory-overlay-1024-qemu-elf-xcheck/traces/qemu.no-harness-store.jsonl --dut-trace generated/r258-store-memory-overlay-1024-qemu-elf-xcheck/traces/dut.no-harness-store.jsonl --report-dir generated/r258-store-memory-overlay-1024-qemu-elf-xcheck/report-no-harness-store --max-commits 665 --mode failfast`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r126-coremark-fret-scalar-redirect-1415-qemu-elf-xcheck-pass --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1415 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r127-brob-flushed-reuse-1461-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1461 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
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
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r131-andi-swi-ori-sub-mul-1595-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1595 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r158-coremark-next-frontier-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r158-coremark-next-frontier-10000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 10000 --allow-block-markers --allow-block-loop-reentry --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r159-coremark-next-frontier-20000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 20000 --allow-block-markers --allow-block-loop-reentry --max-seconds 45 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r160-coremark-next-frontier-50000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50000 --allow-block-markers --allow-block-loop-reentry --max-seconds 90 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r161-coremark-next-frontier-100000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 100000 --allow-block-markers --allow-block-loop-reentry --max-seconds 180 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r162-coremark-next-frontier-200000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 200000 --allow-block-markers --allow-block-loop-reentry --max-seconds 360 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 400000 --allow-block-markers --allow-block-loop-reentry --max-seconds 720 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `BUILD_DIR=/Users/zhoubot/linx-isa/rtl/LinxCore/generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck FETCH_ELF=/Users/zhoubot/linx-isa/rtl/LinxCore/tests/benchmarks/build/coremark_real.elf FETCH_QEMU_TRACE=/Users/zhoubot/linx-isa/rtl/LinxCore/generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r164-coremark-next-frontier-800000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 800000 --allow-block-markers --allow-block-loop-reentry --max-seconds 1440 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r165-coremark-next-frontier-1600000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1600000 --allow-block-markers --allow-block-loop-reentry --max-seconds 2880 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r166-coremark-next-frontier-3200000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 3200000 --allow-block-markers --allow-block-loop-reentry --max-seconds 5760 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedBfuLocalBodyWindow`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --self-test`
- `bash -n tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `bash -n tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`
- `BUILD_DIR=generated/r153-local-body-window-4000-rtl-replay-v6 FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
