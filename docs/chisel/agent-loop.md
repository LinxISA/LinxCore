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
| `rtl/LinxCore` | `0dab21f5d522cda363b18fd929a00e9ccce750bb` |
| `model/LinxCoreModel` | `68b06b2a8dd07db98bd562aeae7e5a8867c6d450` |

LinxCoreModel was refreshed with `git pull --ff-only` on 2026-06-28 and was
already up to date. The superproject root was fetched but not pulled because it
has unrelated local edits.

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

1. Full STQ owner: consume `STQFlushPrune.freeMask` in a real STQ state owner
   without hiding store-commit queue, SCB/MDB, or memory side effects in the
   mask generator.
2. Rename/checkpoint cleanup consumer: connect `RecoveryCleanupControl` to the
   first scalar rename restore/checkpoint owner.
3. Live commit trace schema: define the first full-core `LC-IF-CHISEL-XCHK-*`
   bundle covering commit, trap, memory, recovery, and block sidebands.
4. QEMU full-compare harness: replace reduced synthetic rows with live Chisel
   commit rows once the top can retire a direct-boot smoke.
5. `FrontendDecodeStage`: consume `FrontendDecodeIngress` slots and start the
   D1/D2 opcode table without changing the ingress transport contract.
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
