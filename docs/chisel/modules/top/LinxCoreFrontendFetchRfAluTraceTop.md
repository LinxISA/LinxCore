# LinxCoreFrontendFetchRfAluTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
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
| input | `loadLookupData` | `UInt(64.W)` by default | combinational with `loadLookupValid` | Reduced read-only load data supplied by the harness from the sparse ELF memory image. |
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
  SDI/SWI/SBI/C.SDI store sidebands, and writeback-shaped completion.

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
and block-flush the redirected full BID. Marker redirects stay frontend-only:
they restart fetch/F4/dense decode state but do not discard older backend
scalar work.
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

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only FrontendFetchPacketSource`
- `bash tools/chisel/run_chisel_tests.sh --only F4DenseSlotQueue`
- `bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
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
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 42 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-default-skip-regression-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 3 --capture-rows 16 --allow-block-markers --max-seconds 10 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-1024-frontier-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
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
