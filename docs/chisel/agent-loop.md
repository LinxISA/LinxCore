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

New frontend/backend modules may be implemented after this base, but they do
not become replacement evidence until their rows are visible through monitored
commit or stage-owner trace contracts.

## Cross-Check Ladder

Use this order for each promoted slice:

1. Chisel unit test for the module.
2. Chisel elaboration or emitted SystemVerilog lint for the touched top.
3. Reduced or top xcheck if commit rows or top IO are affected.
4. `trace_schema_adapter.py --self-test` if commit or trace schema changed.
5. `run_chisel_qemu_crosscheck.sh --dry-run` after wrapper or QEMU selection
   changes.
6. Full QEMU-vs-DUT trace compare only after the DUT emits real architectural
   commit rows for the slice.
7. LinxCoreModel `gfsim -f <elf>` only after the same direct-boot ELF passed
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

1. Extend SGPR local-register block-commit from the reduced single live T/U
   bank toward model fanout: selected STID, both SGPR hands, and future PE
   banking, while preserving the R69 ready/accepted maintenance boundary.
2. Enqueue-time ROB reservation: move BROB/ROB allocation before
   `DecodeRenameQueue` enqueue once allocator reservation cursors can advance
   without duplicate identities.
3. Live commit trace schema: define the first full-core `LC-IF-CHISEL-XCHK-*`
   bundle covering commit, trap, memory, recovery, and block sidebands.
4. QEMU full-compare harness: replace reduced synthetic rows with live Chisel
   commit rows once the top can retire a direct-boot smoke.
5. LinxCoreModel ROB maintenance note: audit `SPEROB`, `PROBCommon`,
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
