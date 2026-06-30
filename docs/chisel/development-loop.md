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
R98 started from `linx-isa` commit
`72faf6b0fdc3ae0ec85b9abbf6fcbdf1e1285d71`, `rtl/LinxCore` commit
`0f74eb380193f9a56132905fc782d533e457c1b0`, `model/LinxCoreModel` commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`9c591ac219f23894b0ad23b5a32b18898e34d85f`. R98 binds the live fetch RF/ALU
Verilator gate to an external expected-row source: the wrapper emits
`fixture.expected.jsonl` by default, accepts `FETCH_EXPECTED_ROWS=<jsonl>` for
QEMU-shaped commit rows, passes `--expected-rows` to the harness, and sizes the
neutral comparator window from the expected row count instead of a hardcoded
three rows. The reduced top still serves one instruction window per expected
row and only supports the current scalar ADD/ADDI/MOVR envelope, so this is a
row-source contract and not full QEMU/CoreMark equivalence.
R98 closeout: skill-evolve: update linx-core (new reusable
`FETCH_EXPECTED_ROWS` row-source contract and fixture-row self-test for the
live fetch RF/ALU gate).
R99 started from `linx-isa` commit
`66f88fb97e82293d0139c110fe491444d105f5f3`, `rtl/LinxCore` commit
`568926ee2ee87c1f0cdb6e01c919897294e17c59`, `model/LinxCoreModel` commit
`6a87678d48b6a58a106d3ca23206744463e529f5`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and `skills/linx-skills` commit
`514c3792ed70b16a80c33e1b0e11a271b06975b7`. The packet fetched remotes
without merging over dirty worktrees; LinxCoreModel fast-forwarded to
`origin/main`, while LinxCore `origin/main` was not merged because it removes
the local Chisel development tree. R99 adds `frontend_fetch_rf_alu_qemu_rows.py`
and `FETCH_QEMU_TRACE`: an existing QEMU commit JSONL can now feed the R98
expected-row boundary after strict reduced-scalar validation. The bridge
requires a sequential prefix of `ADD`, `ADDI`, `C.MOVI`, or `C.MOVR` rows,
scalar GPR tags, no memory/trap side effects, matching writeback, and
`next_pc == pc + len`. The verified gate used
`FETCH_QEMU_TRACE=generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.expected.jsonl`
and the default fixture path; both produced a passing manifest with
`compared_rows: 3` and `mismatch_count: 0`. This is still not broad
QEMU/CoreMark equivalence.
R100 started from `linx-isa` commit
`f0d8c8368c9954ff08d6b998a94ddca9c3d4661b`, `rtl/LinxCore` commit
`58c0c4768748f18f665c9ba549c1b33888d9e9fa`,
`model/LinxCoreModel` commit
`6a87678d48b6a58a106d3ca23206744463e529f5`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and
`skills/linx-skills` commit `32d1829b976c1bca0a9c728361da52e1dac842b3`.
R100 adds a reduced live-QEMU direct-boot ELF gate for
`LinxCoreFrontendFetchRfAluTraceTop`: the fixture builder emits a legal-entry
ELF (`C.BSTART.STD; ADD; ADDI; C.MOVR; C.BSTOP`), the new wrapper captures a
bounded QEMU commit prefix through a FIFO, filters the scalar PC range after
the legal `BSTART`, validates rows through the R99 strict reducer, extracts
matching ELF PT_LOAD bytes through `FETCH_ELF`, and runs the generated-RTL
comparator. The Verilator harness now derives initial RF preloads from the
first expected source reads instead of hardcoding the synthetic fixture's
`r4=10, r5=32`; this lets QEMU's zero-valued initial sources and the older
synthetic rows share one expected-row contract. Evidence:
`build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r100-live-qemu-fixture`
and
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 3 --capture-rows 3 --pc-lo 0x10002 --pc-hi 0x1000b --max-seconds 5`
passed with `compared_rows: 3` and `mismatch_count: 0`. This is still a
reduced scalar prefix gate; block headers, dense packets, memory/trap rows,
LSU, recovery, and CoreMark live-DUT equivalence remain future packets.
R101 started from `linx-isa` commit
`b6dcb8f6c48dd6bf247ba9bec2d27d2f33be78fb`, `rtl/LinxCore` commit
`77001f0ec3b463950c1a275fcb5e3dc1638d73cd`,
`model/LinxCoreModel` commit
`6a87678d48b6a58a106d3ca23206744463e529f5`, QEMU commit
`f03477a0f56aeffb82a304e3a553b31cc2d29879`, and
`skills/linx-skills` commit `85c027e05b1cdffda7080d8482c0a7b160a2d672`.
Fetches confirmed LinxCore and superproject branches were ahead of
`origin/main`, LinxCoreModel `main` matched `origin/main`, and
`origin/SuperScalarModel` was one commit ahead for model-maintenance study but
was not checked out over the canonical model lane. R101 adds a reduced
block-marker consume path for the live fetch RF/ALU gate: legal `C.BSTART` and
`C.BSTOP` rows from QEMU are preserved as `skip` entries, fetched and decoded
by the DUT, and asserted not to allocate ROB rows or enter issue. The
architectural comparator still receives only the three scalar rows. Evidence:
`build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r101-live-qemu-fixture`
and
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
captured five raw QEMU rows, preserved skip markers at `0x10000` and
`0x1000c`, and passed with `compared_rows: 3` and `mismatch_count: 0`. This is
not full block execution: dense mixed marker/scalar packets, scalar_done/BROB
retire semantics, LSU, recovery, and CoreMark live-DUT equivalence remain
future packets.
R102 started from `linx-isa` commit
`891555bda192f319627c3a20cb6e906b5bbe94ee`, `rtl/LinxCore` commit
`7eecd8e2770658c633bd52e65654a92d61f7b4e0`,
`model/LinxCoreModel` commit
`6a87678d48b6a58a106d3ca23206744463e529f5`, QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`, and
`skills/linx-skills` commit `f1290432600791a9bd1f47e31dbab10b9e41ec63`.
Remote metadata was fetched without merging over dirty worktrees. R102 adds
`F4DenseSlotQueue`, a reduced dense-packet bridge from `F4DecodeWindow` to the
serialized `DecodeRenameROBPath`: it captures every valid slot from one
8-byte F4 window, preserves original slot indices, and drains one slot per
cycle. This aligns the reduced live fetch RF/ALU gate with the model IFU
`DecodeBundle` path (`mask`, `entry[]`, `isize[]`, and `tpcArr[]`) while
deferring width-wide ROB allocation. Evidence:
`build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r102-live-qemu-fixture`,
`BUILD_DIR=generated/r102-default-fetch-rf-alu-trace-top-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`,
and
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r102-dense-qemu-elf-xcheck --elf generated/r102-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
passed with `compared_rows: 3` and `mismatch_count: 0`. The R102 marker
contract is marker-owned: a marker slot must not select a scalar row, push
dec/ren, or allocate ROB, but it may drain in the same cycle that an older
scalar row enqueues to issue. Full block scalar-done/BROB semantics, LSU,
recovery, and CoreMark live-DUT equivalence remain future packets.
R104 started from `linx-isa` commit
`a5ebce3b003470895ddd3a58790f8e70125b0b12`, `rtl/LinxCore` commit
`326095a9622ebdb3599bc0b8cd737917b0fdf9ea`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`, with unrelated architecture
Markdown edits already dirty in the LinxCore worktree. R104 adds the reduced
marker-owned block lifecycle after R103: consumed `BSTART` rows allocate a
BROB-only active BID, scalar rows reuse that active full BID for ROB identity
and commit sideband, and consumed `BSTOP` rows pulse scalar done for the active
BID before the normal one-cycle-later BROB retire pulse. This remains reduced
marker-consume timing, not full marker ROB retirement or recovery-exact block
control. Evidence:
`build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r104-live-qemu-fixture`
and
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r104-marker-lifecycle-qemu-elf-xcheck --elf generated/r104-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`
passed with `compared_rows: 3` and `mismatch_count: 0`.
R105 started from `linx-isa` commit
`39d975bdf589923354ddbb300b49a40cb5e36313`, `rtl/LinxCore` commit
`b487843427125df2aca2284bd17e0578f31e32e0`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`. R105 adds an opt-in
`--long-body` mode to the live-QEMU legal-entry fixture so the current reduced
scalar ALU subset runs seven scalar commits under one marker-owned active
block instead of only the three-row smoke. Evidence:
`build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r105-long-body-fixture --long-body`
and
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r105-long-body-qemu-elf-xcheck --elf generated/r105-long-body-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 9 --allow-block-markers --max-seconds 5`
passed with `compared_rows: 7` and `mismatch_count: 0`.
R106 started from `linx-isa` commit
`a5f99f55dd76542de6178dcb144e4ae35da68c2c`, `rtl/LinxCore` commit
`96bf4fc077ec9df6759a723c3caf5ffc13154d98`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`. R106 expands the reduced
RF/ALU live-QEMU envelope with `ADDTPC`, the first unsupported CoreMark
`_start` scalar row after `C.BSTART` and `C.MOVR`. Model evidence is
`PC::CalcInstAddTPC`, which computes `(pc & ~0xfff) + imm`, with
`FrontendOperandDecode` already sign-extending `IMM20` and shifting it by 12.
The QEMU probe before the patch failed at `pc=0x400054f8 insn=0x9187`, and the
R106 gate compares the first four CoreMark rows before the next unsupported
HL call marker. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 4 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 3`, and
`summary.mismatch_count: 0`.
R107 started from `linx-isa` commit
`96de804b785f3cd16b579c1bf21399152761fb0c`, `rtl/LinxCore` commit
`59a64a0746b07346e88e0c9ea1e3319e11f85603`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`. R107 expands the reduced
RF/ALU live-QEMU envelope through the first CoreMark direct-call header:
`HL.BSTART.STD CALL` is preserved as a skip marker, compact `C.SETRET`
computes `pc + (uimm5 << 1)`, and `C.BSTOP` redirects the reduced fetch source
to the active `BSTART` target instead of executing filler markers. The
model/QEMU contract is `PC::CalcInstSetret` for PC-relative return setup,
`BCtrl`/IFU target-bearing `BSTART` semantics, and QEMU's duplicate
zero-advance HL marker artifact followed by the advancing marker row. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r107-coremark-hl-call-setret-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 8 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 4`, and
`summary.mismatch_count: 0`.
R108 started from `linx-isa` commit
`adff352229122a0826951c0549424abaa8f2a53f`, `rtl/LinxCore` commit
`9ba736f4851417da7b06d5b8813e344ddca5c3a8`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`8dd1dcdbde20a7543fc5081bc52fd81a7b85b985`. R108 adds the first reduced
single-save `FENTRY` macro-template row to the live RF/ALU path. The reduced
decoder reads the saved GPR and SP internally, writes the decremented SP, and
emits the QEMU-shaped 8-byte store sideband while suppressing internal source
fields in the commit row. This is only the current CoreMark single-save
prologue shape, not full stack-template or LSU execution. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r108-coremark-fentry-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 11 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 6`, and
`summary.mismatch_count: 0`. A 12-row probe stops at the next unsupported row:
`ADDI` writing architectural tag `30`.
R109 started from `linx-isa` commit
`8a0a20d691cf9021d1b3ec96df7265668168ee4e`, `rtl/LinxCore` commit
`795e78fb6dc68863e0fcf108f7b2d9f2375249e0`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`085f20cc8bda1dbc5d34808fda3effb4afc19b77`. R109 lets the reduced live
RF/ALU path compare that CoreMark `ADDI` writing architectural tag `30` without
treating the U alias as a scalar RF register. `ScalarTURenameBridge` already
owns the T/U destination overlay from the model `OPD_ULINK` path; the reduced
issue and execute owners now gate scalar RF clear/write side effects to
`DestinationKind.Gpr`, while the commit row still carries the QEMU-shaped
`dst_reg=30` and `wb_rd=30` architectural fields. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 7`, and
`summary.mismatch_count: 0`. A 13-row probe stops at the next unsupported row:
`OP_HL_LUI` at `pc=0x4000551a`, `insn=0x1f97000e`, `len=6`.
R110 started from `linx-isa` commit
`86966dc063f772b4f2e8581fa7593392db764711`, `rtl/LinxCore` commit
`0849e42300025d20f92535b070f4e61889315e3c`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, and QEMU commit
`085f20cc8bda1dbc5d34808fda3effb4afc19b77`. R110 lets the reduced live
RF/ALU path compare CoreMark's `HL.LUI` row at `pc=0x4000551a`. The frontend
extracts `IMM32=1` from `Cat(pfx16[15:4], main32[31:12])`, classifies
destination architectural tag `31` as `DestinationKind.T`, and the reduced ALU
emits the immediate as the QEMU-shaped destination/writeback data while
keeping scalar RF writeback gated to `DestinationKind.Gpr`. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 8`, and
`summary.mismatch_count: 0`. A 14-row probe stops at the next unsupported row:
`OP_SLL` at `pc=0x40005520`, `insn=0x01cc7f05`, `len=4`; that row uses T/U
local-register sources (`rs1=24`, `rs2=28`) and writes U destination tag `30`.
R111 started from `linx-isa` commit
`ff141137fd29c85afb49978be0f306a2679108db`, `rtl/LinxCore` commit
`2d5cbcaf234d32fc773792b5a36279d1063f5287`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`883737038a7b2ee2a76f84e5d9383b0166e7eeaf`, and
`skills/linx-skills` commit
`ae6d7fc6d76dc4abfd05d8d6e53072cdf9029be0`. R111 lets the reduced live
RF/ALU path compare that CoreMark `OP_SLL` row. The issue path samples local
T/U readiness separately from scalar RF readiness, the top feeds T/U source
data from a small reduced overlay populated by earlier U/T destination rows,
and execute emits a QEMU-shaped row with scalar source fields suppressed. The
same packet fixes the first reduced ROB wrap case: the `SLL` row is the ninth
scalar allocation in an 8-entry generated top, so `DecodeRenameROBPath` must
stamp queued `rid.wrap` from `DispatchROBAllocator.allocRobWrap` instead of
using a false-wrap RID. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 9`, and
`summary.mismatch_count: 0`.
R112 started from `linx-isa` commit
`3eab55854a97ee46f7525c28562abf33a78abf7c`, `rtl/LinxCore` commit
`e4d8f6bae895a562afb4f254660ffe6be8174b9d`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`883737038a7b2ee2a76f84e5d9383b0166e7eeaf`, and
`skills/linx-skills` commit
`60e19eede12c6ac452526a70f0c9b163a0a92d91`. R112 lets the reduced
live RF/ALU path compare the next CoreMark local shift pair. `SLL` at
`pc=0x4000552a` writes architectural T tag `31`, and `SRL` at
`pc=0x4000552e` reads local T/U sources and writes T tag `31`; both rows keep
scalar source fields suppressed and avoid scalar RF writeback side effects.
Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 12`, and
`summary.mismatch_count: 0`. An 18-row probe stops at `OP_OR`
(`pc=0x40005532`, `insn=0x078e3f05`, `len=4`), which reads local U0/T0 and
writes U0.
R113 started from `linx-isa` commit
`6fba7b5a6e31219c5e548e8f7c75224516ca853f`, `rtl/LinxCore` commit
`c0b9ca12224408e964e729661a07e21dc04aa04b`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`883737038a7b2ee2a76f84e5d9383b0166e7eeaf`, and
`skills/linx-skills` commit
`9f3f2163fd493606c486f7bf78c3dcf4fd12b8fb`. R113 lets the reduced
live RF/ALU path compare `OP_OR` at `pc=0x40005532` and the following
`C.LDI` zero-load sideband row at `pc=0x40005536`. `OP_OR` reads local U0/T0
and writes U0; the narrow `C.LDI` row reads scalar x4, emits an 8-byte load
sideband with zero data, writes T0, and remains a prefix bridge rather than a
general data-memory implementation. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 14`, and
`summary.mismatch_count: 0`. A 21-row extraction probe identifies the next
unsupported row as `OP_C_ADD` (`pc=0x4000553c`, `insn=0xe608`, `len=2`) after a
supported same-window `SLL` at `pc=0x40005538`.

R114 started from `linx-isa` commit
`d1dde8e68ea6bf92b11498a963ede4b4494a6d60`, `rtl/LinxCore` commit
`00bf5f15f11355b86a07b7a95d333811db5430d0`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`c561976c60fa5f76f00987563772283a4f2d9b97`, and
`skills/linx-skills` commit
`7a785081ce8aacb293646ebb57016f0186ce4502`. R114 lets the reduced live
RF/ALU path compare `OP_C_ADD` at `pc=0x4000553c`. LinxCoreModel
`block16.decode` marks compressed arithmetic as `src0=%SrcL`, `src1=%SrcR`,
and implicit `dst0=31`; for `insn=0xe608` those fields read T0/U0 and write
T0. Current QEMU commit JSONL omits the C.ADD local destination/writeback
fields, so the expected-row reducer synthesizes only that implicit T writeback
from QEMU PC/instruction order plus the local-value queue. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r114-coremark-c-add-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 21 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 16`, and
`summary.mismatch_count: 0`. A 22-row extraction probe identifies the next
unsupported row as `OP_SRA` (`pc=0x4000553e`, `insn=0x01ec6f85`, `len=4`);
that row reads local T/U sources and writes T tag `31`.

R115 started from `linx-isa` commit
`7ba7616a1c63d0dfdc70a96e1ad411b0705dcd63`, `rtl/LinxCore` commit
`088d80f20b9a9c3a6dc40acc5ceab9855056b40d`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`c561976c60fa5f76f00987563772283a4f2d9b97`, and
`skills/linx-skills` commit
`4233a49dc85989d5285dd3efc29495e270bc5f05`. R115 lets the reduced live
RF/ALU path compare `OP_SRA` at `pc=0x4000553e` and the paired `OP_SLLI` at
`pc=0x40005542`. LinxCoreModel `block32.decode` maps `SRA` to `@shift` and
`SLLI` to `@shift_i`; `SRA` uses signed 64-bit arithmetic right shift by
`SrcR & 0x3f`, while `SLLI` uses `shamt_20_25`. Because the live frontend
emits `SRA` and `SLLI` in the same dense packet, the reduced top now stalls
younger rename output while a local T/U producer is pending. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r115-coremark-sra-slli-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 23 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 18`, and
`summary.mismatch_count: 0`. A 24-row extraction probe identifies the next
frontier as mixed local/scalar `C.ADD` (`pc=0x40005546`, `insn=0x2608`,
`len=2`): compressed `SrcL` is T0, compressed `SrcR` is scalar x4, QEMU emits
the scalar source field, and current QEMU emits no destination/writeback.

R115 closeout: `skill-evolve: update linx-core (shift-immediate rows need
explicit shamt extraction despite generated ImmNONE metadata, and same-packet
local T/U consumers need an ordered producer stall in the reduced top)`.

R116 started from `linx-isa` commit
`a92aa18f560b52df6c7b10e8ae7cfc4d136be7b7`, `rtl/LinxCore` commit
`b332ab2427df2c63ff18e3b1c422bd232ec1315b`,
`model/LinxCoreModel` commit
`1993e4e749403824a4908548baf77d5e15117068`, QEMU commit
`c561976c60fa5f76f00987563772283a4f2d9b97`, and
`skills/linx-skills` commit
`87b46a294ef1e5a0b6eefe3c827d1bf21ce00372`. R116 extends the live reduced
RF/ALU CoreMark prefix through the full dense packet beginning at
`pc=0x40005546`: mixed local/scalar `C.ADD` (`insn=0x2608`) reads compressed
`SrcL=T0` plus scalar `SrcR=x4` and synthesizes QEMU's missing implicit T
writeback, the following `ADDI` (`insn=0x018c0115`) reads encoded `SrcL=T0`
with QEMU's local source field suppressed and writes scalar x2, and `C.MOVR`
(`insn=0x2806`) moves scalar x0 into x5. The expected-row reducer now validates
encoded source fields by checking whether each encoded register is a local T/U
alias or scalar GPR, instead of hard-coding opcode-wide source-valid patterns.
A 24-row gate is intentionally too short because it cuts through the three-slot
dense packet; use the 26-row gate for this packet. Evidence:
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r116-coremark-c-add-mixed-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 26 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
passed with `status: "pass"`, `summary.compared_rows: 21`, and
`summary.mismatch_count: 0`. A 27-row probe still passes and shows the next raw
row as a zero-advance `C.BSTART` artifact at `pc=0x4000554e`, `insn=0x0004`;
the current marker-skip logic already drops that artifact.

R116 closeout: `skill-evolve: update linx-core (QEMU-shaped expected rows for
encoded sources must validate each source independently as scalar or suppressed
local T/U; dense-packet gates must not stop inside an 8-byte fetch window)`.

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
| `model/bctrl/spe/SPEROB.cpp` / `model/bctrl/BROB.cpp` | R103 block lifecycle evidence: `SPEROB::dealloc` stops at block-last and calls `CommitLast`; `CommitBlock` marks the block complete through `SetBlockComplete`; `BlockROB::commitBlock` retires only completed block entries. The reduced Chisel path now preserves the full block BID on ROB block-last deallocation, drives BROB scalar completion, and retires it one cycle later while leaving marker-owned old/current BID semantics for a later packet. |
| `model/iex/pipe/alu_pipe.cpp` | `ALUPipe::Work` executes in the ALU pipe and publishes resolve/writeback at W2. |
| `isa/calculate/arithmetic/Arithmetic.cpp` / `isa/calculate/others/Others.cpp` / `isa/calculate/pc/PC.cpp` | Reduced scalar ALU semantics are `ADD = src0 + src1`, `ADDI = src0 + imm`, `MOVR/MOVI` move the selected source/immediate into the destination, `ADDTPC = (pc & ~0xfff) + imm`, `HL.LUI` materializes its decoded IMM32, and `SLL = SrcL << (SrcR & 0x3f)`. |
| `model/bctrl/spe/GPRRename.cpp` / `model/iex/rtable.cpp` / `model/iex/iex_rf.cpp` | R82 scalar RF operand sourcing uses renamed physical tags: architectural GPRs start as identity physical tags, scalar destinations allocate new physical tags, ready/data state is tracked per physical tag, and RF reads return OPD_GREG data by physical tag. |
| `model/iex/iex_iq.cpp` / `model/iex/iex_dispatch.cpp` / `model/iex/pipe/iex_pipe.h` / `model/iex/pipe/alu_pipe.cpp` / `model/iex/iex.cpp` | R88 scalar issue handoff stores renamed rows before execute, initializes and wakes sources by physical tag, selects the oldest resident ready non-issued entry, marks selected entries issued without removing them, separates P1 pick from I1 RF-read request and I2 RF-return consumption in the pipe model, and releases issued rows later by `(bid, rid, stid)`. The reduced Chisel queue preserves enqueue, registered RF-readiness gating, ROB identity, oldest-ready resident selection, P1 in-flight lock, I1 read-cancel unlock, I2 execute handoff, issued-entry residency, and ALU W2 release while still deferring full read-port arbitration, alternate select preferences, load miss suppression, replay, and bypass. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R93 frontend fetch source evidence: F0 captures `fetchTPC`, block/thread identity, `fid`, and sidebands in `FetchReqBus`; F2 fills instruction window data and sizes with `CheckMInstSize`; F3/F4/F5 move `DecodeBundle` records under backpressure. The reduced Chisel source preserves one-outstanding PC request, response-window packetization, packet-owned UID/checkpoint, restart/flush clearing, and downstream decoded-byte PC advance while deferring cacheline merge, branch prediction, RAHQ, ELF memory loading, and multiple outstanding requests. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/bctrl/spe/DCTop.cpp` / `model/bctrl/spe/SPEROB.cpp` / `model/interface/CommitInfo.h` | R94 live frontend fetch trace-top evidence: model fetch requests become decode bundles before scalar decode/ROB allocation, and commit rows preserve model `(bid,gid,rid)` identity separately from hardware block identity. The Chisel top preserves the reduced form by feeding source-produced packets into F4 and `DecodeRenameROBPath`, advancing source PC from F4 decoded byte count, and dumping generated-RTL commit rows through the shared JSONL writer and neutral comparator. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/bctrl/spe/DCTop.cpp` / `model/bctrl/spe/GPRRename.cpp` / `model/iex/iex_iq.cpp` / `model/iex/iex_rf.cpp` / `model/iex/pipe/alu_pipe.cpp` / `model/interface/CommitInfo.h` | R95 live frontend fetch RF/ALU evidence: source-produced packets flow through F4 into scalar decode/rename/ROB allocation, RF-backed issue residency, RF source reads, ALU W2 completion, destination RF writeback, and commit-row export. The reduced Chisel top preserves source PC request/response ownership, F4 byte-count PC advance, queue-capacity rename acceptance, RF-readiness-gated issue, model-derived issue release identity, and separate model commit identity versus hardware block identity while still using a bounded instruction-window fixture. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R96 fetch-memory harness evidence: the model IFU boundary requests a PC and returns instruction bytes plus size-derived decode metadata before downstream scalar ownership. The reduced Chisel gate now reads response instruction bytes from a reusable memory image at the requested PC before packaging the bounded response window. It intentionally keeps one expected instruction length per response until the dense multi-slot packet path is implemented. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/ModelCommon/bus/FetchReqBus.h` / `model/pe/PECommon/DecodeBundle.h` / `isa/ISACommon/DecodeUtiles.h` | R97 sparse ELF fetch-memory evidence: IFU request/response ownership is address based, so harness memory must support non-contiguous and high-address program bytes before CoreMark-style direct-boot images can become live fetch inputs. The Chisel harness now accepts sparse address-to-byte memory extracted from ELF PT_LOAD segments while keeping expected row selection as a separate future owner. |
| `model/interface/CommitInfo.h` / `model/pe/ifu/iside/pe_ifu.cpp` / `model/bctrl/spe/DCTop.cpp` / `model/iex/pipe/alu_pipe.cpp` | R98 expected-row source evidence: commit comparison is already a QEMU-shaped architectural row stream, while live fetch requests still need row-owned PC, instruction length, source data, and destination data to drive the reduced scalar top. The harness now consumes those expected rows from JSONL instead of deriving them from an internal fixture, preserving the model split between IFU memory bytes, scalar execution data, and commit-row comparison. |
| `model/interface/CommitInfo.h` / QEMU commit JSONL / `tools/chisel/trace_schema_adapter.py` | R99 row extraction evidence: QEMU and DUT comparison already meet at normalized commit rows, but the reduced live-fetch RF/ALU top can only execute a strict sequential scalar ALU prefix. The extractor keeps QEMU row spelling and adapter normalization, then rejects rows outside the reduced scalar envelope before they become Verilator expected rows. |
| QEMU commit JSONL / `tools/qemu/run_qemu_commit_trace.sh` / `tools/chisel/frontend_fetch_elf_memory.py` | R100 live capture evidence: the reduced gate can now collect bounded QEMU rows from a direct-boot ELF and feed the validated scalar prefix plus matching ELF bytes into the live fetch RF/ALU top. PC filters are still required to skip legal block headers until that instruction class is live in the DUT. |
| QEMU commit JSONL / `tools/chisel/frontend_fetch_rf_alu_qemu_rows.py` / `DecodeRenameROBPath` | R101 marker evidence: the reduced gate can consume legal single-instruction `BSTART`/`BSTOP` rows as frontend markers while keeping them out of the architectural commit comparator. Marker-only packets must advance fetch without BROB/ROB allocation or issue enqueue; R102 supersedes the earlier mixed-packet limitation with a dense-slot queue. |
| `model/pe/ifu/iside/pe_ifu.cpp` / `model/pe/PECommon/DecodeBundle.h` / `model/bctrl/spe/DCTop.cpp` / QEMU commit JSONL | R102 dense-slot evidence: model IFU carries `DecodeBundle.mask`, `entry[]`, `isize[]`, and `tpcArr[]` through F4/F5/IB before scalar decode iterates rows. The reduced Chisel gate now captures all valid slots from one 8-byte F4 window in `F4DenseSlotQueue` and drains them serially into the existing one-row ROB path, preserving marker rows as DUT-only skip slots and scalar rows as comparator commits. |
| `model/bctrl/BCtrl.cpp` / `model/bctrl/BROB.cpp` / `model/bctrl/spe/SPEROB.cpp` / `model/pe/PEBase.cpp` | R104 marker lifecycle evidence: `BCtrlUnit::RunFetchStage5` allocates BROB for block-start markers, keeps the resulting BID as the current block identity, stamps following scalar rows from that active BID, and treats `BSTOP` as the current block's terminal marker. `SPEROB::CommitBlock` then calls `SetBlockComplete`, and `BlockROB::commitBlock` retires only contiguous completed block entries. The reduced Chisel path now allocates BROB-only on consumed `BSTART`, reuses the active full BID for scalar ROB rows, and completes the active BID on consumed `BSTOP`. |

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
| 24 | R98 external expected-row source binding | `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`, `tools/chisel/frontend_fetch_rf_alu_fixture_rows.py`, live fetch RF/ALU wrapper/docs | fixture row self-test, live fetch RF/ALU xcheck through `FETCH_EXPECTED_ROWS`, manifest inspection |
| 25 | R99 strict QEMU trace expected-row extraction | `tools/chisel/frontend_fetch_rf_alu_qemu_rows.py`, live fetch RF/ALU wrapper/docs | QEMU-row helper self-test, live fetch RF/ALU xcheck through `FETCH_QEMU_TRACE`, default fixture regression, manifest inspection |
| 26 | R100 live QEMU capture plus reduced-row selection | `tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`, `tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh`, live fetch RF/ALU harness/docs | bounded QEMU trace capture, `FETCH_QEMU_TRACE` plus `FETCH_ELF`, row-derived RF preload regression, manifest inspection, explicit unsupported-row report |
| 27 | R101 reduced block-marker skip path | `DecodeRenameROBPath.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, QEMU-row extractor, live harness/docs | live fetch xchecks with BSTART/BSTOP skip rows, no PC filter, manifest inspection |
| 28 | R102 reduced dense multi-slot frontend packet path | `F4DenseSlotQueue.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, live fetch RF/ALU harness/docs | `F4DenseSlotQueue`, adjacent frontend/path tests, default live fetch RF/ALU xcheck, live QEMU fixture with mixed BSTART/scalar/BSTOP 8-byte windows, manifest inspection |
| 29 | R103 ROB block-last to BROB lifecycle sideband | `BROB.scala`, `ROBEntryBank.scala`, `DispatchROBAllocator.scala`, `DecodeRenameROBPath.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, module docs | BROB stale-BID reference, full block-BID block-last deallocation sideband, reduced scalar-done and next-cycle retire diagnostics, focused ROB/BROB/top gates, manifest inspection |
| 30 | R104 marker-owned active block lifecycle | `DispatchROBAllocator.scala`, `DecodeRenameROBPath.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, live harness/docs | marker-only BROB allocation, scalar active-BID reuse, marker-driven scalar-done/retire, default RF/ALU xcheck, live QEMU fixture with `BSTART`/scalar/`BSTOP`, manifest inspection |
| 31 | R105 longer live-QEMU reduced scalar body | `build_frontend_fetch_rf_alu_qemu_fixture_elf.sh`, `LinxCoreFrontendFetchRfAluTraceTop.md`, runbook docs | `--long-body` fixture build, live QEMU marker gate with `--capture-rows 9`, seven scalar commits compared, manifest inspection |
| 32 | R106 CoreMark ADDTPC reduced scalar prefix | `ReducedScalarAluExecute.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, execute/top docs | reduced ALU unit, extractor self-test, CoreMark live-QEMU gate with `--capture-rows 4`, first three scalar commits compared, manifest inspection |
| 33 | R107 CoreMark HL call marker and compact SETRET prefix | `FrontendDecodeStage.scala`, `FrontendOperandDecode.scala`, `F4DecodeWindow.scala`, `DecodeRenameROBPath.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, `ReducedScalarAluExecute.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module docs | extractor self-test, focused Chisel gates, CoreMark live-QEMU gate with `--capture-rows 8`, first four scalar commits compared, manifest inspection |
| 34 | R108 CoreMark single-save FENTRY reduced macro row | `FrontendOperandDecode.scala`, `ReducedScalarAluExecute.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, `frontend_fetch_rf_alu_trace_top_tb.cpp`, module docs | extractor self-test, focused Chisel gates, CoreMark live-QEMU gate with `--capture-rows 11`, first six scalar/macro commits compared, manifest inspection |
| 35 | R109 CoreMark U-destination ADDI reduced local-register row | `ReducedScalarIssueQueue.scala`, `ReducedScalarAluExecute.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, `frontend_fetch_rf_alu_trace_top_tb.cpp`, module docs | extractor self-test, focused issue/execute/TU/path/top gates, CoreMark live-QEMU gate with `--capture-rows 12`, seven scalar/macro commits compared, 13-row `OP_HL_LUI` probe, manifest inspection |
| 36 | R110 CoreMark HL.LUI T-destination immediate row | `FrontendOperandDecode.scala`, `ReducedScalarAluExecute.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, frontend/execute/TU/path/top gates, CoreMark live-QEMU gate with `--capture-rows 13`, eight scalar/macro commits compared, 14-row `OP_SLL` probe, manifest inspection |
| 37 | R111 CoreMark SLL local T/U source row plus ROB wrap fix | `ReducedScalarIssueQueue.scala`, `ReducedScalarIssuePick.scala`, `ReducedScalarAluExecute.scala`, `ROBEntryBank.scala`, `DispatchROBAllocator.scala`, `DecodeRenameROBPath.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, live harness/docs | extractor self-test, issue/execute/ROB/path/top gates, CoreMark live-QEMU gate with `--capture-rows 14`, nine scalar/macro commits compared, manifest inspection |
| 38 | R112 CoreMark SLL T-destination and SRL local T/U source rows | `ReducedScalarAluExecute.scala`, `ReducedScalarAluExecuteSpec.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, execute gate, CoreMark live-QEMU gate with `--capture-rows 17`, twelve scalar/macro commits compared, 18-row `OP_OR` probe, manifest inspection |
| 39 | R113 CoreMark OR local T/U source and C.LDI zero-load sideband rows | `ReducedScalarAluExecute.scala`, `ReducedScalarAluExecuteSpec.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, execute gate, CoreMark live-QEMU gate with `--capture-rows 19`, fourteen scalar/macro commits compared, 21-row `OP_C_ADD` probe, manifest inspection |
| 40 | R114 CoreMark C.ADD local T/U source row with QEMU trace-gap synthesis | `ReducedScalarAluExecute.scala`, `ReducedScalarAluExecuteSpec.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, execute gate, CoreMark live-QEMU gate with `--capture-rows 21`, sixteen scalar/macro commits compared, 22-row `OP_SRA` probe, manifest inspection |
| 41 | R115 CoreMark SRA/SLLI local T dependency packet | `FrontendOperandDecode.scala`, `ReducedScalarAluExecute.scala`, `LinxCoreFrontendFetchRfAluTraceTop.scala`, `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, frontend/execute/top gates, CoreMark live-QEMU gate with `--capture-rows 23`, eighteen scalar/macro commits compared, 24-row mixed `C.ADD` probe, manifest inspection |
| 42 | R116 CoreMark mixed C.ADD/local-source ADDI dense packet | `frontend_fetch_rf_alu_qemu_rows.py`, module/top docs | extractor self-test, CoreMark live-QEMU gate with `--capture-rows 26`, twenty-one scalar/macro commits compared, 27-row zero-advance `C.BSTART` artifact probe, manifest inspection |
| 43 | Live QEMU full-compare harness | `tools/chisel/run_chisel_qemu_crosscheck.sh`, live Chisel trace writer | dry-run, manifest inspection, then full compare on a bounded direct-boot smoke |
| 44 | Multi-PE/STID bank expansion | frontend packet production plus T/U bank array | PE/STID-specific rename and retire-source gates |
| 45 | LinxCoreModel ROB maintenance note | `docs/chisel/model-notes/ROBCommit.md` and model-lane notes | documentation check plus model ownership review |

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
   memory-window fixture, source PC advance, dense F4 slot capture/drain,
   issue enqueue, RF writeback, ALU completion, or commit export.
13. `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`
   after changes to live QEMU capture, strict QEMU row selection, sparse ELF
   fetch-memory binding, or the row-derived RF preload contract for the
   reduced fetch RF/ALU gate.
   Use `--allow-block-markers --expected-rows 0 --capture-rows 5` for the
   legal-entry fixture when marker rows are intentionally part of the DUT input
   stream but not part of the comparator stream.
14. `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` for wrapper or
   QEMU-selection changes.
15. `bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh --dry-run` for
   QEMU-row replay wrapper changes, then replay a bounded archived or fresh
   QEMU JSONL with `--qemu-trace` or `--elf`. Use `--replay-rows` only to cap
   the raw search window; `--max-commits` remains the architectural compare
   window.
16. Inspect `<report-dir>/crosscheck_manifest.json` after any non-dry-run
   generated-RTL or QEMU comparison that routes through the common wrapper.
17. Full QEMU-vs-DUT comparison only after the Chisel top emits real
   architectural commit rows.
18. `gfsim -f <elf>` only after the same ELF passed QEMU in the same run packet.

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
- a live-QEMU ELF gate rule where bounded FIFO capture, optional scalar PC
  filtering, `FETCH_ELF`, and `FETCH_QEMU_TRACE` must pass together before the
  reduced fetch RF/ALU top claims live replacement evidence.
- a dense frontend rule where all valid slots from one F4 window are preserved
  before serial reduced decode/ROB drain, and marker-slot checks are
  marker-owned rather than global same-cycle issue silence.
- a reduced marker lifecycle rule where consumed `BSTART` allocates a
  BROB-only active BID, following scalar rows reuse that full BID, and consumed
  `BSTOP` completes the active BID before BROB retire.

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
