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
| 7 | Live commit trace schema | `commit/`, `top/`, `tools/chisel/trace_schema_adapter.py` | `trace_schema_adapter.py --self-test`, reduced/top/replay/frontend-top gates |
| 8 | QEMU full-compare harness | `tools/chisel/run_chisel_qemu_crosscheck.sh`, trace writer | dry-run, then full compare on a bounded direct-boot smoke |
| 9 | Multi-PE/STID bank expansion | frontend packet production plus T/U bank array | PE/STID-specific rename and retire-source gates |
| 10 | LinxCoreModel ROB maintenance note | `docs/chisel/model-notes/ROBCommit.md` and model-lane notes | documentation check plus model ownership review |

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
10. `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` for wrapper or
   QEMU-selection changes.
11. Full QEMU-vs-DUT comparison only after the Chisel top emits real
   architectural commit rows.
12. `gfsim -f <elf>` only after the same ELF passed QEMU in the same run packet.

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
