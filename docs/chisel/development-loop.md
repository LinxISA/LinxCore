# LinxCore Chisel Development Loop

Date: 2026-06-30

## Purpose

This document is the concise execution plan for future LinxCore Chisel agents.
Use it with `docs/chisel/agent-loop.md`, which remains the detailed packet
ledger and gate history.

The current priority is ROB and cross-check infrastructure. Wider frontend,
issue, LSU, and engine work should build on a real retirement, recovery, and
trace oracle instead of creating isolated module demos.

## Packet Start Baseline

Record these at the start of each packet and refresh only by explicit fetch, not
by merging over dirty worktrees:

| Repository | SHA observed at the start of this plan packet |
|---|---|
| `linx-isa` | `6514dc90b448eacef3c5e8b907764b36fbd063cc` |
| `rtl/LinxCore` | `4f6489bf61b477adc4a685f8dc3d2b6773981f30` |
| `model/LinxCoreModel` | `68b06b2a8dd07db98bd562aeae7e5a8867c6d450` |
| `emulator/qemu` | `9f96be0c952fb9a047b324b06a480b1c689ba51d` |
| `skills/linx-skills` | `13e314c69fdd6841f94a814df3961e566df98dc8` |

`model/LinxCoreModel` was fetched on 2026-06-29 and still matched
`origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
The superproject, LinxCore, LinxCoreModel, and skills remotes were fetched
again on 2026-06-29. Do not merge over the current dirty worktrees; record
remote deltas at packet start and keep unrelated dirt out of module commits.
R82 started from `linx-isa` commit
`68e669d9d016286aa40e6685ea0f1c06ce1d49f1`, `rtl/LinxCore` commit
`fc03e13a7260228c34a49817f66786fea9839ac5`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`9f96be0c952fb9a047b324b06a480b1c689ba51d`, and `skills/linx-skills` commit
`fc6559011846725755d56d089908489553ab1518`. The LinxCore worktree also had
unrelated architecture Markdown edits; keep them unstaged for the R82 packet.
R83 started from `linx-isa` commit
`834952d639179b2a5430bbb55cc8a8855c58b302`, `rtl/LinxCore` commit
`8747920e9d4b59c07ded648c77f61e73ad2e9fcb`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`9f96be0c952fb9a047b324b06a480b1c689ba51d`, and `skills/linx-skills` commit
`3b1cc70b9ee90c7f23cb978b9cb740db4e9ca377`. The R83 model evidence is
`IssueState::insert`, `IssueState::Select`, `IssueState::Wakeup`,
`IssueState::ReleaseEntry`, `IDispatch::Work`, and `ALUPipe` W1/W2
writeback. R83 inserts a reduced FIFO issue queue between rename and execute,
so source readiness gates queue-head issue rather than rename acceptance.
R84 started from `linx-isa` commit
`a7ffc894868cdb39d8bd6a5a80df32665c203952`, `rtl/LinxCore` commit
`e97b92dc6e3bb94949c4b7803ca13e5f52532692`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`9f96be0c952fb9a047b324b06a480b1c689ba51d`, and `skills/linx-skills` commit
`82e1a5fe42b20e56a22c9d203c623c37752ea00f`. The R84 model evidence is
`IssueState::Select` marking an entry `issued`, `IssueState::ReleaseEntry`
removing only a later issued match, `IssueState::flush` preserving
issued/not-issued accounting, and `ALUPipe`/`IEX::releaseIQEntry` returning a
release event after issue.
R84 closeout: `skill-evolve: update linx-core (issue acceptance marks issued;
issue-queue removal waits for later model-derived release identity)`.
R85 started from `linx-isa` commit
`ecebd8a4fe26761eb6d6cf79ae43cf48cf52be1d`, `rtl/LinxCore` commit
`b99c0f6a5949fb605d511cc2dc768fd59e2b5903`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`9f96be0c952fb9a047b324b06a480b1c689ba51d`, and `skills/linx-skills` commit
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. The R85 model evidence is
`IssueState::Wakeup` mutating resident operand-ready bits,
`IssueState::Select` selecting only already-ready non-issued entries,
`ReadyState::checkReady` initializing sources from the ready table, and
`ALUPipe::WakeupDstTags` publishing physical destination wakeups. R85 keeps
the reduced FIFO policy but stores per-entry source readiness in the issue
queue, initialized and updated from the RF ready mask instead of feeding
same-cycle RF `readReady` directly into issue selection.
R85 closeout: `skill-evolve: no-update (installed linx-core skill already
documents wakeup at cycle N must not affect pick until N+1)`.
R86 started from `linx-isa` commit
`2df227e808b30c039b03be3eef9db077630243c6`, `rtl/LinxCore` commit
`f31fd42484c55ac9a1ed957e4c1d64ae1a456bc9`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and `skills/linx-skills` commit
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. The R86 model evidence is
`IssueState::Select` scanning resident entries, ignoring issued entries,
remembering the oldest ready candidate, marking that selected entry issued,
and preserving `sizeNotIssued` until a later release. R86 changes the reduced
Chisel queue from head-only pick to oldest-ready resident selection while
preserving registered source readiness and ALU W2 release.
R86 closeout: `skill-evolve: no-update (installed linx-core skill already
documents oldest-ready ordering across in-flight issued entries)`.
R87 started from `linx-isa` commit
`fe6d42a5612c0fd42935c6b995513275f10542f2`, `rtl/LinxCore` commit
`9f6379a2a3baa5ed654142b45e8fbda23334b752`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and `skills/linx-skills` commit
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. Remote metadata was fetched on
2026-06-29 without merging: root `origin/main` was
`e6708166cd6bde8d1d1cbb8b13a64814859ac41b`, LinxCore `origin/main` was
`d9157fd1e79db2a9eb9294cdce6bb361d52a77d2`, LinxCoreModel `origin/main`
still matched `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, and
`skills/linx-skills origin/main` was
`c42152d9883c3743ecc28744fe9a616c16dda47b`. The R87 model evidence is
`IssueState::Select` owning pick/issued marking, `iex_pipe.h` defining the P1
issue, I1 RF-read, and I2 RF-return split, `ALUPipe::runI1/runI2` generating RF
requests and consuming returns, and `IEX::releaseIQEntry` releasing queue rows
after issue. R87 introduces `ReducedScalarIssuePick` as the reduced
selected-row pick/read/confirm owner inside `ReducedScalarIssueQueue`; the
queue remains the residency, source-ready, release, and compaction owner.
R87 closeout: `skill-evolve: no-update (installed linx-core skill already
documents the issue-owner P1/I1/I2 pick/read/confirm boundary)`.
R88 started from `linx-isa` commit
`876701b68eeb9ca16ebf45bba4fe28023c024df7`, `rtl/LinxCore` commit
`37b039f2e96bfdea34203f419fdfa0755fc9f1b9`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and `skills/linx-skills` commit
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. Remote metadata was fetched on
2026-06-29 without merging: root `origin/main` was
`e6708166cd6bde8d1d1cbb8b13a64814859ac41b`, LinxCore `origin/main` was
`d9157fd1e79db2a9eb9294cdce6bb361d52a77d2`, and LinxCoreModel `origin/main`
still matched `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. QEMU did not expose
an `origin/main` ref in this checkout, so R88 records only its checked-out SHA.
The XiangShan flow references were probed directly from GitHub:
OpenXiangShan/XiangShan `HEAD` was
`0aff3b4899fa89fb3e7d48ee5ff336047d47b1ff`, and OpenXiangShan/difftest
`HEAD` was `36062fbd54579220e8aff92bc820e2fd3e749539`. The R88 model evidence
is `IssueState::Select` marking selected rows issued, `ALUPipe::move` moving
`p1_inst` to `i1_inst` to `i2_inst`, `ALUPipe::runI1/runI2` separating RF read
request and RF return consumption, and scalar ALU IQ release remaining later
through the existing release identity path. R88 makes `ReducedScalarIssuePick`
stateful: P1 locks a queue row, I1 drives RF read and cancels the lock on read
readiness failure, and I2 presents the captured row/data to execute while
queue deallocation remains ALU-release driven.
R89 started from `linx-isa` commit
`3bf69fae1a0cde8902bd06bef8dc693f56155dd2`, `rtl/LinxCore` commit
`03a6944dd8bc3a7f43dda37e69a8c3dfbfb9b374`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and `skills/linx-skills` commit
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. The R89 packet is an
infrastructure packet: `run_chisel_qemu_crosscheck.sh` now emits
`crosscheck_manifest.json` after every non-dry-run comparison, preserving the
comparator exit status while tying raw traces, normalized traces, reports,
QEMU binary selection, row counts, first mismatch, CBSTOP summary, and
LinxCore/superproject git context into one evidence bundle.
R90 started from `linx-isa` commit
`774c4fbd6935d098e778e45bd717b57af72d7db8`, `rtl/LinxCore` commit
`3d23b7c561aca535d500c1c2ce00d7e86013cc59`,
`model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and
`skills/linx-skills` commit `848bd17bb9ced589eaadf648dc0f5293ab0a3dc4`.
The R90 packet is a bounded bridge: it feeds archived or freshly collected QEMU
commit rows through the current Chisel trace replay surface in an isolated
build/report directory. It does not claim live full-DUT QEMU equivalence; that
remains blocked until the Chisel top emits architectural commit rows from real
fetch, issue, execute, LSU, trap, and recovery paths.
R90 also separates raw replay/normalization depth from architectural compare
depth: QEMU metadata rows may be filtered by the comparator, so the bridge
normalizes a wider raw window and slices the replay input to the smallest
prefix containing the requested non-metadata commit rows.
R91 started from `linx-isa` commit
`f8a6c3e7c703c770b426ab9fbc702469e4ec5993`, `rtl/LinxCore` commit
`1c40d4eeae136370003d1e257669972e2f90e433`,
`model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`, and
`skills/linx-skills` commit `0580ef69ed3e77f179f91d139062f3223cd01120`.
R91 hardens the live `--elf` QEMU trace replay path: the FIFO prefix reader is
bounded by `--replay-rows`, gets killed if QEMU exits before producing rows,
and the wrapper fails fast with an empty-trace error instead of hanging. The
CoreMark direct-boot ELF maps load segments at `0x40000000`; use explicit QEMU
memory such as `-m 1280M` when replaying
`tests/benchmarks/build/coremark_real.elf`. R91 evidence captured 128 raw QEMU
rows from that ELF, sliced 5 replay rows containing 4 architectural commits,
and passed the Chisel replay cross-check with zero mismatches.
R92 started from `linx-isa` commit
`2d51e6a1625e213908131096a8c0360ba102f748`, `rtl/LinxCore` commit
`9cd2e09af329ff08d0370c9bb334ae72298b1281`,
`model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and
`skills/linx-skills` commit `67bcb752359e67b1de87920349f44f9d21eb65d7`.
R92 is a shared trace-writer packet: generated-RTL Verilator harnesses should
use `tools/chisel/commit_trace_jsonl.h` for QEMU-shaped reference rows and
DUT sideband rows instead of open-coded JSON strings. The helper preserves the
QEMU commit field set, plus optional DUT sidebands `seq/cycle/slot`,
`bid/gid/rid`, ROB id, and `block_bid`; `trace_schema_adapter.py` remains the
normalization boundary. This keeps model `CommitInfo` identity separate from
hardware block identity while future live trace writers are added.
R92 closeout: `skill-evolve: update linx-core (generated-RTL harnesses must
use the shared commit JSONL writer before live Chisel trace writers are added)`.
R93 started from `linx-isa` commit
`1d15a2787fd39cdcfc61249faf261ef1a80049f5`, `rtl/LinxCore` commit
`4ffa096d7180c682a625910ddbf8bd973c75f7b6`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`d783c308c8ed3fda088901f011c7c6457c30105f`. R93 adds
`FrontendFetchPacketSource`, the first reduced live frontend packet producer:
it owns one-outstanding PC request, response-window capture, packet residency,
packet UID/checkpoint assignment, restart/flush clearing, and decoded-byte PC
advance before `F4DecodeWindow`. The model evidence is
`PEIFU::GenFetchReq`, `PEIFU::RunF0`, `IFUICache::getCacheData`,
`PEIFU::InsertToF4`, `PEIFU::InsertToF5`, `PEIFU::InsertToIB`,
`FetchReqBus`, `DecodeBundle`, and `CheckMInstSize`. This is a live-source
substrate only; full QEMU-vs-DUT compare still waits for a top that fetches
from ELF memory and retires real commits.
R93 closeout: skill-evolve: no-update (module-local IFU source contract is
documented in FrontendFetchPacketSource.md; no new reusable skill policy).
R94 started from `linx-isa` commit
`a47cc4dc339b079abd0e86fdfa1777896aa7ba88`, `rtl/LinxCore` commit
`b9366af9986e85b6f4a1c28149aebd1b9285c3cc`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` at
`d783c308c8ed3fda088901f011c7c6457c30105f` before local skill edits. R94 adds
`LinxCoreFrontendFetchTraceTop`, the first generated-RTL cross-check top that
uses `FrontendFetchPacketSource` rather than a testbench-owned
`FrontendDecodePacket`. The bounded Verilator memory-window fixture drives PC
request/response handshakes, returns one decoded instruction per 64-bit
window, lets F4's decoded byte count advance the source PC, then retires the
allocated ROB row through the existing explicit completion surrogate. The
manifest at
`generated/chisel-frontend-fetch-trace-top-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`. This is
live fetch-source infrastructure, not full QEMU equivalence; ELF memory,
dense multi-slot packets, real execute/LSU completion, and recovery remain
later packets.
R94 closeout: skill-evolve: update linx-core (new generated-RTL live fetch
trace-top xcheck is a reusable promotion gate).
R95 evidence was collected with `linx-isa` commit
`b9b7066823c7c532954923cea395d11002b978c5`, `rtl/LinxCore` commit
`f3fe4c325c0aad3c1936d9b565f84079f48d076d`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`653282e779886f057821b49f43a29d23b7a88937`. R95 adds
`LinxCoreFrontendFetchRfAluTraceTop`, the first generated-RTL cross-check top
that combines a live `FrontendFetchPacketSource` with the RF-backed reduced
issue and ALU completion path. The bounded Verilator fixture now serves PC
request/response windows instead of creating `FrontendDecodePacket` rows, and
the Chisel source advances its PC from F4's decoded byte count before the
decoded row flows through rename/ROB, RF source reads, issue residency, ALU W2
completion, RF writeback, and monitored commit export. The manifest at
`generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/report/crosscheck_manifest.json`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
This remains reduced live-source evidence, not full QEMU equivalence; ELF
memory loading, dense multi-slot packets, full issue arbitration, LSU,
trap/recovery, and redirect restart remain later packets.
R95 closeout: skill-evolve: update linx-core (new generated-RTL live fetch to
RF/issue/ALU xcheck is a reusable promotion gate).
R96 started from `linx-isa` commit
`7de7d31252e6bba4c124764007730f8e3ee4b023`, `rtl/LinxCore` commit
`1775df73eefaa1eadac5a0ff0499ad133466113b`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`a4947ee70ddc93b1019c2a43f899d3a9bb6cb4dd`. R96 is a harness-infrastructure
packet for `LinxCoreFrontendFetchRfAluTraceTop`: the Verilator driver now
loads instruction bytes through a reusable `FetchMemoryImage` and accepts
`--memory-bin`/`--memory-base`, while the wrapper emits and passes
`fixture.fetch.bin` for the current dependent scalar smoke. This removes
direct response-time instruction-word injection from the live fetch RF/ALU
gate. It still appends a single-instruction terminator using the expected
instruction length, so dense multi-slot packet decode, ELF program segments,
and full QEMU-vs-DUT architectural comparison remain later packets.
R96 closeout: skill-evolve: no-update (file-backed fetch bytes are documented
in the module gate; installed linx-core already requires the live fetch RF/ALU
xcheck and manifest inspection).
R97 started from `linx-isa` commit
`5120205ede665a1f82e90d73e04afaf534d204d6`, `rtl/LinxCore` commit
`4871cb91952450cfbae92027c739f410eb7f1901`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`a4947ee70ddc93b1019c2a43f899d3a9bb6cb4dd`. R97 extends the same harness
lane with sparse ELF-backed fetch memory: `frontend_fetch_elf_memory.py`
parses ELF64 little-endian PT_LOAD segments into an address-to-byte memory map,
the Verilator driver accepts `--memory-hex`, and the wrapper supports
`FETCH_ELF` / `FETCH_MEMORY_HEX` in addition to the R96 base-addressed binary
path. This is still memory-input infrastructure only. The reduced top keeps
the fixed three expected rows and appends one single-instruction terminator per
expected length, so QEMU-derived row binding, dense multi-slot packets, and
full QEMU-vs-DUT architectural comparison remain later packets.
R97 closeout: skill-evolve: update linx-core (new reusable `FETCH_ELF` sparse
fetch-memory extraction mode and self-test gate for future frontend agents).

## Reference Evidence

The active ROB, issue, and frontend packets are anchored to these C++ model
facts:

| Source | Evidence |
|---|---|
| `model/bctrl/BCtrl.cpp` | `BCtrlUnit::Work` allocates BROB metadata for block-split instructions, assigns `inst->bid`, `inst->peID`, and `inst->stid`, then sends the instruction toward the scalar PE decode path. |
| `model/bctrl/BROB.cpp` | `BlockROB::allocBlock` advances the BROB allocation pointer, writes header and command metadata, and returns the full block identity used by the scalar PE path. |
| `model/bctrl/spe/DCTop.cpp` | `DCTop::Work` sets `inst->rid = prob[stid]->getAllocPtr()`, calls `prob[stid]->allocROB(inst)`, assigns load/store IDs, and only then writes `dec_ren_q`. |
| `model/bctrl/spe/SPEROB.cpp` | `SPEROB::allocROB` writes a valid allocated ROB row with `tpc`, `bid`, `gid`, `last`, `rid`, and the `SimInst` pointer, then increments `allocPtr`, `size`, and `osdSize`. |
| `model/bctrl/spe/SPERename.cpp` | Rename later consumes `dec_ren_q` and mutates the same instruction object with renamed sidecars such as local T/U sequences. |
| `model/iex/pipe/alu_pipe.cpp` | `ALUPipe::Work` executes in the ALU pipe and publishes resolve/writeback at W2. |
| `isa/calculate/arithmetic/Arithmetic.cpp` / `isa/calculate/others/Others.cpp` | Reduced scalar ALU semantics for R81 are `ADD = src0 + src1`, `ADDI = src0 + imm`, and `MOVR/MOVI` move the selected source/immediate into the destination. |
| `model/bctrl/spe/GPRRename.cpp` / `model/iex/rtable.cpp` / `model/iex/iex_rf.cpp` | R82 scalar RF operand sourcing uses renamed physical tags: architectural GPRs start as identity physical tags, scalar destinations allocate new physical tags, ready/data state is tracked per physical tag, and RF reads return OPD_GREG data by physical tag. |
| `model/iex/iex_iq.cpp` / `model/iex/iex_dispatch.cpp` / `model/iex/pipe/iex_pipe.h` / `model/iex/pipe/alu_pipe.cpp` / `model/iex/iex.cpp` | R88 scalar issue handoff stores renamed rows before execute, initializes and wakes sources by physical tag, selects the oldest resident ready non-issued entry, marks selected entries issued without removing them, separates P1 pick from I1 RF-read request and I2 RF-return consumption in the pipe model, and releases issued rows later by `(bid, rid, stid)`. The reduced Chisel queue preserves enqueue, registered RF-readiness gating, ROB identity, oldest-ready resident selection, P1 in-flight lock, I1 read-cancel unlock, I2 execute handoff, issued-entry residency, and ALU W2 release while still deferring full read-port arbitration, alternate select preferences, load miss suppression, replay, and bypass. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R93 frontend fetch source evidence: F0 captures `fetchTPC`, block/thread identity, `fid`, and sidebands in `FetchReqBus`; F2 fills instruction window data and sizes with `CheckMInstSize`; F3/F4/F5 move `DecodeBundle` records under backpressure. The reduced Chisel source preserves one-outstanding PC request, response-window packetization, packet-owned UID/checkpoint, restart/flush clearing, and downstream decoded-byte PC advance while deferring cacheline merge, branch prediction, RAHQ, ELF memory loading, and multiple outstanding requests. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/bctrl/spe/DCTop.cpp` / `model/bctrl/spe/SPEROB.cpp` / `model/interface/CommitInfo.h` | R94 live frontend fetch trace-top evidence: model fetch requests become decode bundles before scalar decode/ROB allocation, and commit rows preserve model `(bid,gid,rid)` identity separately from hardware block identity. The Chisel top preserves the reduced form by feeding source-produced packets into F4 and `DecodeRenameROBPath`, advancing source PC from F4 decoded byte count, and dumping generated-RTL commit rows through the shared JSONL writer and neutral comparator. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/bctrl/spe/DCTop.cpp` / `model/bctrl/spe/GPRRename.cpp` / `model/iex/iex_iq.cpp` / `model/iex/iex_rf.cpp` / `model/iex/pipe/alu_pipe.cpp` / `model/interface/CommitInfo.h` | R95 live frontend fetch RF/ALU evidence: source-produced packets flow through F4 into scalar decode/rename/ROB allocation, RF-backed issue residency, RF source reads, ALU W2 completion, destination RF writeback, and commit-row export. The reduced Chisel top preserves source PC request/response ownership, F4 byte-count PC advance, queue-capacity rename acceptance, RF-readiness-gated issue, model-derived issue release identity, and separate model commit identity versus hardware block identity while still using a bounded instruction-window fixture. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R96 fetch-memory harness evidence: the model IFU boundary requests a PC and returns instruction bytes plus size-derived decode metadata before downstream scalar ownership. The reduced Chisel gate now reads response instruction bytes from a reusable memory image at the requested PC before packaging the bounded response window. It intentionally keeps one expected instruction length per response until the dense multi-slot packet path is implemented. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R97 sparse ELF fetch-memory evidence: IFU request/response ownership is address based, so harness memory must support non-contiguous and high-address program bytes before CoreMark-style direct-boot images can become live fetch inputs. The Chisel harness now accepts sparse address-to-byte memory extracted from ELF PT_LOAD segments while keeping expected row selection as a separate future owner. |

The key hardware implication is that the C++ model gets post-allocation rename
visibility through a shared `SimInst` pointer. Chisel `ROBEntryBank` stores
value fields. Therefore enqueue-time ROB reservation in Chisel must either:

- reserve a row at decode enqueue and later update rename-produced sidecars, or
- split the ROB row into reservation metadata and post-rename sidecar metadata.

Do not move allocation before `DecodeRenameQueue` by simply feeding zero
`tSeq/uSeq` or destination sidecars into `ROBEntryBank` and treating the result
as model-equivalent.

## XiangShan Flow Reference

Assumption: the earlier request's "Xianghan" means OpenXiangShan.

Use OpenXiangShan as a flow reference only:

- [OpenXiangShan/XiangShan](https://github.com/OpenXiangShan/XiangShan)
- [OpenXiangShan DiffTest](https://github.com/OpenXiangShan/difftest)
- [XiangShan DiffTest documentation](https://docs.xiangshan.cc/zh-cn/latest/tools/difftest/)

R88 GitHub probe, 2026-06-29: XiangShan remote `HEAD` was
`0aff3b4899fa89fb3e7d48ee5ff336047d47b1ff`; DiffTest remote `HEAD` was
`36062fbd54579220e8aff92bc820e2fd3e749539`. Treat these as flow references,
not LinxCore source dependencies.

Transferable pattern:

1. Keep Chisel generation, emitted RTL, simulator build, and architectural
   cross-check as separate targets.
2. Bind typed commit/trap/memory/recovery events near the design top.
3. Build a Verilator simulator and compare the typed DUT stream against a
   reference, rather than stopping at RTL emission.
4. Keep waveform and event logging bounded to the first mismatch window.

LinxCore adaptation:

- Use `tools/chisel/build_chisel.sh`, `emit_verilog.sh`,
  `run_chisel_verilator_lint.sh`, `run_chisel_reduced_rob_xcheck.sh`,
  `run_chisel_top_xcheck.sh`, and `run_chisel_qemu_crosscheck.sh`.
- Use `tools/chisel/trace_schema_adapter.py` as the typed boundary into
  `tools/trace/crosscheck_qemu_linxcore.py`.
- Define Linx-native `LC-IF-CHISEL-XCHK-*` payloads. Do not import
  RISC-V-specific DiffTest bundles.

## Agent Loop

Each module packet follows this loop:

1. Refresh SHAs and record unrelated dirty files.
2. Read the relevant LinxCoreModel owner files and extract testable invariants.
3. Read the existing Chisel and pyCircuit owner boundaries.
4. Update the module Markdown page before promoting behavior.
5. Implement one owner in one Chisel source file, using shared bundles only
   when multiple owners already need the type.
6. Add focused tests for reset, valid/ready, ordering, flush/recovery,
   identity width, and the model-derived edge case.
7. Run the focused gate, then affected cross-check gates.
8. Decide `skill-evolve: update` or `skill-evolve: no-update`.
9. Commit the module repo, then commit and repin any skill update separately.

Agents must not edit unrelated architecture docs, generated logs, or another
agent's module files. If a packet discovers a cross-module invariant, record it
locally in module docs first and update canonical skills only at closeout.

## First Execution Wave

The ROB/cross-check substrate remains the required base:

| Order | Packet | Primary files | Required gates |
|---|---|---|---|
| 1 | R76 implemented baseline: enqueue-time ROB reservation plus post-rename sidecar update | `DecodeRenameROBPath.scala`, `DispatchROBAllocator.scala`, `ROBEntryBank.scala`, module docs | `DecodeRenameROBPath`, `DispatchROBAllocator`, `ROBEntryBank`, `DecodeRenameQueue`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| 2 | R77 gate broadening and top trace prep | `commit/`, `top/`, `tools/chisel/trace_schema_adapter.py`, wrapper docs | top xcheck, Verilator lint, `build_chisel.sh`, trace self-test, QEMU dry-run |
| 3 | R78 trace replay xcheck harness | `tools/chisel/reduced_rob_trace_tb.cpp`, `tools/chisel/run_chisel_trace_replay_xcheck.sh`, wrapper docs | `run_chisel_trace_replay_xcheck.sh`, top xcheck, trace self-test, QEMU dry-run |
| 4 | R79 frontend-window trace top | `top/LinxCoreFrontendTraceTop.scala`, `F4DecodeWindow.scala`, `DecodeRenameROBPath.scala`, top docs | `LinxCoreFrontendTraceTop`, frontend trace top Verilator lint, `DecodeRenameROBPath`, trace self-test, QEMU dry-run |
| 5 | R80 frontend-window Verilator xcheck | `tools/chisel/frontend_trace_top_tb.cpp`, `tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`, top/cross-check docs | `run_chisel_frontend_trace_top_xcheck.sh`, frontend trace top lint, trace self-test, QEMU dry-run |
| 6 | R81 scalar ALU completion row xcheck | `execute/ReducedScalarAluExecute.scala`, `top/LinxCoreFrontendAluTraceTop.scala`, frontend ALU xcheck driver/script, module docs | `ReducedScalarAluExecute`, `LinxCoreFrontendAluTraceTop`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, old frontend trace-top regression |
| 7 | R82 RF-backed scalar ALU source path | `execute/ReducedScalarRegisterFile.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, shared frontend ALU xcheck driver, RF module docs | `ReducedScalarRegisterFile`, `ReducedScalarAluExecute`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81 ALU regression |
| 8 | R83 reduced scalar issue-queue handoff | `execute/ReducedScalarIssueQueue.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, shared frontend ALU xcheck driver, module docs | `ReducedScalarIssueQueue`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 9 | R84 model-style issued-entry release | `execute/ReducedScalarIssueQueue.scala`, `execute/ReducedScalarAluExecute.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, module docs | `ReducedScalarIssueQueue`, `ReducedScalarAluExecute`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 10 | R85 registered issue source readiness | `execute/ReducedScalarIssueQueue.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, RF/issue/top module docs | `ReducedScalarIssueQueue`, `ReducedScalarAluExecute`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 11 | R86 oldest-ready reduced issue selection | `execute/ReducedScalarIssueQueue.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, RF/issue/top module docs | `ReducedScalarIssueQueue`, `ReducedScalarAluExecute`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 12 | R87 reduced issue-pick read-confirm owner | `execute/ReducedScalarIssuePick.scala`, `execute/ReducedScalarIssueQueue.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, issue/top module docs | `ReducedScalarIssuePick`, `ReducedScalarIssueQueue`, `ReducedScalarAluExecute`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 13 | R88 reduced P1/I1/I2 issue timing | `execute/ReducedScalarIssuePick.scala`, `execute/ReducedScalarIssueQueue.scala`, `top/LinxCoreFrontendRfAluTraceTop.scala`, issue/top module docs | `ReducedScalarIssuePick`, `ReducedScalarIssueQueue`, `LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, R81/R82 trace regressions |
| 14 | Live commit trace schema | `commit/`, `top/`, `tools/chisel/trace_schema_adapter.py` | `trace_schema_adapter.py --self-test`, reduced/top/replay/frontend-top gates |
| 15 | R89 QEMU cross-check manifest evidence | `tools/chisel/run_chisel_qemu_crosscheck.sh`, cross-check docs | dry-run, RF/ALU xcheck, ALU xcheck, trace self-test, diff check |
| 16 | R90 QEMU trace replay harness | `tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh`, trace replay wrapper docs | dry-run, archived/fresh QEMU JSONL replay with metadata-aware raw prefix, manifest inspection |
| 17 | R91 bounded CoreMark ELF replay prefix | `tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh`, trace replay wrapper docs | default-memory fail-fast check, CoreMark `--elf` replay with explicit `-m 1280M`, manifest inspection |
| 18 | R92 shared commit JSONL writer | `tools/chisel/commit_trace_jsonl.h`, generated-RTL Verilator harnesses, cross-check docs | reduced/top/replay/frontend generated-RTL xchecks, trace self-test, QEMU dry-run, diff check |
| 19 | R93 frontend fetch packet source | `frontend/FrontendFetchPacketSource.scala`, module docs/tests | `FrontendFetchPacketSource`, `F4DecodeWindow`, `FrontendDecodeIngress`, trace self-test, QEMU dry-run |
| 20 | R94 live frontend fetch trace top | `top/LinxCoreFrontendFetchTraceTop.scala`, `frontend/FrontendFetchPacketSource.scala`, live Chisel trace writer | `LinxCoreFrontendFetchTraceTop`, `run_chisel_frontend_fetch_trace_top_xcheck.sh`, manifest inspection |
| 21 | R95 live frontend fetch RF-backed ALU trace top | `top/LinxCoreFrontendFetchRfAluTraceTop.scala`, `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`, top/cross-check docs | `FrontendFetchPacketSource`, `LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, manifest inspection |
| 22 | R96 file-backed frontend fetch memory feeder | `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`, `tools/chisel/frontend_fetch_rf_alu_fixture_memory.py`, top/cross-check docs | live fetch RF/ALU xcheck, manifest inspection, trace self-test, QEMU dry-run |
| 23 | R97 sparse ELF fetch-memory extractor | `tools/chisel/frontend_fetch_elf_memory.py`, `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`, `tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, top/cross-check docs | ELF extractor self-test, live fetch RF/ALU xcheck through sparse memory, manifest inspection |
| 24 | QEMU/ELF row-source binding | frontend source/top harness and QEMU trace prefix fixture | live fetch RF/ALU xcheck with PCs/instructions from a bounded QEMU/ELF prefix plus manifest inspection |
| 25 | Dense multi-slot frontend packet path | F4/source/top harness | live fetch xchecks with multi-slot windows and manifest inspection |
| 26 | Live QEMU full-compare harness | `tools/chisel/run_chisel_qemu_crosscheck.sh`, live Chisel trace writer | dry-run, manifest inspection, then full compare on a bounded direct-boot smoke |
| 27 | Multi-PE/STID bank expansion | frontend packet production plus T/U bank array | PE/STID-specific rename and retire-source gates |
| 28 | LinxCoreModel ROB maintenance note | `docs/chisel/model-notes/ROBCommit.md` and model-lane notes | documentation check plus model ownership review |

R76 implemented the reservation/update split at `rtl/LinxCore` commit
`11529bf345c407fe1c7614973e61b68be8d99fb4`. Future agents must not
reintroduce queue-head ROB allocation or reserve a row with permanent zero T/U
sidecars. R77 owns gate broadening and cross-check payload prep around the new
split; it must not redesign the split unless a failing gate produces concrete
first-divergence evidence.

## Cross-Check Ladder

Use this ladder for every promoted packet:

1. `bash tools/chisel/run_chisel_tests.sh --only <module>`
2. Adjacent module tests listed in that module's Markdown page.
3. `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob` for ROB,
   commit, allocation, or recovery changes.
4. `python3 tools/chisel/trace_schema_adapter.py --self-test` for trace schema
   changes.
5. `bash tools/chisel/run_chisel_top_xcheck.sh` for top-level IO or commit
   monitor changes.
6. `bash tools/chisel/run_chisel_trace_replay_xcheck.sh` for any top-level
   commit export, adapter, or cross-check harness changes.
7. `bash tools/chisel/run_chisel_frontend_trace_top_lint.sh` for any
   frontend-window-to-commit top boundary changes.
8. `bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh` after changes
   to the frontend trace-top driver, completion surrogate, or commit export.
9. `bash tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh` after
   changes to the live frontend fetch source top, bounded memory-window
   fixture, source-to-F4 handshake, completion surrogate, or commit export.
10. `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh` after
   changes to scalar ALU execute completion, completion-row payload wiring, or
   the frontend ALU trace-top driver.
11. `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh` after
   changes to scalar RF operand sourcing, registered issue-queue source
   readiness, oldest-ready issue selection, issue-pick read-confirm gating,
   physical writeback metadata, or the shared frontend ALU trace-top driver.
12. `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
   after changes to the live fetch source to RF-backed issue/ALU top, bounded
   memory-window fixture, source PC advance, issue enqueue, RF writeback, ALU
   completion, or commit export.
13. `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` for wrapper or
   QEMU-selection changes.
14. `bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh --dry-run` for
   QEMU-row replay wrapper changes, then replay a bounded archived or fresh
   QEMU JSONL with `--qemu-trace` or `--elf`. Use `--replay-rows` only to cap
   the raw search window; `--max-commits` remains the architectural compare
   window.
15. Inspect `<report-dir>/crosscheck_manifest.json` after any non-dry-run
   generated-RTL or QEMU comparison that routes through the common wrapper.
16. Full QEMU-vs-DUT comparison only after the Chisel top emits real
   architectural commit rows.
17. `gfsim -f <elf>` only after the same ELF passed QEMU in the same run packet.

## Project Maintenance

At the start of a multi-agent run:

```bash
git -C /Users/zhoubot/linx-isa fetch origin
git -C /Users/zhoubot/linx-isa/rtl/LinxCore fetch origin
git -C /Users/zhoubot/linx-isa/model/LinxCoreModel fetch origin
git -C /Users/zhoubot/linx-isa/skills/linx-skills fetch origin main
```

Do not merge or checkout over dirty submodules. Summarize SHA deltas and carry
unrelated dirt forward without staging it.

At closeout, update:

- the module Markdown page,
- `docs/chisel/agent-loop.md` if the packet advances the ledger,
- `docs/chisel/development-loop.md` only for reusable plan changes,
- canonical skills only for reusable policy,
- the superproject gitlink only after the module or skill submodule commit.

## Skill-Evolve Decision

Every packet ends with one line:

```text
skill-evolve: update <skill-list> (<material reusable finding>)
skill-evolve: no-update (<reason this is local or already documented>)
```

Update skills for:

- a new cross-module ROB/BROB/flush/trace invariant,
- a new required gate or command,
- a new required cross-check evidence artifact such as the QEMU/DUT manifest,
- a recurring QEMU/model/Chisel first-divergence workflow,
- a superproject or skills-submodule maintenance rule future agents must obey.
- a ready/valid rule where physical source readiness must gate issue from a
  queued row, while reduced rename acceptance remains queue-capacity driven.
- an issue-queue lifetime rule where execute acceptance marks a row issued but
  removal waits for a later model-derived release identity.
- a live-source promotion rule where future replacement evidence must remove
  testbench-owned frontend packets before claiming a top can consume fetch
  traffic.

Do not update skills for wording cleanup, one-off test vectors, or module-local
implementation detail already captured in that module page.

## Launch Template

Use this prompt shape for future agents:

```markdown
Packet: <name>

Owned files:
- `<path>`

Read first:
- `docs/chisel/development-loop.md`
- `docs/chisel/agent-loop.md`
- `<module Markdown page>`
- `<LinxCoreModel source files>`

Contract:
- `<model-derived invariant>`

Target:
- `<exact Chisel/doc/test change>`

Gates:
- `<focused commands>`

Closeout:
- update module docs and packet ledger
- report `skill-evolve: update|no-update`
- commit only owned files
```
