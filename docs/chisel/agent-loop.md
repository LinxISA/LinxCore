# LinxCore Chisel Agent Loop

Date: 2026-06-29

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
R85 started from `rtl/LinxCore` commit
`b99c0f6a5949fb605d511cc2dc768fd59e2b5903` after R84 landed model-style
issued-entry release. The superproject root was
`ecebd8a4fe26761eb6d6cf79ae43cf48cf52be1d`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`9f96be0c952fb9a047b324b06a480b1c689ba51d`; `skills/linx-skills` was at
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. The R85 model evidence is
`IssueState::Wakeup`, `IssueState::Select`, `ReadyState::checkReady`,
`ReadyState::CheckReadySrc`, and `ALUPipe::WakeupDstTags`. R85 keeps the
reduced FIFO head-only selection policy, but per-entry source readiness is now
registered inside the issue queue and updated from the RF ready mask instead
of feeding same-cycle RF `readReady` directly into issue selection.
R85 closeout: `skill-evolve: no-update (installed linx-core skill already
documents wakeup at cycle N must not affect pick until N+1)`.
R86 started from `rtl/LinxCore` commit
`f31fd42484c55ac9a1ed957e4c1d64ae1a456bc9` after R85 landed registered
source readiness. The superproject root was
`2df227e808b30c039b03be3eef9db077630243c6`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. The R86 model evidence is
`IssueState::Select` scanning resident entries, ignoring issued entries,
tracking `oldeIdx`, marking only the selected entry issued, and leaving release
to the later `ReleaseEntry` path. R86 keeps the reduced one-issue-port scalar
top, but RF reads and execute issue now use the oldest resident ready
non-issued row rather than always using slot 0.
R86 closeout: `skill-evolve: no-update (installed linx-core skill already
documents oldest-ready ordering across in-flight issued entries)`.
R87 started from `rtl/LinxCore` commit
`9f6379a2a3baa5ed654142b45e8fbda23334b752` after R86 landed oldest-ready
reduced issue selection. The superproject root was
`fe6d42a5612c0fd42935c6b995513275f10542f2`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. Remote metadata was fetched
without merging on 2026-06-29: root `origin/main`
`e6708166cd6bde8d1d1cbb8b13a64814859ac41b`, LinxCore `origin/main`
`d9157fd1e79db2a9eb9294cdce6bb361d52a77d2`, LinxCoreModel `origin/main`
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`, and skills `origin/main`
`c42152d9883c3743ecc28744fe9a616c16dda47b`. The R87 model evidence is
`IssueState::Select`, `iex_pipe.h` P1/I1/I2 stage definitions,
`ALUPipe::runI1/runI2`, and `IEX::releaseIQEntry`. R87 adds
`ReducedScalarIssuePick` as a reduced issue-owner child for selected-row pick,
RF read request, RF read-confirm gating, issue fire, and block diagnostics,
leaving residency/source-ready/release/compaction in `ReducedScalarIssueQueue`.
R87 closeout: `skill-evolve: no-update (installed linx-core skill already
documents the issue-owner P1/I1/I2 pick/read/confirm boundary)`.
R88 started from `rtl/LinxCore` commit
`37b039f2e96bfdea34203f419fdfa0755fc9f1b9` after R87 landed the reduced
issue-pick owner. The superproject root was
`876701b68eeb9ca16ebf45bba4fe28023c024df7`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. Remote metadata was fetched
without merging on 2026-06-29: root `origin/main`
`e6708166cd6bde8d1d1cbb8b13a64814859ac41b`, LinxCore `origin/main`
`d9157fd1e79db2a9eb9294cdce6bb361d52a77d2`, and LinxCoreModel `origin/main`
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`. The R88 model evidence is
`IssueState::Select` setting selected entries issued, `ALUPipe::move`
advancing `p1_inst -> i1_inst -> i2_inst`, `ALUPipe::runI1/runI2`, and the
later scalar ALU IQ release path. R88 makes `ReducedScalarIssuePick` stateful:
P1 `pickFire` locks a queue row, I1 drives RF reads and can `cancelFire` the
lock, and I2 drives execute valid while queue deallocation remains tied to the
ALU release identity.
R89 started from `rtl/LinxCore` commit
`03a6944dd8bc3a7f43dda37e69a8c3dfbfb9b374` after R88 landed reduced P1/I1/I2
issue timing. The superproject root was
`3bf69fae1a0cde8902bd06bef8dc693f56155dd2`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`14550071b38617fbdb2302489bc180b2b8f9cbf8`. R89 is cross-check
infrastructure: `run_chisel_qemu_crosscheck.sh` now emits
`crosscheck_manifest.json` after non-dry-run comparisons so future agents can
archive one evidence bundle naming the selected QEMU binary, raw traces,
normalized traces, reports, row counts, first mismatch, CBSTOP summary, and
git context.
R90 started from `rtl/LinxCore` commit
`3d23b7c561aca535d500c1c2ce00d7e86013cc59` after R89 landed manifest
evidence. The superproject root was
`774c4fbd6935d098e778e45bd717b57af72d7db8`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`848bd17bb9ced589eaadf648dc0f5293ab0a3dc4`. R90 is a bridge packet:
archived or freshly collected QEMU rows are replayed through the current
Chisel commit-surface harness in a separate build directory and compared with
manifest evidence, while live full-DUT comparison remains a later top-level
trace-writer packet.
The bridge keeps raw replay/normalization rows separate from architectural
compare rows: QEMU metadata rows can be skipped by the comparator, so R90
normalizes a wider raw window and slices the replay input to the smallest prefix
that contains the requested non-metadata commits.
R91 started from `rtl/LinxCore` commit
`1c40d4eeae136370003d1e257669972e2f90e433` after R90 landed archived QEMU row
replay evidence. The superproject root was
`f8a6c3e7c703c770b426ab9fbc702469e4ec5993`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f17c551aaef51a784a99d5cccc69cf65ff2a7b32`; `skills/linx-skills` was at
`0580ef69ed3e77f179f91d139062f3223cd01120`. R91 is a live-ELF replay prefix
hardening packet: early QEMU failure no longer leaves the FIFO reader hanging,
and direct-boot CoreMark-style images mapped at `0x40000000` are replayed with
explicit QEMU RAM, e.g. `-m 1280M`. R91 evidence captured 128 raw QEMU rows
from `tests/benchmarks/build/coremark_real.elf`, sliced 5 replay rows with 4
architectural commits, and passed the Chisel replay cross-check with zero
mismatches.
R92 started from `rtl/LinxCore` commit
`9cd2e09af329ff08d0370c9bb334ae72298b1281` after R91 landed bounded
CoreMark ELF prefix replay evidence. The superproject root was
`2d51e6a1625e213908131096a8c0360ba102f748`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f03477a0f56aeffb82a304e3a553b31cc2d29879`; `skills/linx-skills` was at
`67bcb752359e67b1de87920349f44f9d21eb65d7`. R92 is the shared
commit-JSONL writer packet. It factors QEMU-shaped reference rows and DUT
sideband rows into `tools/chisel/commit_trace_jsonl.h` so reduced ROB, top
replay, frontend trace, ALU, and RF/ALU Verilator harnesses cannot drift in
field spelling or default values while future live Chisel trace writers are
added.
R92 closeout: `skill-evolve: update linx-core (generated-RTL harnesses must
use the shared commit JSONL writer before live Chisel trace writers are added)`.
R93 started from `rtl/LinxCore` commit
`4ffa096d7180c682a625910ddbf8bd973c75f7b6` after R92 landed the shared
commit-JSONL writer. The superproject root was
`1d15a2787fd39cdcfc61249faf261ef1a80049f5`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f03477a0f56aeffb82a304e3a553b31cc2d29879`; `skills/linx-skills` was at
`d783c308c8ed3fda088901f011c7c6457c30105f`. R93 is the first Chisel
frontend source packet: `FrontendFetchPacketSource` owns a reduced
one-outstanding PC request, instruction-window response, packet residency, and
decoded-byte PC advance boundary before `F4DecodeWindow`. It is not live
QEMU/CoreMark evidence yet; it removes the testbench-owned packet fixture that
a later live top must replace before full compare can be valid.
R93 closeout: skill-evolve: no-update (the reusable IFU owner contract is
captured in the new module page; no new cross-module gate or skill policy).
R94 started from `rtl/LinxCore` commit
`b9366af9986e85b6f4a1c28149aebd1b9285c3cc` after R93 landed the live source
packet producer. The superproject root was
`a47cc4dc339b079abd0e86fdfa1777896aa7ba88`; LinxCoreModel remained at
`68b06b2a8dd07db98bd562aeae7e5a8867c6d450`; QEMU was at
`f03477a0f56aeffb82a304e3a553b31cc2d29879`; `skills/linx-skills` was at
`d783c308c8ed3fda088901f011c7c6457c30105f` before local skill edits. R94 is
the first live frontend fetch trace-top packet: `LinxCoreFrontendFetchTraceTop`
connects `FrontendFetchPacketSource` to `F4DecodeWindow` and
`DecodeRenameROBPath`, uses F4 decoded byte count to advance the source PC,
and dumps generated-RTL commit rows through the shared JSONL writer. The
bounded memory-window fixture intentionally exposes one valid slot per response
while the reduced backend can retire only one selected row per packet. Its
manifest under `generated/chisel-frontend-fetch-trace-top-xcheck/report`
records `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
R94 closeout: skill-evolve: update linx-core (new generated-RTL live fetch
trace-top xcheck is a reusable promotion gate).

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
- Non-dry-run QEMU/DUT comparisons that route through
  `run_chisel_qemu_crosscheck.sh` must leave a `crosscheck_manifest.json`
  evidence bundle in the report directory.
- Direct-boot ET_DYN benchmark ELFs mapped near `0x40000000`, including the
  current CoreMark ELF, need explicit Linx QEMU memory such as `-m 1280M`; the
  default 128 MiB `virt` machine is expected to fail before trace production.
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
| R85 | Registered issue source readiness | `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R86 | Oldest-ready reduced issue selection | `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R87 | Reduced issue-pick read-confirm owner | `run_chisel_tests.sh --only ReducedScalarIssuePick`, `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R88 | Reduced P1/I1/I2 issue timing | `run_chisel_tests.sh --only ReducedScalarIssuePick`, `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `build_chisel.sh`, `run_chisel_verilator_lint.sh` |
| R89 | QEMU cross-check manifest evidence | `run_chisel_qemu_crosscheck.sh --dry-run`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, inspect each `crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `git diff --check` |
| R90 | QEMU trace replay bridge | `run_chisel_qemu_trace_replay_xcheck.sh --dry-run`, replay archived/fresh QEMU JSONL with `--qemu-trace` or `--elf`, verify metadata-aware raw prefix output, inspect `generated/chisel-qemu-trace-replay-xcheck/report/crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `git diff --check` |
| R91 | Bounded CoreMark ELF replay prefix | `run_chisel_qemu_trace_replay_xcheck.sh --dry-run`, default-memory CoreMark `--elf` fail-fast check, CoreMark `--elf` replay with explicit `-m 1280M`, inspect `generated/r91-coremark-elf-prefix/report/crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `git diff --check` |
| R92 | Shared commit JSONL writer | `run_chisel_reduced_rob_xcheck.sh`, `run_chisel_top_xcheck.sh`, `run_chisel_trace_replay_xcheck.sh`, `run_chisel_frontend_trace_top_xcheck.sh`, `run_chisel_frontend_alu_trace_top_xcheck.sh`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R93 | Frontend fetch packet source | `run_chisel_tests.sh --only FrontendFetchPacketSource`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendDecodeIngress`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R94 | Live frontend fetch trace top | `run_chisel_tests.sh --only LinxCoreFrontendFetchTraceTop`, `run_chisel_tests.sh --only FrontendFetchPacketSource`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_frontend_fetch_trace_top_xcheck.sh`, `run_chisel_frontend_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, manifest inspection, `git diff --check` |
| R95 | Live frontend fetch RF-backed ALU trace top | `run_chisel_tests.sh --only FrontendFetchPacketSource`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `run_chisel_frontend_fetch_trace_top_xcheck.sh`, `run_chisel_frontend_rf_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, manifest inspection, `git diff --check` |
| R96 | File-backed live fetch RF/ALU memory feeder | `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, inspect `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.fetch.bin` and `crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R97 | Sparse ELF live fetch RF/ALU memory feeder | `frontend_fetch_elf_memory.py --self-test`, `FETCH_ELF=<synthetic.elf> run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, inspect `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/elf.fetch.mem` and `crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R98 | External expected-row source for live fetch RF/ALU | `frontend_fetch_rf_alu_fixture_rows.py --self-test`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `FETCH_EXPECTED_ROWS=<rows.jsonl> run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, inspect `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/fixture.expected.jsonl` and `crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R99 | Strict QEMU trace expected-row extraction for live fetch RF/ALU | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `FETCH_QEMU_TRACE=<qemu.jsonl> run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, default live fetch RF/ALU xcheck regression, inspect `generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/qemu.expected.jsonl` and `crosscheck_manifest.json`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, `git diff --check` |
| R100 | Live QEMU ELF capture for reduced fetch RF/ALU | `build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r100-live-qemu-fixture`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 3 --capture-rows 3 --pc-lo 0x10002 --pc-hi 0x1000b --max-seconds 5`, default live fetch RF/ALU xcheck regression, inspect `generated/chisel-frontend-fetch-rf-alu-qemu-elf-xcheck/report/crosscheck_manifest.json`, trace/adapter self-tests, QEMU dry-run, `git diff --check` |
| R101 | Reduced BSTART/BSTOP marker skip for live fetch RF/ALU | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r101-live-qemu-fixture`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`, inspect preview skip rows and `crosscheck_manifest.json`, QEMU dry-run, `git diff --check` |
| R102 | Reduced dense F4 slot queue for live fetch RF/ALU | `run_chisel_tests.sh --only F4DenseSlotQueue`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendFetchPacketSource`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r102-default-fetch-rf-alu-trace-top-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r102-live-qemu-fixture`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r102-dense-qemu-elf-xcheck --elf generated/r102-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`, inspect dense preview rows and both manifests, QEMU dry-run, `git diff --check` |
| R103 | ROB block-last to BROB scalar-done/retire sideband | `run_chisel_tests.sh --only BROB`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `trace_schema_adapter.py --self-test`, `run_chisel_qemu_crosscheck.sh --dry-run`, inspect generated RF/ALU manifest, `git diff --check` |
| R104 | Marker-owned active BID lifecycle | `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_tests.sh --only BROB`, `run_chisel_rob_bookkeeping.sh --reduced-rob`, `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, `build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r104-live-qemu-fixture`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r104-marker-lifecycle-qemu-elf-xcheck --elf generated/r104-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 5 --allow-block-markers --max-seconds 5`, trace self-test, QEMU dry-run, manifest inspection, `git diff --check` |
| R105 | Longer live-QEMU reduced scalar body | `build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r105-long-body-fixture --long-body`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r105-long-body-qemu-elf-xcheck --elf generated/r105-long-body-fixture/frontend_fetch_rf_alu_qemu_fixture.elf --expected-rows 0 --capture-rows 9 --allow-block-markers --max-seconds 5`, default fixture build regression, manifest inspection, `git diff --check` |
| R106 | CoreMark ADDTPC reduced scalar prefix | `run_chisel_tests.sh --only ReducedScalarAluExecute`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r106-coremark-addtpc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 4 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, `git diff --check` |
| R107 | CoreMark HL call marker, compact SETRET, and C.BSTOP redirect prefix | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only F4DecodeWindow`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_tests.sh --only FrontendDecodeIngress`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r107-coremark-hl-call-setret-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 8 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, `git diff --check` |
| R108 | CoreMark single-save FENTRY reduced macro row | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r108-coremark-fentry-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 11 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 12-row unsupported-row probe, `git diff --check` |
| R109 | CoreMark U-destination ADDI local-register row | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r109-coremark-u-dst-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 12 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 13-row `OP_HL_LUI` unsupported-row probe, `git diff --check` |
| R110 | CoreMark HL.LUI T-destination immediate row | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only ScalarTURenameBridge`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r110-coremark-hl-lui-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 13 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 14-row `OP_SLL` unsupported-row probe, `git diff --check` |
| R111 | CoreMark SLL local T/U source row plus ROB wrap fix | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarIssueQueue`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only ROBEntryBank`, `run_chisel_tests.sh --only DispatchROBAllocator`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, `git diff --check` |
| R112 | CoreMark SLL T-destination plus SRL local T/U source rows | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r112-coremark-sll-srl-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 17 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 18-row `OP_OR` unsupported-row probe, `git diff --check` |
| R113 | CoreMark OR local T/U source and C.LDI zero-load sideband rows | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r113-coremark-or-c-ldi-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 19 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 21-row `OP_C_ADD` unsupported-row probe, `git diff --check` |
| R114 | CoreMark C.ADD local T/U source row with QEMU trace-gap synthesis | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r114-coremark-c-add-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 21 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 22-row `OP_SRA` unsupported-row probe, `git diff --check` |
| R115 | CoreMark SRA/SLLI ordered local T dependency row | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r115-coremark-sra-slli-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 23 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 24-row mixed `C.ADD` frontier probe, `git diff --check` |
| R116 | CoreMark mixed C.ADD plus local-source ADDI dense packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r116-coremark-c-add-mixed-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 26 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 27-row zero-advance `C.BSTART` artifact probe, `git diff --check` |
| R117 | CoreMark C.MOVR to T, scaled local C.LDI, C.SETC_NE no-writeback, and retire feedback | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r117-coremark-c-movr-c-ldi-setc-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 34 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, next frontier `pc=0x4000555c`/`insn=0x13808315`, `git diff --check` |
| R118 | CoreMark SDI local-base store sideband packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r118-coremark-sdi-42-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 42 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 41/43-row dense-cut probes, `git diff --check` |
| R119 | CoreMark conditional BSTART loop edge | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 48-row dense-cut probe, `git diff --check` |
| R120 | CoreMark repeated loop body through scalar-created blocks and reduced store bypass | `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r120-coremark-scalar-block-store-bypass-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, 68-row diagnostic gate, 256-row unsupported-opcode probe, `git diff --check` |
| R121 | CoreMark LDI/C.SETC_EQ plus bounded-capture tail prefix | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r121-coremark-ldi-setceq-tail-256-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 256 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, manifest inspection, `git diff --check` |
| R122 | CoreMark read-only sparse-ELF load lookup, SETC_LTU, and local-alias row reducer | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r122-coremark-prefix-before-redirect-marker-470-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 470 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 315 normalized rows compared, 486-row redirect-marker allocation mismatch probe, 512-row `OP_SD` frontier probe, `git diff --check` |
| R123 | CoreMark direct-active marker-boundary redirect and two-slot commit collection | `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r123-coremark-redirect-marker-486-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 486 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 323 normalized rows compared, next `OP_SD` frontier, `git diff --check` |
| R124 | CoreMark OP_SD indexed store and committed sparse-memory mutation | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r124-coremark-op-sd-544-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 544 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 357 normalized rows compared, `git diff --check` |
| R125 | CoreMark SUBI, C.AND, local/scalar ADD, and C.SDI packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-subi-cand-add-csdi-768-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 768 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r125-coremark-1024-frontier-probe-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 665 normalized rows compared in the promoted 1024-row probe, `git diff --check` |
| R126 | CoreMark PCR return, FRET.STK redirect, and SBI byte store packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only DecodeRenameROBPath`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r126-coremark-fret-scalar-redirect-1415-qemu-elf-xcheck-pass --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1415 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 927 normalized rows compared, `git diff --check` |
| R131 | CoreMark ANDI/SWI/ORI/SUB/SLL/MUL packet before richer FRET.STK | `git diff --check`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r131-andi-swi-ori-sub-mul-1595-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1595 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1595 raw rows captured, 1475 expected rows extracted, 1079 normalized rows compared, 0 mismatches; next frontier is the repeated `FRET.STK` return/load packet at `pc=0x4000d2d4`, `insn=0x02a53041` |
| R132 | CoreMark condition-aware FRET.STK RA-load return packet | `git diff --check`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r132-fret-stk-load-1597-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1597 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1597 raw rows captured, 1476 expected rows extracted, 1080 normalized rows compared, 0 mismatches; next frontier is `OP_ADDTPC` writing `x31/T0` at `pc=0x40005cb2`, `insn=0x00009f87` |
| R133 | CoreMark post-return ADDTPC/ORI local T materialization packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r133-addtpc-local-1600-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1600 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1600 raw rows captured, 1479 expected rows extracted, 1082 normalized rows compared, 0 mismatches; QEMU-only 1620-row probe reaches `OP_HL_SD_PCR` at `pc=0x40005cce`, `insn=0x43a1b569000e`, `len=6` |
| R134 | CoreMark high-long `SD.PCR` store sideband packet | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r134-hl-sd-pcr-1611-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1611 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1611 raw rows captured, 1488 expected rows extracted, 1089 normalized rows compared, 0 mismatches; QEMU-only 1640-row probe reaches `OP_CMP_EQI` at `pc=0x40005d2a`, `insn=0x00060f55`, `len=4`, writing reduced `x30/U0` with value `1` |
| R135 | CoreMark `CMP_EQI` compare-immediate local result packet | `git diff --check`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r135-cmp-eqi-1619-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1619 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1619 raw rows captured, 1495 expected rows extracted, 1093 normalized rows compared, 0 mismatches; QEMU-only 1660-row probe reaches `OP_LDI` writing reduced `x31/T0` at `pc=0x40005d2e`, `insn=0x0260bf99`, `len=4` |
| R136 | CoreMark `LDI` local-T destination after hidden `FRET.STK` SP restore | `git diff --check`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only FrontendDecodeStage`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r136-ldi-t-dst-1620-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1620 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, 1620 raw rows captured, 1496 expected rows extracted, 1094 normalized rows compared, 0 mismatches; QEMU-only 1660-row probe reaches `OP_CSEL` at `pc=0x40005d32`, `insn=0xe7860177`, `len=4`, `rd=x2`, `rs1=x12`, `rs2=x24/T0`, `srcp=x28/U0`, visible `src0=x12=0`, destination `x2=0` |
| R137 | CoreMark `CSEL` model/QEMU source-order divergence packet | `git diff --check`, `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --input generated/r136-next-frontier-1660-qemu-probe/traces/qemu.live.raw.jsonl --output /tmp/r137-csel-check.jsonl --max-rows 0 --allow-block-markers` is expected to fail at `pc=0x40005d32` because `OP_CSEL` remains unsupported; evidence collected from Sail `exec_csel`, LinxCoreModel `Compound.cpp`, fetched `origin/SuperScalarModel`, QEMU `trans_csel`, and the 1660-row QEMU probe. LinxCoreModel/Sail select `SrcL` when `SrcP != 0`; QEMU selects `SrcR` when `SrcP != 0`; `docs/chisel/issues.md` records `CHISEL-ISSUE-003`. No RTL CSEL support is promoted until the source-order contract is resolved. |
| R138 | CoreMark `CSEL` model-aligned source-order packet | Local QEMU scalar `trans_csel`, LLVM MC lowering, reduced Chisel `OP_CSEL`, and the QEMU-row reducer are aligned to LinxCoreModel/Sail true-to-`SrcL` semantics. Focused gates: `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, LLVM `csel-source-order.ll`, and AVS `05_move.c` literal CSEL tests after rebuilding the affected tools. The reduced row schema still only admits CSEL when `SrcP` is a tracked T/U local source. |
| R139 | CoreMark `LBUI` local-T byte-load packet | `git diff --check`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, `run_chisel_tests.sh --only ReducedScalarAluExecute`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, and `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r139-lbui-1642-qemu-elf-xcheck --qemu-bin /Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64 --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1642 --allow-block-markers --max-seconds 12 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf` passed: 1642 raw rows captured, 1518 expected rows extracted, 1114 normalized rows compared, 0 mismatches. A 1660-row probe now fails later at `pc=0x40005d94` with dense-slot queue not drained while a conditional marker is active and execute/issue are still busy. |
| R142 | CoreMark FALL block re-entry row-source diagnostic | `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live-fetch RF/ALU wrappers, and a 1900-row CoreMark QEMU probe. Strict extraction fails at raw row 1747 (`pc=0x4000630c`, expected `0x4000632e`); `--allow-block-markers --allow-block-loop-reentry` extracts 1766 rows with 11 annotated `loop_reentry` markers. Replaying the loop-aware expected stream against current generated RTL is expected to fail at `pc=0x4000632a` because F4 captures three slots through `0x4000632e` instead of cutting after two body slots and restarting at the FALL header. See `CHISEL-ISSUE-007`; do not promote this diagnostic flag as a pass criterion before the RTL body-boundary cut/restart is implemented. |
| R143 | CoreMark FALL body-cut/restart bridge | `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live-fetch RF/ALU wrappers, `git diff --check`, and `BUILD_DIR=generated/r143b-loop-reentry-rtl-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. The reduced top consumes loop-aware `loop_reentry` metadata as a temporary `reducedBodyCut*` sideband, cuts the F4 packet before the static continuation slot, advances the source only to the body boundary, and restarts at the FALL header without flushing clipped rows. Replay compares 1330 normalized QEMU/DUT rows with zero mismatches. Full BFU static-predictor `bsize`/`hsize`/fallBPC metadata remains a future packet. |
| R144 | Reduced BFU body-geometry cut module | `run_chisel_tests.sh --only ReducedBfuBodyCutPredictor`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r144-bfu-geometry-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuBodyCutPredictor` now owns the model-style arithmetic `cutPc = headerPc + 2 + bsize` and `restartPc = headerPc + hsize`; the live top consumes `reducedBfu*` geometry instead of exact `cutPc`/`restartPc` commands. The generated-RTL replay compares 1330 normalized QEMU/DUT rows with zero mismatches and emits `generated/r144-bfu-geometry-loop-replay/report/crosscheck_manifest.json`. The reduced harness still supplies geometry from `loop_reentry` rows, so the next packet remains a real BFU/static-predictor geometry producer. |
| R145 | Reduced BFU static-geometry diagnostics | `run_chisel_tests.sh --only ReducedBfuStaticGeometryProducer`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r145-static-bfu-diagnostic-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuStaticGeometryProducer` learns model-style `bsize` from explicit block-boundary or `BSTOP` events and exposes top diagnostics, but the cut path remains driven only by external R144 geometry because general `hsize` and the CoreMark `OP_ACRC` continuation scan are not owned yet. The generated-RTL replay compares 1330 normalized QEMU/DUT rows with zero mismatches and emits `generated/r145-static-bfu-diagnostic-loop-replay/report/crosscheck_manifest.json`. |
| R146 | Reduced BFU resolved-end geometry diagnostics | `run_chisel_tests.sh --only ReducedBfuStaticGeometryProducer`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r146-resolved-bfu-diagnostic-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuStaticGeometryProducer` now has a resolved body-end learning input, so CoreMark's end PC `0x4000632e` can be represented as model-style `SetBsize(header, headerPc + 2, endPc)` without classifying `OP_ACRC` as a block boundary. The top feeds this diagnostic input from the existing external R144 geometry, but body cuts still use the external `reducedBfu*` geometry until branch-resolution ownership and the `hsize` contract are implemented. Replay compares 1330 normalized QEMU/DUT rows with zero mismatches and emits `generated/r146-resolved-bfu-diagnostic-loop-replay/report/crosscheck_manifest.json`. |
| R147 | Reduced BFU resolved-hsize diagnostic carry | `run_chisel_tests.sh --only ReducedBfuStaticGeometryProducer`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r147-resolved-hsize-diagnostic-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuStaticGeometryProducer` now carries `resolvedHSizeBytes` on resolved body-end geometry rows, and the top feeds that payload from the existing external `reducedBfuHSizeBytes` hint. Model search found `SPMInstInfo::hsize` default/copy/consume sites but no non-copy static-predictor producer in the observed path, so this remains diagnostic and body cuts still use the external `reducedBfu*` geometry. Replay compares 1330 normalized QEMU/DUT rows with zero mismatches and emits `generated/r147-resolved-hsize-diagnostic-loop-replay/report/crosscheck_manifest.json`. |
| R148 | Reduced BFU static/external geometry agreement diagnostics | `run_chisel_tests.sh --only ReducedBfuStaticGeometryProducer`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r148-bfu-static-external-match-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `LinxCoreFrontendFetchRfAluTraceTop` now exposes comparable/match/mismatch diagnostics comparing the static producer's `headerPc`/`hsizeBytes`/`bsizeBytes` to the external `reducedBfu*` geometry that still drives `ReducedBfuBodyCutPredictor`; the generated-RTL harness fails on any field mismatch and requires each loop-reentry hint to produce a match. The generated-RTL replay reports `bfu_static_external_comparable=33`, `bfu_static_external_matches=33`, compares 1330 normalized QEMU/DUT rows with zero mismatches, and emits `generated/r148-bfu-static-external-match-loop-replay/report/crosscheck_manifest.json`. Control remains external-geometry driven until a real branch-resolution/body-end owner replaces the replay sideband. |
| R149 | Reduced BFU resolved body-end owner | `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndOwner`, `run_chisel_tests.sh --only ReducedBfuStaticGeometryProducer`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r149-bfu-resolved-body-owner-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuResolvedBodyEndOwner` now owns the resolved body-end event normalization before the static producer consumes it: it matches against the active header, carries resolved `hsize`, computes saturated `bsize = max(bodyEndPc - (headerPc + 2), 0)`, and exposes header-mismatch/inactive/flush/underflow diagnostics. The harness fails on any rejected resolved replay event; the generated-RTL replay reports `bfu_resolved_body_end_accepts=33`, `bfu_static_external_comparable=33`, `bfu_static_external_matches=33`, compares 1330 normalized QEMU/DUT rows with zero mismatches, and emits `generated/r149-bfu-resolved-body-owner-loop-replay/report/crosscheck_manifest.json`. The top still drives `ReducedBfuBodyCutPredictor` from external `reducedBfu*` geometry until a real branch/BFU resolver replaces the replay sideband. |
| R150 | Reduced BFU latched static geometry payload drives body cut | `run_chisel_tests.sh --only ReducedBfuGeometryPredictionLatch`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `BUILD_DIR=generated/r150-bfu-latched-static-body-cut-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`, extractor self-test, shell syntax, and scoped `git diff --check`. `ReducedBfuStaticGeometryProducer` now feeds `ReducedBfuGeometryPredictionLatch`, and `ReducedBfuBodyCutPredictor` consumes the latched geometry payload rather than the raw external `reducedBfu*` replay row or the producer's same-cycle learn event. The external row remains the temporary cut-arm, resolved-event source, and static/external oracle until a real branch/BFU resolver replaces it. |
| R151 | Cross-check manifest model/QEMU provenance | `bash -n tools/chisel/run_chisel_qemu_crosscheck.sh`, `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`, and a bounded manifest replay using existing R150 traces with `--max-commits 8 --normalize-rows 8`. `run_chisel_qemu_crosscheck.sh` now records `git.linxcore_model` and `git.qemu` alongside LinxCore and the superproject, so later generated-RTL/QEMU evidence cites the checked-out C++ model and emulator SHAs used for the packet. The change is additive and preserves the existing comparator status and manifest schema. |
| R152 | Reduced BFU body-cut arm owner | `run_chisel_tests.sh --only ReducedBfuBodyCutArm`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, extractor self-test, shell syntax checks for the live fetch RF/ALU wrappers, scoped `git diff --check`, and `BUILD_DIR=generated/r152-bfu-cut-arm-owner-loop-replay FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuBodyCutArm` now owns the equality boundary between the latched static prediction and temporary external arm candidate; `ReducedBfuBodyCutPredictor` consumes only accepted prediction-owned payloads. Replay compares 1330 normalized QEMU/DUT rows with zero mismatches and reports `bfu_static_external_comparable=33`, `bfu_static_external_matches=33`, `bfu_resolved_body_end_accepts=33`, `bfu_body_cut_arm_comparable=33`, `bfu_body_cut_arm_accepts=22`, and `bfu_body_cut_arm_mismatches=11`. The 11 mismatches are diagnostic, not fatal, because the external replay row still serves as resolved-event source, cut-arm candidate, and oracle until a real branch/BFU resolver replaces it. Manifest provenance includes `git.linxcore`, `git.linxcore_model`, `git.qemu`, and `git.superproject`. |
| R153 | Resolved BFU body-window owner and cold-cut fallback | `run_chisel_tests.sh --only ReducedBfuLocalBodyWindow`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live fetch RF/ALU wrappers, scoped `git diff --check`, and `BUILD_DIR=generated/r153-local-body-window-4000-rtl-replay-v6 FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuLocalBodyWindow` arms only when the learned header is visible in F4, the prediction latch now learns only from accepted `ReducedBfuResolvedBodyEndOwner` geometry, and the body-cut predictor uses resolved body-end geometry as same-cycle cold-cut fallback. Static boundary geometry remains diagnostic because a conditional `BSTART` may reenter or fall through at runtime. Replay compares 3280 normalized QEMU/DUT rows with zero mismatches, reports `bfu_static_external_matches=483`, `bfu_resolved_body_end_accepts=483`, `bfu_body_cut_arm_accepts=482`, `bfu_body_cut_arm_mismatches=0`, and includes required R151 provenance. Closeout: `skill-evolve: update linx-core (static BSTART geometry must not arm body cuts without resolved branch/body-end evidence)`. |
| R154 | Resolved BFU body-end source arbitration | `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndSource`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live fetch RF/ALU wrappers, scoped `git diff --check`, and `BUILD_DIR=generated/r154-resolved-source-4000-rtl-replay FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuResolvedBodyEndSource` now converts replay `bsize` into a body-end PC, prioritizes registered runtime local-cut feedback when present, and exposes runtime/replay oracle diagnostics. Replay remains the cold fallback because the first FALL re-entry still arrives before a local learned window can cut without metadata. Replay compares 3280 normalized QEMU/DUT rows with zero mismatches, reports `bfu_resolved_source_replay_selected=483`, `bfu_resolved_source_runtime_feedback=161`, and includes required R151 provenance. Closeout: `skill-evolve: no-update (R153 already recorded the reusable static-vs-resolved BFU cut invariant; R154 only adds the next module boundary)`. |
| R155 | Replay-qualified pending BFU runtime body-end source | `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndPending`, `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndSource`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live fetch RF/ALU wrappers, `git diff --check`, and `BUILD_DIR=generated/r155-pending-runtime-source-4000-rtl-replay FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuResolvedBodyEndPending` retains local body-window cut feedback until a matching replay-qualified candidate proves the same header, hsize, and body-end PC, then lets `ReducedBfuResolvedBodyEndSource` select runtime geometry instead of the replay fallback. Model evidence is `StaticPredictor::Predict`, `BCtrlUnit::ArbitrateForLocalFB`, `BCtrlUnit::FlushForF4`, `SetLocalPipeFetchSize`, `LocalPipe::sizeGet`, and `SetBsize` in LinxCoreModel BFU code. Replay compares 3280 normalized QEMU/DUT rows with zero mismatches, reports `bfu_resolved_source_runtime_selected=159`, `bfu_resolved_source_replay_selected=324`, `bfu_resolved_source_runtime_feedback=161`, `bfu_resolved_source_runtime_pending_consumes=159`, `bfu_resolved_source_runtime_pending_drop_mismatches=0`, and `bfu_resolved_source_runtime_replay_mismatches=0`, with required R151 provenance. Closeout: `skill-evolve: no-update (R153/R154 already record the reusable BFU resolved-source invariant; R155 changes runtime event lifetime and diagnostics while keeping replay as candidate timing oracle)`. |
| R156 | Pending BFU runtime body-end candidate diagnostic | `run_chisel_tests.sh --only ReducedBfuPendingRuntimeBodyEndCandidate`, `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndPending`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live fetch RF/ALU wrappers, `git diff --check`, and `BUILD_DIR=generated/r156-pending-runtime-candidate-4000-rtl-replay FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuPendingRuntimeBodyEndCandidate` checks whether retained pending runtime body-end feedback would be source-eligible from active-header state alone and compares that replay-free candidate with the replay oracle without driving control. Replay compares 3280 normalized QEMU/DUT rows with zero mismatches, reports `bfu_pending_runtime_candidate_valid=2082`, `bfu_pending_runtime_candidate_without_active_header=0`, `bfu_pending_runtime_candidate_active_header_mismatches=0`, `bfu_pending_runtime_candidate_replay_comparable=159`, `bfu_pending_runtime_candidate_replay_matches=159`, `bfu_pending_runtime_candidate_replay_mismatches=0`, and keeps R155 source counters stable. Closeout: `skill-evolve: no-update (the promotion criterion is documented in this module and ledger; no new cross-module policy until the candidate drives source selection)`. |
| R157 | Replay-free promoted BFU runtime body-end source | `run_chisel_tests.sh --only ReducedBfuPromotedRuntimeBodyEndOracle`, `run_chisel_tests.sh --only ReducedBfuPendingRuntimeBodyEndCandidate`, `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndPending`, `run_chisel_tests.sh --only ReducedBfuResolvedBodyEndSource`, `run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `frontend_fetch_rf_alu_qemu_rows.py --self-test`, shell syntax checks for the live fetch RF/ALU wrappers, `git diff --check`, and `BUILD_DIR=generated/r157-promoted-runtime-source-4000-rtl-replay FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 FETCH_ELF=tests/benchmarks/build/coremark_real.elf bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. `ReducedBfuPendingRuntimeBodyEndCandidate` now drives `ReducedBfuResolvedBodyEndSource` when pending runtime feedback matches the active header, and `ReducedBfuPromotedRuntimeBodyEndOracle` keeps replay proof after the pending slot is consumed. Model evidence remains LinxCoreModel BFU local-pipe retention and `SetBsize`: `ArbitrateForLocalFB`, `FlushForF4`, `SetLocalPipeFetchSize`, `LocalPipe::sizeGet`, and `SetBsize`. Replay compares 3280 normalized QEMU/DUT rows with zero mismatches, reports `bfu_promoted_runtime_body_end_oracle_pending=2082`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=159`, `bfu_promoted_runtime_body_end_oracle_replay_matches=159`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`, with required R151 provenance. Closeout: `skill-evolve: no-update (R153 already records the reusable resolved-evidence-only BFU cut invariant; R157 promotes an already documented active-header candidate and adds replay proof retention)`. |
| R158 | CoreMark 10k live-QEMU BFU/runtime promotion scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r158-coremark-next-frontier-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf` and `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r158-coremark-next-frontier-10000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 10000 --allow-block-markers --allow-block-loop-reentry --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this is a gate-broadening packet for the R157 promoted runtime body-end source. The 6000-row run compares 5137 normalized rows with zero mismatches. The 10000-row run captures 9866 expected rows, compares 8851 normalized rows with zero mismatches, and reports `bfu_promoted_runtime_body_end_oracle_replay_comparable=588`, `bfu_promoted_runtime_body_end_oracle_replay_matches=588`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance includes clean `git.linxcore=ba73c362ddd2e074ad303615bec077777b269f9d`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=08d589dfa19fc3f5b6990787b72e5a501376e31c` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R158 only scales the already documented BFU runtime-promotion proof window)`. |
| R159 | CoreMark 20k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r159-coremark-next-frontier-20000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 20000 --allow-block-markers --allow-block-loop-reentry --max-seconds 45 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R158 CoreMark scale-out. QEMU capture produced 19866 expected rows, the generated-RTL comparator normalized 18138 QEMU/DUT rows, and the run passed with `compared=18137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=3912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=1302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=1302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance includes clean `git.linxcore=977d516080a5d49334fdd349f8942a128d84e7b6`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=14d8a2ccf91f855b57eb1b4db005b1e109e786c2` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R159 only broadens the reduced live CoreMark proof window)`. |
| R160 | CoreMark 50k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r160-coremark-next-frontier-50000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50000 --allow-block-markers --allow-block-loop-reentry --max-seconds 90 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R159 CoreMark scale-out. QEMU capture produced 49866 expected rows, the generated-RTL comparator normalized 45995 QEMU/DUT rows, and the run passed with `compared=45994`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=10341`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=3445`, `bfu_promoted_runtime_body_end_oracle_replay_matches=3445`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance includes clean `git.linxcore=ccfebdc0591b8f016f05b4408d400167b8499ede`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=a6c4f6cb79d553712b246bf890b031a3c93ab6f2` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R160 only broadens the reduced live CoreMark proof window)`. |
| R161 | CoreMark 100k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r161-coremark-next-frontier-100000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 100000 --allow-block-markers --allow-block-loop-reentry --max-seconds 180 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R160 CoreMark scale-out. QEMU capture produced 99866 expected rows, the generated-RTL comparator normalized 92423 QEMU/DUT rows, and the run passed with `compared=92422`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=21057`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=7017`, `bfu_promoted_runtime_body_end_oracle_replay_matches=7017`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance includes clean `git.linxcore=8340360e9027948350ecc2059999e79c565fd50a`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=3462444070c79360920cb2e7e295f6010fbf46d1` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R161 only broadens the reduced live CoreMark proof window)`. |
| R162 | CoreMark 200k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r162-coremark-next-frontier-200000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 200000 --allow-block-markers --allow-block-loop-reentry --max-seconds 360 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R161 CoreMark scale-out. QEMU capture produced 199866 expected rows, the generated-RTL comparator normalized 185281 QEMU/DUT rows, and the run passed with `compared=185280`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=42483`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=14159`, `bfu_promoted_runtime_body_end_oracle_replay_matches=14159`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance includes clean `git.linxcore=d498177e82355097476ddf26e7f58f9c2f0bd076`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=6be5f2697c712c871abe00c5057400eaebee91b7` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R162 only broadens the reduced live CoreMark proof window)`. |
| R163 | CoreMark 400k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 400000 --allow-block-markers --allow-block-loop-reentry --max-seconds 720 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, followed after an interrupted session by `BUILD_DIR=/Users/zhoubot/linx-isa/rtl/LinxCore/generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck FETCH_ELF=/Users/zhoubot/linx-isa/rtl/LinxCore/tests/benchmarks/build/coremark_real.elf FETCH_QEMU_TRACE=/Users/zhoubot/linx-isa/rtl/LinxCore/generated/r163-coremark-next-frontier-400000-qemu-elf-xcheck/traces/qemu.live.raw.jsonl FETCH_QEMU_MAX_ROWS=0 FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`. No RTL changed; this extends the R162 CoreMark scale-out using the captured 400000-row QEMU prefix. QEMU capture produced 399866 expected rows, the generated-RTL comparator normalized 370995 QEMU/DUT rows, and the run passed with `compared=370994`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=85341`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=28445`, `bfu_promoted_runtime_body_end_oracle_replay_matches=28445`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes clean `git.linxcore=be79b7ad0ebfefd6d79ad17a78592f42a0ed97f4`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=6ae5b996c85ac03fc34a3c6e97c75bdd4b8f2462` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R163 only broadens the reduced live CoreMark proof window)`. |
| R164 | CoreMark 800k live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r164-coremark-next-frontier-800000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 800000 --allow-block-markers --allow-block-loop-reentry --max-seconds 1440 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R163 CoreMark scale-out. QEMU capture produced 799866 expected rows, the generated-RTL comparator normalized 742423 QEMU/DUT rows, and the run passed with `compared=742422`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=171057`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=57017`, `bfu_promoted_runtime_body_end_oracle_replay_matches=57017`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes clean `git.linxcore=2ea093c4c078773b7601f128c74e4eb54ca6173a`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=14a75d7b96c8fcd6c732b63eb60098271e7b97a5`, and dirty `git.superproject=f07f248508fcd1e71ad23e911c7649cf173ba882` only because unrelated pre-existing bring-up files remained unstaged in the superproject. Closeout: `skill-evolve: no-update (no new reusable invariant; R164 only broadens the reduced live CoreMark proof window)`. |
| R165 | CoreMark 1.6M live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r165-coremark-next-frontier-1600000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1600000 --allow-block-markers --allow-block-loop-reentry --max-seconds 2880 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R164 CoreMark scale-out. QEMU capture produced 1599866 expected rows, the generated-RTL comparator normalized 1485281 QEMU/DUT rows, and the run passed with `compared=1485280`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=342483`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=114159`, `bfu_promoted_runtime_body_end_oracle_replay_matches=114159`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes clean `git.linxcore=9f251686af9658031ae5e61ca15fc7992aeb3958`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, dirty `git.qemu=9fd825f7a8e4ef22f088de7a69b5fc7699388929`, and dirty `git.superproject=d3ec53128fc09038fa21e1f4914e29538cac6df7`. Closeout: `skill-evolve: no-update (no new reusable invariant; R165 only broadens the reduced live CoreMark proof window)`. |
| R166 | CoreMark 3.2M live-QEMU reduced RTL scale-out | `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r166-coremark-next-frontier-3200000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 3200000 --allow-block-markers --allow-block-loop-reentry --max-seconds 5760 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`. No RTL changed; this extends the R165 CoreMark scale-out. QEMU capture produced 3199866 expected rows, the generated-RTL comparator normalized 2970995 QEMU/DUT rows, and the run passed with `compared=2970994`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean at the larger window: `bfu_static_external_matches=685341`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=228445`, `bfu_promoted_runtime_body_end_oracle_replay_matches=228445`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes clean `git.linxcore=8801b5bce3508772dcda3e41438dae3278059fe7`, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=576efbe7341608abce7273fefdf216f61d0b0747` because untracked `experimental/` remained in the superproject root. Closeout: `skill-evolve: no-update (no new reusable invariant; R166 only broadens the reduced live CoreMark proof window)`. |
| R167 | Block scalar-done retire sequencer | `bash tools/chisel/run_chisel_tests.sh --only BlockScalarDoneSequencer`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only BROB`, `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r167-block-scalar-done-sequencer-smoke --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockScalarDoneSequencer` now owns the one-cycle scalar-done to BROB-retire gap that had been embedded in `DecodeRenameROBPath`: scalar done passes through in the source cycle, retire/free pulses for the same BID one cycle later, overlap keeps the old retire visible while capturing the new scalar-done BID, and cleanup clears pending state for following cycles. This is a behavior-preserving ownership extraction, not full marker-row ROB retirement; reduced marker-consume, scalar redirect, and ROB block-last deallocation still select the source BID. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes dirty `git.linxcore=a71ff68c7298c41ea54312a09a0d39d680ff28fa` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, dirty `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=576efbe7341608abce7273fefdf216f61d0b0747` from pre-existing outer-worktree state. Closeout: `skill-evolve: no-update (the scalar-done-before-retire and marker lifecycle invariants are already in linx-core; R167 only extracts their local timing owner and documents it in module Markdown)`. |
| R168 | Block marker lifecycle owner extraction | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only BlockScalarDoneSequencer`, `bash tools/chisel/run_chisel_tests.sh --only BROB`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r168-block-marker-lifecycle-smoke --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockMarkerLifecycle` now owns the active full-BID context, marker readiness, conditional/direct marker decision, same-slot pre-retire, scalar redirect cleanup, scalar-created active block seeding, ROB block-last closure, and scalar-done source selection that were previously embedded in `DecodeRenameROBPath`. This preserves reduced marker-consume behavior and does not yet allocate marker rows into the ROB; it narrows the next full-marker-retirement packet to feeding the BCTRL lifecycle owner from real marker-row retire events. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes dirty `git.linxcore=bd2cea47cd2c93acd1766cd68c28389f8b74343a` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, dirty `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=576efbe7341608abce7273fefdf216f61d0b0747` from pre-existing outer-worktree state. Closeout: `skill-evolve: no-update (the reduced marker lifecycle policy is already in linx-core; R168 changes local ownership and documents the module boundary)`. |
| R169 | ROB marker-retire source infrastructure | `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`, `bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r169-marker-retire-source-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockMarkerRetireSource` now defines the row-owned marker retirement payload, `ROBEntryBank` stores marker boundary/stop/kind/target sidecars at allocation, and deallocation publishes a width-wide source vector with native `bid/gid/rid`, PE/STID, full block BID, PC, raw instruction, length, and boundary metadata. `DispatchROBAllocator` and `DecodeRenameROBPath` forward the allocation sidecars and the deallocation source vector without consuming it. This does not switch the reduced live path away from marker-only skip; it creates the source boundary that the next full marker-row retirement owner can serialize into `BlockMarkerLifecycle`. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Provenance at run time includes dirty `git.linxcore=43a750265cc579217dfc2d90d449e497331af3d7` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=d50e9a13dc52cc57acfe4063c52022d4f2953918` from pre-existing outer-worktree state plus the unpinned LinxCore gitlink. Closeout: `skill-evolve: no-update (linx-core already records full marker ROB retirement as the next block-control owner; R169 adds local source plumbing, docs, and tests only)`. |
| R170 | Marker retire-source serializer boundary | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`, `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`, and `git diff --check`. `BlockMarkerRetireSourceSerializer` now owns the policy-free BCTRL queue boundary between ROB deallocation marker sources and future marker lifecycle consumption: it accepts a full source window only when there is room for every lane, compacts valid marker rows in ROB slot order, preserves queued rows across downstream backpressure, clears queued state on `clear`, and exposes one `BlockMarkerRetireSource` at a time. This does not yet feed `BlockMarkerLifecycle` or gate ROB deallocation in the live path; it prevents the next packet from scanning or collapsing the R169 width-wide source vector directly. Provenance at run time includes dirty `git.linxcore=b725f3bdadf00328e906c5c37c3efa68e559ce5e` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and pushed `origin/linx-isa-0.57=9fc16c79b10824fbd7aaf9cad84ebc08cad3e9f4`. Closeout: `skill-evolve: no-update (the full-window retire-source preservation invariant was already recorded for R169; R170 adds the local serializer boundary and module docs/tests)`. |
| R171 | Marker retire-source serializer integration | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r171-marker-retire-serializer-integration-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `DecodeRenameROBPath` now instantiates `BlockMarkerRetireSourceSerializer`, feeds it from `DispatchROBAllocator.deallocBlockMarkerRetireSource`, gates ROB deallocation on marker-source full-window queue credit beside the existing T/U retire-source credit, clears queued marker sources on backend/block lifecycle cleanup, and exposes `robMarkerRetireSource*` diagnostics while draining the serialized source policy-free. This still does not feed `BlockMarkerLifecycle`; it makes marker-source preservation part of the live deallocation handshake before lifecycle policy is connected. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance at run time includes dirty `git.linxcore=a81618220f538c20253fa15391030f3b97ff5ebc` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=d50e9a13dc52cc57acfe4063c52022d4f2953918` from pre-existing outer-worktree state plus the unpinned LinxCore gitlink. Closeout: `skill-evolve: no-update (R171 only wires the already documented marker-source serializer into backend deallocation diagnostics/backpressure; lifecycle consumption and per-STID active marker policy remain the next reusable owner)`. |
| R172 | Retired marker lifecycle consumer | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`, `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r172-retired-marker-lifecycle-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockMarkerLifecycle` now has a distinct retired-marker input lane fed by `BlockMarkerRetireSourceSerializer`; serializer `outReady` comes from lifecycle retired-marker readiness, and `DecodeRenameROBPath` exposes `robMarkerRetireSourceLifecycle*` ready/fire/boundary/stop diagnostics. Retired fallthrough boundaries install the row-owned `BlockMarkerRetireSource.blockBid` captured before dispatch instead of requesting BROB allocation at retire time, while malformed fallthrough boundaries with `blockBidValid=false` backpressure instead of being dropped. The reduced decode-time marker-skip lane is unchanged and remains separate from retired marker-row consumption. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance at run time includes dirty `git.linxcore=9c32d7f6bfbf6f9f17a6577cedc9787a25ac9450` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c`, and dirty `git.superproject=c2b47a4188873030a04388e192f439a6fbcd1f7b` from pre-existing outer-worktree state plus the unpinned LinxCore gitlink. Closeout: `skill-evolve: no-update (R172 implements the already planned lifecycle consumer and records the row-owned retired marker BID rule in module docs; per-STID state, recovery-exact pruning, and full marker-row ROB admission remain the next reusable owner)`. |
| R173 | Marker retire-source recovery pruning | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r173-marker-retire-source-flush-prune-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockMarkerRetireSourceSerializer` now accepts `FlushBus`, blocks enqueue/dequeue during cleanup, prunes only the newest flushed marker-source suffix, compacts surviving sources in order, and reports `sourcePruneCount`. `DecodeRenameROBPath` wires `cleanup.flush` into the marker serializer, keeps the serializer's hard clear inactive in the backend composition, and exposes `robMarkerRetireSourcePruneCount` beside the existing marker-source queue and lifecycle diagnostics. The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance at run time includes dirty `git.linxcore=34a3f8a684716189bf26c2e36508b5f2e65c0de8` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, dirty `git.qemu=0211cfc2c56ad2b371717a4d6ba3c48c26908e2c` from unrelated local `target/linx/helper.c`, and dirty `git.superproject=c2b47a4188873030a04388e192f439a6fbcd1f7b` from pre-existing outer-worktree state plus the unpinned LinxCore gitlink. Closeout: `skill-evolve: no-update (R173 reuses the existing T/U retire-source cleanup policy locally for marker sources; per-STID active marker state and full marker-row ROB admission remain the next reusable owners)`. |
| R174 | STID-indexed marker lifecycle state | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycleSpec`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPathSpec`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTopSpec`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r174-stid-marker-lifecycle-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. `BlockMarkerLifecycle` now stores active BID/target/conditional/direct state in STID-indexed lanes. Decode-time marker operations use `markerStid`, retired marker operations use `retiredMarker.stid`, scalar rows query active state with `activeQueryStid`, scalar-created active blocks install with `scalarBlockStartStid`, and execute-owned scalar redirects clear only `scalarRedirectStid`. `DecodeRenameROBPath` forwards marker and selected scalar `threadId` into those inputs and adds `scalarRedirectStid` to its IO; the current reduced top still instantiates one STID lane, preserving STID0 behavior while matching the C++ model's per-STID block-control owner shape (`BRQ`, `BCtrlUnit`, `BlockROB`, and `DCTop`). The smoke captured 6000 raw QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, and passed with `compared=5137`, `mismatches=0`, `cbstop_qemu=0`, and `cbstop_dut=0`. BFU diagnostics stayed clean: `bfu_static_external_matches=912`, `bfu_body_cut_arm_mismatches=0`, `bfu_promoted_runtime_body_end_oracle_replay_comparable=302`, `bfu_promoted_runtime_body_end_oracle_replay_matches=302`, `bfu_promoted_runtime_body_end_oracle_replay_mismatches=0`, and `bfu_promoted_runtime_body_end_oracle_overwrites=0`. Manifest provenance at run time includes dirty `git.linxcore=b8b94fa82e4f67d3c322b71fd92ccd11b4b36786` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=08783bb4572df9c5f6623bed0d468641befab762`, and dirty `git.superproject=f2dd29f2b282a58dacccabb5e290f42a723afa40` from the uncommitted LinxCore/skills submodule state plus untracked `experimental/`. Closeout: `skill-evolve: update linx-core (added the STID-owned marker lifecycle invariant, focused R174 gates, and missing BCTRL Chisel gates; pushed linx-skills d38f3ec)`. |
| R192 | Marker-row BROB retire drain | `bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle`, `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`, `bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint`, `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`, `bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-default-skip-regression-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 3 --capture-rows 16 --allow-block-markers --max-seconds 10 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`, and `git diff --check`. The filtered marker-row path now invalidates backend decode inputs while an admitted marker drain barrier holds dense slots, rebuilds scalar GPR `smap` from surviving mapQ rows across wrapped BID/RID cleanup, and drains marker-owned redirect boundary BROB entries that have no scalar body. `BlockMarkerLifecycle` completes the prior active BID first, queues the retired marker row's own BID for a later scalar-done pulse, and `DecodeRenameROBPath` does not flush `BlockScalarDoneSequencer` on redirect cleanup so the delayed retire/free pulse can clear the completed marker-only BROB slot. The marker-row CoreMark gate admitted and filtered 36 marker commits, normalized 88 QEMU/DUT rows, compared 88 rows, and passed with zero mismatches and no CBSTOP divergence. The default skip-mode regression admitted zero marker rows, compared three rows, and passed. Manifest provenance at run time includes dirty `git.linxcore=c32e883d02c7989e322f95e5944303755b979978` because the packet had not yet been committed, clean `git.linxcore_model=3c0878da3aa1e06669b718e93269f094e7244066`, clean `git.qemu=5cfb672a711bb2172bfe7de6c6b7bd1bdb47e902`, and dirty `git.superproject=da7327c8c417e40c60f84f8375c78267bbfc4044` from uncommitted submodule state. Closeout: `skill-evolve: update linx-core (marker-row redirect boundaries can own marker-only BROB entries that must be completed after the prior active block, and redirect cleanup must preserve the scalar-done sequencer retire pulse)`. |

New frontend/backend modules may be implemented after this base, but they do
not become replacement evidence until their rows are visible through monitored
commit or stage-owner trace contracts.

R192 narrows the next block-control packet: scale marker-row mode beyond the
128-row repeated-loop window and then decide when the default live CoreMark top
can leave `skipBlockMarkers=true`.

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
8. `run_chisel_frontend_fetch_trace_top_xcheck.sh` after changes to the live
   frontend fetch source top, bounded memory-window fixture, source-to-F4
   handshake, temporary completion surrogate, or top commit export.
9. `run_chisel_frontend_alu_trace_top_xcheck.sh` after changes to scalar ALU
   execute completion, completion-row payload wiring, or the frontend ALU
   trace-top driver.
10. `run_chisel_frontend_rf_alu_trace_top_xcheck.sh` after changes to scalar RF
   operand sourcing, registered issue-queue source readiness,
   oldest-ready issue selection, issue-queue release, execute
   physical-destination writeback metadata, or the shared frontend ALU
   trace-top driver.
11. `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` after changes to
    the live fetch source to RF-backed reduced issue/ALU top, file-backed
    or sparse ELF fetch-memory feeder, bounded memory-window response fixture,
    source PC advance, dense F4 slot capture/drain, issue enqueue, RF
    writeback, ALU completion, or commit export.
12. `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh` after changes to
    live QEMU capture, `FETCH_QEMU_TRACE` selection, `FETCH_ELF` binding, or
    the row-derived RF preload contract for the reduced fetch RF/ALU gate.
13. `run_chisel_qemu_crosscheck.sh --dry-run` after wrapper or QEMU selection
   changes.
14. `run_chisel_qemu_trace_replay_xcheck.sh --dry-run` after QEMU row replay
   wrapper changes, followed by a bounded `--qemu-trace` or `--elf` replay.
   `--max-commits` is the architectural compare depth; `--replay-rows` is only
   the raw search/replay cap before metadata filtering. Direct-boot CoreMark
   replay also requires trailing QEMU memory args such as `-m 1280M` because
   the current ELF maps load segments at `0x40000000`.
15. Inspect `crosscheck_manifest.json` after any non-dry-run generated-RTL,
   QEMU-row replay, or full QEMU comparison routed through the common wrapper.
   For R151 and later, require `git.linxcore`, `git.linxcore_model`,
   `git.qemu`, and `git.superproject` provenance before citing the manifest as
   replacement evidence.
16. Full QEMU-vs-DUT trace compare only after the DUT emits real architectural
   commit rows for the slice.
17. LinxCoreModel `gfsim -f <elf>` only after the same direct-boot ELF passed
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
- a new required cross-check evidence artifact such as the QEMU/DUT manifest,
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
- a live-source promotion rule where future replacement evidence must remove
  testbench-owned frontend packets before claiming a top can consume fetch
  traffic.
- a QEMU row-source rule where a reduced live-fetch gate may consume QEMU
  commit JSONL only after strict prefix validation proves every row is inside
  the current DUT subset.
- a live-QEMU ELF gate rule where bounded FIFO capture, optional PC filtering
  after legal block headers, `FETCH_ELF`, and `FETCH_QEMU_TRACE` must pass
  together before claiming live fetch RF/ALU replacement evidence.
- a reduced block-marker rule where legal `BSTART`/`BSTOP` rows may be
  preserved as DUT-only skip rows.
- a reduced dense frontend rule where every valid F4 slot from one response
  window is queued before serial decode/ROB drain, and marker-slot checks are
  marker-owned rather than global same-cycle issue silence.
- a reduced marker lifecycle rule where consumed `BSTART` allocates a
  BROB-only active BID, following scalar rows reuse that full BID, and consumed
  `BSTOP` completes the active BID before BROB retire.
- a reduced scalar-created block rule where a target-body scalar allocation
  with no active marker block must seed active block state until a matching
  block-last or later marker boundary closes it.
- a reduced live RF/ALU store rule where the top may bypass store-dispatch
  residency only while ALU execute owns the compared store sideband and the
  STA/STD execution plus STQ commit/free owners are absent.

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

1. Scale the R194 marker-row filtered comparator beyond the 512-row CoreMark
   repeated-loop window. R178 adds `LinxCoreFrontendFetchRfAluMarkerRowsTraceTop`,
   R179 proves the wrapper admits the first `C.BSTART` row in generated RTL,
   R180 adds `--marker-rows` to the QEMU/Verilator gate, and R192 proves
   admitted marker rows through redirect cleanup with 36 filtered marker
   commits and 88 compared scalar/macro rows. R194 proves the same admitted
   marker path at 512 raw QEMU rows with 161 filtered marker commits and 337
   compared scalar/macro rows; it also fixes the generated-RTL harness rule for
   bounded captures that end on an admitted marker by draining only marker
   commits after the final expected row. The next packet should grow that
   filtered policy while watching marker-only BROB retire drain, loop re-entry,
   stop/redirect boundaries, and default skip-mode parity before changing the
   default live CoreMark top.
2. Full marker lifecycle split and live-top switch: R172 feeds serialized
   retired marker rows into `BlockMarkerLifecycle` with row-owned BID evidence,
   R173 gives the marker-source queue recovery-exact suffix pruning, R174 makes
   active marker state STID-indexed, and R175 lets unskipped marker rows
   rename-update, stay off the reduced scalar ALU path, and complete internally
   through the ROB. R176 adds the decode-time context owner and R177 wires it
   into the backend path behind `useMarkerDecodeContext=true`; R178 gives that
   mode a named top-level wrapper, R179 proves the wrapper in generated RTL,
   and R180 gives it a filtered QEMU comparator path for the first CoreMark
   prefix. R192 adds marker-owned redirect-boundary BROB completion and keeps
   sequencer retire pulses alive across redirect cleanup. R194 scales the
   filtered marker-row path to 512 raw QEMU rows and keeps marker-only tail
   commits out of the scalar comparator. The reduced live top still uses
   `skipBlockMarkers=true`; remove marker skipping only after the filtered
   marker-row path scales beyond the R194 window and its lifecycle side effects
   are checked across broader stops and redirects.
3. Replace the temporary replay resolved-event source with real branch/BFU
   resolver outputs. R153 has resolved cold-cut fallback and local
   header-window arming, but external QEMU metadata still provides body-end
   eligibility; the next packet should derive that from decoded/execute branch
   outcome and close skipped-marker lifecycle without replay-side help.
4. Full issue scheduler timing: add explicit wakeup ports, alternate model
   select preferences, P1/I1/I2 RF-read arbitration, cancel, replay, and bypass
   behavior behind the reduced oldest-ready selector.
5. Live commit trace schema: extend the top-owned `LC-IF-CHISEL-XCHK-*`
   event stream from commit-only rows toward trap, memory, recovery, and block
   sidebands.
6. QEMU full-compare harness: scale the reduced live CoreMark window beyond
   the R166 3.2M-row pass, or feed a bounded direct-boot window into the same
   comparator path once frontend/decode/execute/LSU can retire it from the
   full DUT stream.
7. Per-bank cleanup source vectors: publish ROB/STQ cleanup candidates with
   enough PE/STID structure for multi-bank cleanup selection in the SGPR array.
8. Multi-PE packet production and bank instantiation: teach the upstream
   frontend/top owner to set nonzero `FrontendDecodePacket.peId` and instantiate
   matching `ScalarTURenameBridge`/`TULinkLocalBankArray` PE banks.
9. LinxCoreModel ROB maintenance note: audit `SPEROB`, `PROBCommon`,
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
