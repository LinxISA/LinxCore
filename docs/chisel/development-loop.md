# LinxCore Chisel Development Loop

Date: 2026-06-29

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

## Reference Evidence

The next ROB packet is anchored to these C++ model facts:

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
| `model/iex/iex_iq.cpp` / `model/iex/iex_dispatch.cpp` / `model/iex/pipe/alu_pipe.cpp` / `model/iex/iex.cpp` | R86 scalar issue handoff stores renamed rows before execute, initializes and wakes sources by physical tag, selects the oldest resident ready non-issued entry, marks selected entries issued without removing them, and releases issued rows later by `(bid, rid, stid)`. The reduced Chisel queue preserves enqueue, registered RF-readiness gating, ROB identity, oldest-ready resident selection, issued-entry residency, and ALU W2 release while still deferring P1/I1/I2 RF-read arbitration, alternate select preferences, cancel, replay, and bypass. |

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
| 12 | Live commit trace schema | `commit/`, `top/`, `tools/chisel/trace_schema_adapter.py` | `trace_schema_adapter.py --self-test`, reduced/top/replay/frontend-top gates |
| 13 | QEMU full-compare harness | `tools/chisel/run_chisel_qemu_crosscheck.sh`, trace writer | dry-run, then full compare on a bounded direct-boot smoke |
| 14 | Multi-PE/STID bank expansion | frontend packet production plus T/U bank array | PE/STID-specific rename and retire-source gates |
| 15 | LinxCoreModel ROB maintenance note | `docs/chisel/model-notes/ROBCommit.md` and model-lane notes | documentation check plus model ownership review |

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
9. `bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh` after
   changes to scalar ALU execute completion, completion-row payload wiring, or
   the frontend ALU trace-top driver.
10. `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh` after
   changes to scalar RF operand sourcing, registered issue-queue source
   readiness, oldest-ready issue selection, physical writeback metadata, or
   the shared frontend ALU trace-top driver.
11. `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` for wrapper or
   QEMU-selection changes.
12. Full QEMU-vs-DUT comparison only after the Chisel top emits real
   architectural commit rows.
13. `gfsim -f <elf>` only after the same ELF passed QEMU in the same run packet.

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
- a recurring QEMU/model/Chisel first-divergence workflow,
- a superproject or skills-submodule maintenance rule future agents must obey.
- a ready/valid rule where physical source readiness must gate issue from a
  queued row, while reduced rename acceptance remains queue-capacity driven.
- an issue-queue lifetime rule where execute acceptance marks a row issued but
  removal waits for a later model-derived release identity.

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
