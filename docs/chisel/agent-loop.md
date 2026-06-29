# LinxCore Chisel Agent Loop

Date: 2026-06-28

## Purpose

This runbook is the handoff surface for many agents developing the LinxCore
Chisel RTL lane. It turns the long development plan into a repeatable loop:
learn the microarchitecture from LinxCoreModel, write the module contract,
implement one Chisel owner, cross-check the evidence, then decide whether the
skills must evolve.

The loop is conservative by design. ROB, commit trace, flush, BROB/BID, and
QEMU cross-check infrastructure are the first proof surface. Frontend, decode,
issue, LSU, engines, and top-level integration can move faster after they are
anchored to a real retirement and trace oracle.

## Current Baseline

Record these SHAs at the start of each agent packet and refresh them if the
submodule moves:

| Repository | Baseline checked for this loop |
|---|---|
| `linx-isa` | `026428e27154242f37f730e07e9829633168cba7` |
| `rtl/LinxCore` | `8f4361a09a19c7f53f8caa0f3b1133afed7f038a` |
| `model/LinxCoreModel` | `68b06b2a8dd07db98bd562aeae7e5a8867c6d450` |
| `emulator/qemu` | `26bbd574fc5e1cbd4b8c859bcb0d007e8e281bf3` |

LinxCoreModel was fetched on 2026-06-28 and `main...origin/main` was already
up to date. The superproject root and `rtl/LinxCore` were fetched; both active
working branches contain `origin/main`, so no merge/pull was required while
unrelated local edits remain in the workspace.
LinxCoreModel was fetched again on 2026-06-29 before R53; local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R54 started from `rtl/LinxCore` commit
`f52ff6e3557eafb5c39ce1f7578ff510ecfa89e1`, with only unrelated
architecture markdown files dirty in the LinxCore worktree.
During R54, `model/LinxCoreModel` was fetched again and local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; the
QEMU adapter dry-run selected
`emulator/qemu/build-linx/qemu-system-linx64` and passed the trace schema
adapter self-test.
R55 started from `rtl/LinxCore` commit
`874480aa5b1deb77417329f2f42c1997720a875c`, with only unrelated architecture
markdown files dirty in the LinxCore worktree.
During R55, `model/LinxCoreModel` was fetched again and local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; the
Chisel QEMU adapter dry-run selected
`emulator/qemu/build-linx/qemu-system-linx64` and passed the trace schema
adapter self-test. The AI workload smoke dry-run produced
`workloads/generated/ai-20260628-190342/ai-bringup/` manifest, report, and
summary paths without executing downstream QEMU/model payloads.
R56 started from `rtl/LinxCore` commit
`47b543835333ae7eb4557236b4336389ec7e93eb`, with only unrelated architecture
markdown files dirty in the LinxCore worktree.
During R56, `model/LinxCoreModel` was fetched again and local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; the
Chisel QEMU adapter dry-run selected
`emulator/qemu/build-linx/qemu-system-linx64` and passed the trace schema
adapter self-test.
R57 started from `rtl/LinxCore` commit
`98753084bb683b224c889044c7f5b6037be1683b`, with only unrelated architecture
markdown files dirty in the LinxCore worktree.
During R57, `model/LinxCoreModel` was fetched again and local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; the
Chisel QEMU adapter dry-run selected
`emulator/qemu/build-linx/qemu-system-linx64` and passed the trace schema
adapter self-test.
R58 started from `rtl/LinxCore` commit
`ac3540247bb25107c51332f5ed971ab0b5bdc8b6`, with only unrelated architecture
markdown files dirty in the LinxCore worktree.
During R58, `model/LinxCoreModel` was fetched again and local `HEAD` still
matched `origin/main` at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; the
Chisel QEMU adapter dry-run selected
`emulator/qemu/build-linx/qemu-system-linx64` and passed the trace schema
adapter self-test.
R61 started from `rtl/LinxCore` commit
`779690712e8fa47dfb15cc0afa56fdf8fcc53af9`, with only unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R61 was
`SPERename::Rename`, `SPERename::InsertToStoreIEX`, `MemReqBus`, and the
store-unit cleanup builders at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R63 started from `rtl/LinxCore` commit
`1cf17776706800a8aa049471193da17af8dca8fa`, with only unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R63 was
`SPEROB::ReleaseRelative`, `SPEROB::CheckRelativeReg`,
`SPEROB::ReleaseFunc`, `SPERename::RepLocalRetired`,
`LocalRegMgr::ReportRetired`, and `RelateCmap` at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R68 started from `rtl/LinxCore` commit
`aa07118c4ee31cf6bdce414f05fc5b3f12005d01`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R68 was
`SPEROB::ReportLocalRegBlockCommit`,
`SPERename::ReportSGPRBlockCommit`, and
`LocalRegMgr::ReportBlockCommit` at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R69 started from `rtl/LinxCore` commit
`a8d36b8b10c5e5a421c19574111d3b3df3d0a4a8`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R69 was
the same `SPEROB::CommitBlock` post-`CleanCMAP` call into
`SPERename::ReportSGPRBlockCommit` and `LocalRegMgr::ReportBlockCommit` at
LinxCoreModel commit `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R70 started from `rtl/LinxCore` commit
`1c950e968f19f1b51707bfcae1950bbf8fbc8167`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R70 was
`SPEROB::CommitBlock`, `SPEROB::ReportLocalRegBlockCommit`,
`SPERename::ReportSGPRBlockCommit`, `LocalRegMgr::ReportBlockCommit`, and
`SGPR_HAND_COUNT`/`SGPRType2Idx` at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R71 started from `rtl/LinxCore` commit
`a6c0ef80ad20b6e473d90b5ff36f5c661b5c3af4`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R71 was
`SPERename::Build`, `SPERename::ReportSGPRBlockCommit`,
`SPEROB::ReportLocalRegBlockCommit`, `LocalRegMgr::ReportBlockCommit`, and
`SGPR_HAND_COUNT` at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R72 started from `rtl/LinxCore` commit
`72446ab7c2cbc43294ac369944d4d485e8331e1b`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The model evidence for R72 was
`SPERename::Build`, `SPERename::Rename`, `SPERename::Flush`,
`SPERename::ReportSGPRBlockCommit`, `SPERename::RepLocalRetired`,
`SPEROB::ReportLocalRegBlockCommit`, `LocalRegMgr::ReportBlockCommit`, and
`SGPR_HAND_COUNT`/`SGPRType2Idx` at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`.
R73 started from `rtl/LinxCore` commit
`4eca50f90dbef959c23ab83e50591d266b436954`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The superproject root was at
`238be4ccc27492e82a65ce3a20b0dcb7a5f834e4` before edits. LinxCoreModel was
fetched again on 2026-06-29 and local `HEAD` still matched `origin/main` at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. The model evidence for R73 was
`SPERename::Rename`, `SPERename::RepLocalRetired`,
`SPERename::ReportSGPRBlockCommit`, `SPEROB::ReleaseFunc`, and
`SPEROB::CheckReg`.
R74 started from `rtl/LinxCore` commit
`d3c4c9385461206990792723c523512037a3b0e7`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The superproject root was at
`88b67ea1e08b34c522b6b49372d10605e9d4c9ff` before edits. LinxCoreModel was
fetched again on 2026-06-29 and local `HEAD` still matched `origin/main` at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. The model evidence for R74 was
`SPERename::ReportSGPRBlockCommit`, `SPERename::RepLocalRetired`,
`SPEROB::ReleaseFunc`, `SPEROB::CheckReg`, and `RelateInfo::peid`.
R75 started from `rtl/LinxCore` commit
`3b98c1ee70d39de7bec85ff1c66c9ed488f278c7`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The superproject root was at
`597e8ed1ed356357f59effdb84c4f51a03f02f27` before edits. LinxCoreModel was
checked on 2026-06-29 and local `HEAD` still matched `origin/main` at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. The model evidence for R75 was
`DCTop::Work`, `SPERename::Build`, `SPERename::Rename`,
`SPEROB::getRetireID`, and the existing retire-side `inst->peID/stid`
sidecar path.
R76 planning started from `rtl/LinxCore` commit
`ecc6e32da7f37f7b043aaab60129a1155c789f47`, with unrelated architecture
markdown files dirty in the LinxCore worktree. The superproject root was at
`23c216bef8b9e8cd40d09865108732e6deb9a8f9` before edits. LinxCoreModel was
fetched again on 2026-06-29 and local `HEAD` still matched `origin/main` at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. The model evidence for R76 is
`BCtrlUnit::Work`, `BlockROB::allocBlock`, `DCTop::Work`,
`SPEROB::allocROB`, and `SPERename::Work`: BROB and PE ROB allocation happen
before `dec_ren_q` enqueue, while later rename mutations remain visible in the
model through the shared `SimInst` pointer stored in the ROB row.
R76 implementation started from `rtl/LinxCore` commit
`0cb614cb46833b1f4e5a40c9f59416a5b07946b8`, with the same unrelated
architecture markdown files dirty in the LinxCore worktree. The model evidence
was rechecked at LinxCoreModel commit
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450` in
`DCTop::Work`, `SPEROB::allocROB`, `SPERename::Rename`,
`BCtrlUnit::Work`, and `BlockROB::allocBlock`. The implementation reserves
BROB/ROB before `DecodeRenameQueue` enqueue and patches `ROBEntryBank` through
`renameUpdate*` when rename accepts the queue head.
R76 landed at `rtl/LinxCore` commit
`11529bf345c407fe1c7614973e61b68be8d99fb4` and was repinned by superproject
commit `ca6faab14a05975b4b52c00c4c5556e301d50930`. R77 planning starts from
that baseline. The superproject, LinxCore, LinxCoreModel, and skills remotes
were fetched on 2026-06-29; `model/LinxCoreModel` still matched `origin/main`
at `68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. Do not merge or checkout over
the current dirty worktrees; record remote deltas and keep unrelated
architecture docs, bring-up docs, and non-`linx-core` skill edits out of R77
commits.
R81 started from `rtl/LinxCore` commit
`4f6489bf61b477adc4a685f8dc3d2b6773981f30` after R80 landed the frontend
trace-top xcheck. The superproject root was
`6514dc90b448eacef3c5e8b907764b36fbd063cc`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`9f96be0c952fb9a047b324b06a480b1c689ba51d`; `skills/linx-skills` was at
`13e314c69fdd6841f94a814df3961e566df98dc8`. The R81 model evidence is
`SPERename::InsertToSIEXQ`, `ALUPipe::Work`, `SimInstInfo::Execute`,
`CalcInstAdd`, `CalcInstAddw`, and the MOVR/MOVI calculators. R81 replaces
the R80 external completion surrogate only for the reduced scalar ALU smoke;
it does not claim a real register-file or issue path.
R82 started from `rtl/LinxCore` commit
`fc03e13a7260228c34a49817f66786fea9839ac5` after R81 landed the ALU-produced
completion-row xcheck. The superproject root was
`68e669d9d016286aa40e6685ea0f1c06ce1d49f1`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`9f96be0c952fb9a047b324b06a480b1c689ba51d`; `skills/linx-skills` was at
`fc6559011846725755d56d089908489553ab1518`. The R82 model evidence is
`GPRRename::Build`, `GPRRename::RenameSrc`, `GPRRename::RenameDst`,
`ReadyState::InitGGPRRtable`, `ReadyState::GetSrcData`, `iex_rf.cpp` OPD_GREG
readback, and `ALUPipe` W2 writeback. R82 replaces per-uop operand fixture
values with reduced physical RF state for dependent scalar ALU rows; it does
not claim a full issue queue, bypass network, or speculative RF recovery path.
R83 started from `rtl/LinxCore` commit
`8747920e9d4b59c07ded648c77f61e73ad2e9fcb` after R82 landed the RF-backed
scalar ALU source gate. The superproject root was
`834952d639179b2a5430bbb55cc8a8855c58b302`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`9f96be0c952fb9a047b324b06a480b1c689ba51d`; `skills/linx-skills` was at
`3b1cc70b9ee90c7f23cb978b9cb740db4e9ca377`. The R83 model evidence is
`IssueState::insert`, `IssueState::Select`, `IssueState::Wakeup`,
`IssueState::ReleaseEntry`, `IDispatch::Work`, and `ALUPipe` W1/W2 writeback.
R83 inserts a reduced scalar issue queue between rename and execute, gates
queue-head issue on RF source readiness, and preserves ROB identity into
execute; it does not claim full age-select, P1/I1/I2 in-flight release,
cancel, replay, or bypass behavior.
R84 started from `rtl/LinxCore` commit
`e97b92dc6e3bb94949c4b7803ca13e5f52532692` after R83 landed the RF-gated
issue queue. The superproject root was
`a7ffc894868cdb39d8bd6a5a80df32665c203952`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`9f96be0c952fb9a047b324b06a480b1c689ba51d`; `skills/linx-skills` was at
`82e1a5fe42b20e56a22c9d203c623c37752ea00f`. The R84 model evidence is
`IssueState::Select`, `IssueState::ReleaseEntry`, `IssueState::flush`,
`IssueState::setCancel`, `ALUPipe::Work`, and `IEX::releaseIQEntry`. R84 keeps
the reduced FIFO head-only selection policy, but selected rows now remain
resident as issued entries until an ALU W2 `(bid, rid, stid)` release removes
them; full age-select, cancel, replay, bypass, and P1/I1/I2 timing remain
future packets.
R84 closeout: `skill-evolve: update linx-core (issue acceptance marks issued;
issue-queue removal waits for later model-derived release identity)`.

## Non-Negotiable Rules

- Every module starts from LinxCoreModel source evidence, not from an invented
  Chisel shape.
- Every promoted module has a Markdown spec with source mapping, purpose,
  interface, state, logic design, timing, flush/recovery, observability, and
  verification sections.
- Each agent owns a narrow file set. Do not edit unrelated architecture docs,
  generated artifacts, or another agent's module files.
- SBT-backed Chisel wrappers run sequentially until the SBT server race is
  closed.
- QEMU path selection is explicit: `QEMU=...`, then
  `emulator/qemu/build-linx/qemu-system-linx64`, then the legacy build path.
- Direct `gfsim -f <elf>` model comparison runs only after the same ELF passed
  QEMU in the same run packet.
- Every closeout records `skill-evolve: update ...` or
  `skill-evolve: no-update ...`.

## Upstream Flow Reference

Use OpenXiangShan as a workflow reference only. Do not import its ISA payloads.

Checked references:

- `https://raw.githubusercontent.com/OpenXiangShan/XiangShan/kunminghu-v3/Makefile`
- `https://raw.githubusercontent.com/OpenXiangShan/XiangShan/kunminghu-v3/build.mill`
- `https://raw.githubusercontent.com/OpenXiangShan/difftest/master/README.md`
- 2026-06-29 refresh: `https://github.com/OpenXiangShan/XiangShan` still
  exposes `Makefile` and `build.mill` on the current `kunminghu-v3` view, and
  its README simulator flow builds a Verilator C++ simulator with `make emu`.
  `https://github.com/OpenXiangShan/difftest` and
  `https://docs.xiangshan.cc/zh-cn/latest/tools/difftest/` remain the upstream
  reference for typed DUT/reference co-simulation, commit-event comparison, and
  hardware-emulation acceleration.

Transferable patterns:

- Make Chisel generation an explicit target, separate from simulation RTL.
- Build a simulator/emulator target from generated RTL instead of treating RTL
  emission as the verification endpoint.
- Finish at a typed simulation top where commit, trap, memory, interrupt, and
  debug events are visible.
- Keep the architectural comparison path typed and versioned.

LinxCore adaptation:

- Use `tools/chisel/build_chisel.sh`, `emit_verilog.sh`,
  `run_chisel_verilator_lint.sh`, `run_chisel_reduced_rob_xcheck.sh`,
  `run_chisel_top_xcheck.sh`, and `run_chisel_qemu_crosscheck.sh`.
- Bind typed Linx commit, recovery, memory, trap, block, and stage events to
  QEMU/LinxCoreModel comparison through `trace_schema_adapter.py`.
- Keep RISC-V-specific DiffTest bundles out of LinxCore. The analogous Linx
  payload is `LC-IF-CHISEL-XCHK-*`.

## Agent Iteration

Use `docs/chisel/development-loop.md` as the concise multi-agent handoff. This
file remains the detailed packet ledger and evidence runbook.

1. **Select packet.** The leader assigns one module family and writes the owned
   file set before any edits.
2. **Refresh state.** Record submodule SHAs, dirty files, and the relevant
   existing docs. Do not pull a dirty repository.
3. **Learn model behavior.** Read the LinxCoreModel C++ owner files and extract
   only testable microarchitectural invariants.
4. **Map existing RTL.** Read the matching pyCircuit owner and existing Chisel
   module, if any.
5. **Write or update Markdown.** The module spec is the design contract. It
   must name source files and define the interface before implementation is
   promoted.
6. **Implement one owner.** Add one Chisel module family in one package. Shared
   bundles belong in `common/` only when multiple owners need them.
7. **Add focused tests.** Unit tests cover reset, valid/ready, ordering,
   flush/recovery, identity width, and any model-derived edge case.
8. **Run gates.** Start with the module target, then run the affected common
   and cross-check gates. Keep captures short.
9. **Review ownership.** Check that no stage state, trace event, or recovery
   behavior moved into generic top-level glue.
10. **Closeout.** Update the module ledger, gate ledger, docs, and the
    skill-evolve decision before assigning downstream work.

## ROB And Cross-Check First Wave

These packets remain the required base before broad module promotion:

| Packet | Owner | Required evidence |
|---|---|---|
| R0 | `ROBID` | `run_chisel_rob_bookkeeping.sh --robid-only` |
| R1 | `CommitTraceRow` and adapter | `run_chisel_tests.sh --only CommitTrace`, `trace_schema_adapter.py --self-test` |
| R2 | `FlushControl` | `run_chisel_tests.sh --only FlushControl` |
| R3 | `BROB/BID` | `run_chisel_tests.sh --only BROB` |
| R4 | `ReducedCommitROB` | `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_reduced_rob_xcheck.sh` |
| R5 | `LinxCoreTop` reduced shell | `run_chisel_tests.sh --only LinxCoreTop`, `run_chisel_top_xcheck.sh` |
| R6 | QEMU adapter | `run_chisel_qemu_crosscheck.sh --dry-run`; full compare when live Chisel commit rows exist |
| R7 | `ROBEntryBank` integrated skeleton | `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only ROBEntryStatus`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R8 | `ROBFlushPrune` selector | `run_chisel_tests.sh --only ROBFlushPrune`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_tests.sh --only ROBEntryBank` |
| R9 | `ROBEntryBank` flush application | `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only ROBFlushPrune`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R10 | `ROBEntryBank` native row IDs | `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only ROBFlushPrune`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R11 | `DispatchROBAllocator` | `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only BROB`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R12 | `FullBidRecoveryBridge` | `run_chisel_tests.sh --only FullBidRecoveryBridge`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only ROBEntryBank` |
| R13 | `RecoveryCleanupControl` | `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FullBidRecoveryBridge`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_tests.sh --only ROBEntryBank` |
| R14 | `STQFlushPrune` | `run_chisel_tests.sh --only STQFlushPrune`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_tests.sh --only ROBEntryBank` |
| R15 | `STQEntryBank` | `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only STQFlushPrune`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl` |
| R16 | `STQCommitQueue` | `run_chisel_tests.sh --only STQCommitQueue`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only STQFlushPrune`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R17 | `STQEntryBank` multi-free target | `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only STQCommitQueue`, `run_chisel_tests.sh --only STQFlushPrune`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R18 | `STQCommitDrain` | `run_chisel_tests.sh --only STQCommitDrain`, `run_chisel_tests.sh --only STQCommitQueue`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R19 | `SCBCommitIngress` | `run_chisel_tests.sh --only SCBCommitIngress`, `run_chisel_tests.sh --only STQCommitDrain`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R20 | `SCBCommitBridge` | `run_chisel_tests.sh --only SCBCommitBridge`, `run_chisel_tests.sh --only SCBCommitIngress`, `run_chisel_tests.sh --only STQCommitDrain`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R21 | `SCBEgressSelect` | `run_chisel_tests.sh --only SCBEgressSelect`, `run_chisel_tests.sh --only SCBCommitBridge`, `run_chisel_tests.sh --only SCBCommitIngress`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R22 | `SCBLookupControl` | `run_chisel_tests.sh --only SCBLookupControl`, `run_chisel_tests.sh --only SCBEgressSelect`, `run_chisel_tests.sh --only SCBCommitBridge`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R23 | `SCBStateUpdate` | `run_chisel_tests.sh --only SCBStateUpdate`, `run_chisel_tests.sh --only SCBLookupControl`, `run_chisel_tests.sh --only SCBEgressSelect`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R24 | `SCBRowBank` | `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_tests.sh --only SCBStateUpdate`, `run_chisel_tests.sh --only SCBLookupControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R25 | `STQSCBCommitPath` | `run_chisel_tests.sh --only STQSCBCommitPath`, `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_tests.sh --only STQCommitDrain`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R26 | `SCBResponseDecode` | `run_chisel_tests.sh --only SCBResponseDecode`, `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_tests.sh --only SCBStateUpdate`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R27 | `MDBConflictDetect` | `run_chisel_tests.sh --only MDBConflictDetect`, `run_chisel_tests.sh --only STQCommitQueue`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R28 | `MDBSSIT` | `run_chisel_tests.sh --only MDBSSIT`, `run_chisel_tests.sh --only MDBConflictDetect`, `run_chisel_tests.sh --only STQCommitQueue`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R29 | `MDBQueueFanout` | `run_chisel_tests.sh --only MDBQueueFanout`, `run_chisel_tests.sh --only MDBSSIT`, `run_chisel_tests.sh --only MDBConflictDetect`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R30 | `LoadStoreForwarding` | `run_chisel_tests.sh --only LoadStoreForwarding`, `run_chisel_tests.sh --only MDBQueueFanout`, `run_chisel_tests.sh --only MDBConflictDetect`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R31 | `LoadForwardPipeline` | `run_chisel_tests.sh --only LoadForwardPipeline`, `run_chisel_tests.sh --only LoadStoreForwarding`, `run_chisel_tests.sh --only MDBQueueFanout`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R32 | `LoadInflightQueue` | `run_chisel_tests.sh --only LoadInflightQueue`, `run_chisel_tests.sh --only LoadForwardPipeline`, `run_chisel_tests.sh --only LoadStoreForwarding`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R33 | `LoadReplayWakeup` | `run_chisel_tests.sh --only LoadReplayWakeup`, `run_chisel_tests.sh --only LoadInflightQueue`, `run_chisel_tests.sh --only LoadForwardPipeline`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R34 | `LoadRefillWakeup` | `run_chisel_tests.sh --only LoadRefillWakeup`, `run_chisel_tests.sh --only LoadInflightQueue`, `run_chisel_tests.sh --only LoadReplayWakeup`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R35 | `SCBResponseBuffer` | `run_chisel_tests.sh --only SCBResponseBuffer`, `run_chisel_tests.sh --only SCBResponseDecode`, `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R36 | `SCBResponseRetrySelect` | `run_chisel_tests.sh --only SCBResponseRetrySelect`, `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_tests.sh --only SCBStateUpdate`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R37 | `SCBResponseRetryQueue` | `run_chisel_tests.sh --only SCBResponseRetryQueue`, `run_chisel_tests.sh --only SCBResponseRetrySelect`, `run_chisel_tests.sh --only SCBRowBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R38 | `GPRRenameCheckpoint` | `run_chisel_tests.sh --only GPRRenameCheckpoint`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FullBidRecoveryBridge`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R39 | `FrontendDecodeStage` | `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendDecodeIngress`, `run_chisel_tests.sh --only InterfaceBundles` |
| R40 | `FrontendOperandDecode` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendDecodeIngress`, `run_chisel_tests.sh --only InterfaceBundles`, `build_chisel.sh`, `run_chisel_top_xcheck.sh`, `run_chisel_verilator_lint.sh`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R41 | `ScalarDecodeRenameBridge` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only GPRRenameCheckpoint`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R42 | `DecodeRenameROBPath` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only GPRRenameCheckpoint`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R43 | `FrontendRegAliasClassify` / `FrontendOperandDecode` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R44 | `DecodeRenameQueue` / `DecodeRenameROBPath` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R45 | `DecodeLoadStoreIdAssign` / `DecodeRenameROBPath` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only DecodeLoadStoreIdAssign`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R46 | `StoreSplitPayload` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only DecodeLoadStoreIdAssign`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R47 | Generated store metadata / reduced store dispatch handoff | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DecodeLoadStoreIdAssign`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R48 | `StoreDispatchQueues` / queue-backed store dispatch handoff | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only StoreDispatchQueues`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only DecodeLoadStoreIdAssign`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R49 | `StoreDispatchToSTQ` request bridge | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only StoreDispatchToSTQ`, `run_chisel_tests.sh --only StoreDispatchQueues`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R50 | `STQInsertProbe` / `StoreDispatchSTQPath` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only STQInsertProbe`, `run_chisel_tests.sh --only StoreDispatchSTQPath`, `run_chisel_tests.sh --only StoreDispatchToSTQ`, `run_chisel_tests.sh --only StoreDispatchQueues`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R51 | `TULinkRename` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R52 | `TULinkRename` cleanup hooks | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R53 | `TULinkFlushSequencePublisher` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkFlushSequencePublisher`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R54 | `TULinkRecoveryCleanupPath` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSequencePublisher`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob` |
| R55 | `TULinkFlushSourceSelector` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSequencePublisher`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R56 | `ROBEntryBank` T/U source sidecars | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSequencePublisher`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R57 | `TULinkRecoveryCleanupPath` source-selected composition | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only TULinkFlushSequencePublisher`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only RecoveryCleanupControl`, `run_chisel_tests.sh --only FlushControl`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R58 | `STQEntryBank` LSU T/U source sidecars | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only STQFlushPrune`, `run_chisel_tests.sh --only StoreDispatchToSTQ`, `run_chisel_tests.sh --only StoreDispatchSTQPath`, `run_chisel_tests.sh --only STQInsertProbe`, `run_chisel_tests.sh --only STQCommitDrain`, `run_chisel_tests.sh --only STQSCBCommitPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R59 | `DecodeRenameROBPath` reduced T/U cleanup source composition | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only StoreDispatchSTQPath`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R60 | `DecodeRenameROBPath` integrated STQ-bank LSU source | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only StoreDispatchSTQPath`, `run_chisel_tests.sh --only StoreDispatchToSTQ`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R61 | Store dispatch T/U sidecar carry into STQ requests | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only StoreDispatchQueues`, `run_chisel_tests.sh --only StoreDispatchToSTQ`, `run_chisel_tests.sh --only StoreDispatchSTQPath`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only STQEntryBank`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkFlushSourceSelector`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R62 | `ScalarTURenameBridge` live scalar plus T/U rename composition | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R63 | `TULinkRelationCmap` and ROB deallocation T/U retire-source vector | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R64 | `TULinkRetireCommandPath` live ROB deallocation T/U retire-command wiring | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R65 | Relation-cmap and retire-source cleanup pruning | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R66 | ROB block-last deallocation boundary | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R67 | Scalar `CleanCMAP` scheduling after block-last retire commands drain | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R68 | Scalar local block-commit event after `CleanCMAP` | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R69 | Consume scalar local block-commit event in live T/U rename owner | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R70 | Carry selected STID through local block-commit and reject non-local reduced-bank events | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R71 | Selected-STID local block-commit fanout boundary | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkLocalBlockCommitFanout`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R72 | Explicit SGPR local bank-array hierarchy | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only TULinkLocalBankArray`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkLocalBlockCommitFanout`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R73 | Active SGPR bank selector plumbing | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkLocalBankArray`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R74 | Retired-row PE/STID sidecars for T/U retire commands | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only TULinkRelationCmap`, `run_chisel_tests.sh --only TULinkRetireCommandPath`, `run_chisel_tests.sh --only TULinkLocalBankArray`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkRecoveryCleanupPath`, `run_chisel_tests.sh --only TULinkRename`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R75 | Decoded/renamed scalar PE owner sidecar carry | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only InterfaceBundles`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendInstructionBuffer`, `run_chisel_tests.sh --only FrontendDecodeIngress`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DecodeLoadStoreIdAssign`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_tests.sh --only ScalarDecodeRenameBridge`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only StoreSplitPayload`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only TULinkLocalBankArray`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R76 | Enqueue-time ROB/BROB reservation with post-rename sidecar update | `sbt "testOnly linxcore.rob.ROBEntryBankSpec linxcore.backend.DispatchROBAllocatorSpec linxcore.backend.DecodeRenameROBPathSpec"`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R77 | R76 gate broadening and top trace/xcheck prep | `sbt --client --error 'Test / compile'`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only DecodeRenameQueue`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_reduced_rob_xcheck.sh`, `run_chisel_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R78 | Top trace replay xcheck harness | `run_chisel_trace_replay_xcheck.sh`, `run_chisel_top_xcheck.sh`, `run_chisel_reduced_rob_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R79 | Frontend-window trace top boundary | `run_chisel_tests.sh --only LinxCoreFrontendTraceTop`, `run_chisel_frontend_trace_top_lint.sh`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R80 | Frontend-window trace top Verilator xcheck | `run_chisel_frontend_trace_top_xcheck.sh`, `run_chisel_frontend_trace_top_lint.sh`, `run_chisel_tests.sh --only LinxCoreFrontendTraceTop`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R81 | Reduced scalar ALU completion row xcheck | `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `run_chisel_frontend_trace_top_xcheck.sh`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ROBEntryBank`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R82 | Reduced scalar RF-backed ALU source path | `run_chisel_tests.sh --only ReducedScalarRegisterFile`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run` |
| R83 | Reduced scalar issue-queue handoff | `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_tests.sh --only ReducedScalarRegisterFile`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `run_chisel_frontend_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R84 | Model-style issued-entry release | `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_tests.sh --only LinxCoreFrontendAluTraceTop`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |

New frontend/backend modules may be implemented after this base, but they do
not become replacement evidence until their rows are visible through monitored
commit or stage-owner trace contracts.

## Cross-Check Ladder

Use this order for each promoted slice:

1. Chisel unit test for the module.
2. Chisel elaboration or emitted SystemVerilog lint for the touched top.
3. Reduced or top xcheck if commit rows or top IO are affected.
4. `trace_schema_adapter.py --self-test` if commit or trace schema changed.
5. `run_chisel_trace_replay_xcheck.sh` after top-level commit export, adapter,
   or cross-check harness changes.
6. `run_chisel_frontend_trace_top_lint.sh` after changes to the
   frontend-window-to-commit top boundary.
7. `run_chisel_frontend_trace_top_xcheck.sh` after changes to the frontend
   trace-top driver, temporary completion surrogate, or top commit export.
8. `run_chisel_frontend_alu_trace_top_xcheck.sh` after changes to scalar ALU
   execute completion, completion-row payload wiring, or the frontend ALU
   trace-top driver.
9. `run_chisel_frontend_rf_alu_trace_top_xcheck.sh` after changes to scalar RF
   operand sourcing, issue-queue source readiness, issue-queue release,
   execute physical-destination writeback metadata, or the shared frontend ALU
   trace-top driver.
10. `run_chisel_qemu_crosscheck.sh --dry-run` after wrapper or QEMU selection
   changes.
11. Full QEMU-vs-DUT trace compare only after the DUT emits real architectural
   commit rows for the slice.
12. LinxCoreModel `gfsim -f <elf>` only after the same direct-boot ELF passed
   QEMU in the same run packet.

## LinxCoreModel Maintenance Loop

Use this loop when Chisel work exposes a model ambiguity or divergence:

1. Record model SHA, superproject gitlink, QEMU binary, ELF, and command.
2. Confirm the ELF passed QEMU first.
3. Capture the first wrong architectural event: PC, commit index, BID, RID,
   memory side effect, trap, or redirect.
4. Classify ownership before editing: `chisel`, `qemu`, `model`, `compiler`,
   `benchmark`, `adapter`, or `unknown`.
5. If the model is wrong, make a separate model-lane patch and verify it with
   model gates before repinning the superproject.
6. Update the relevant `docs/chisel/model-notes/*.md` file and close with a
   skill-evolve decision.

Do not hide model failures with artificial stop-cycle limits. Timeout evidence
belongs in the model lane until `gfsim -f <elf>` exits naturally or a real
architectural stop condition is implemented.

## Skill Evolve Loop

At closeout, choose exactly one:

```text
skill-evolve: update <skill-list> (<material reusable finding>)
skill-evolve: no-update (<why this is module-local or already documented>)
```

Update skills only for:

- a new cross-module LinxCore invariant,
- a new required gate or reproducibility command,
- a recurring QEMU/model/chisel first-divergence workflow,
- a superproject gitlink or lane-ordering rule needed by later agents.
- a ready/valid rule that prevents one owner from feeding another owner's
  accepted-output readiness back into its own input acceptance.
- a sidecar preservation rule where row-owned model metadata must be copied
  through split/queue/bridge owners before the live producer is composed.
- a retire-source preservation rule where ROB deallocation metadata must remain
  a row vector until a relation-cmap serializer can consume every row without
  dropping width-wide events.
- a post-clean event-ordering rule where `ReportLocalRegBlockCommit` must stay
  after scalar `CleanCMAP` and must not be folded into the T/U relation owner.
- a selected-STID local block-commit rule where a reduced local-register owner
  must not consume an event for a different STID; fanout must select or
  instantiate the matching local banks explicitly.
- an all-selected-PE local block-commit fanout rule where downstream bank valid
  must pulse only when every selected PE bank for the event STID is ready.
- an active-bank selector rule where decoded-row STID can drive the SGPR
  bank-array selector for rename/external reduced maintenance without becoming
  the implicit selector for retired-row release commands.
- a retired-row bank sidecar rule where `TULinkRetireSource`,
  `TULinkRelationCmap`, and `TULinkRetireCommand` must carry PE/STID from the
  deallocated ROB row or relation entry so T/U mark/release commands route
  independently of the active rename-head selector.
- an issue-boundary readiness rule where RF physical source readiness must gate
  issue from a queued row, while reduced rename acceptance remains driven by
  issue-queue capacity.

Run skill evolution as a trailing maintenance lane after the module docs and
evidence are updated. The module packet owns local Markdown first; the
canonical `skills/linx-skills` submodule changes only when the new finding must
guide future agents outside the touched module docs. If a skill update is
needed, bundle all material findings from the packet into one skill commit,
validate the touched skills, run the scope guard, install the canonical copy,
and repin the superproject gitlink after the skill submodule commit.

Do not update skills for:

- formatting,
- a one-off workaround,
- local module prose,
- test vectors already documented in that module's Markdown page.

When updating skills:

```bash
python3 /Users/zhoubot/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  /Users/zhoubot/linx-isa/skills/linx-skills/linx-core
python3 /Users/zhoubot/linx-isa/skills/linx-skills/scripts/check_skill_change_scope.py \
  --repo-root /Users/zhoubot/linx-isa/skills/linx-skills --base origin/main
```

Mirror the installed skill only after the canonical `skills/linx-skills`
version is correct.

## Agent Packet Template

Use this shape when launching a future agent:

```markdown
### Packet: <module or harness>

Owned files:
- `<path>`

Read first:
- `<LinxCoreModel source>`
- `<pyCircuit or existing Chisel source>`
- `<existing Markdown spec>`

Contract to preserve:
- `<invariant>`

Implementation target:
- `<Chisel module or doc update>`

Gates:
- `<command>`

Closeout:
- update module docs and ledgers
- report `skill-evolve: update|no-update`
```

## Suggested Next Packets

1. Full issue scheduler timing: replace the reduced FIFO head-only selector
   with model-aligned wakeup, age-select, P1/I1/I2 in-flight timing, cancel,
   replay, and bypass behavior.
2. Live commit trace schema: extend the top-owned `LC-IF-CHISEL-XCHK-*`
   event stream from commit-only rows toward trap, memory, recovery, and block
   sidebands.
3. QEMU full-compare harness: feed a bounded direct-boot or CoreMark window
   from QEMU into the same comparator path, then make the Chisel DUT stream
   live once frontend/decode/execute/LSU can retire it.
4. Per-bank cleanup source vectors: publish ROB/STQ cleanup candidates with
   enough PE/STID structure for multi-bank cleanup selection in the SGPR array.
5. Multi-PE packet production and bank instantiation: teach the upstream
   frontend/top owner to set nonzero `FrontendDecodePacket.peId` and instantiate
   matching `ScalarTURenameBridge`/`TULinkLocalBankArray` PE banks.
6. LinxCoreModel ROB maintenance note: audit `SPEROB`, `PROBCommon`,
   `VectorLiteROB`, and `GROB` for shared commit-ordering invariants and model
   implementation-only details.

## Done Criteria

A packet is complete only when:

- the module Markdown page is current,
- the Chisel source and tests are in separate owner files,
- model evidence is cited by file and symbol,
- required gates passed and outputs were read,
- no unrelated dirty files were staged,
- the module and gate ledgers are updated,
- the skill-evolve decision is recorded.
