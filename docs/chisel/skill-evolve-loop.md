# LinxCore Chisel Skill-Evolve Loop

Date: 2026-06-30

## Purpose

This runbook is the loop for many agents replacing the pyCircuit LinxCore RTL
lane with Chisel. It is intentionally evidence-first: every packet learns the
microarchitecture from LinxCoreModel C++ owner files, documents the module
contract in Markdown, implements or verifies one Chisel owner, cross-checks
through the neutral QEMU/DUT infrastructure, and closes with an explicit
skill-evolve decision.

Use this page with:

- `docs/chisel/development-loop.md` for current packet order and gate ladder.
- `docs/chisel/agent-loop.md` for the long packet ledger.
- `docs/chisel/module-index.md` for module ownership and documentation links.

## Baseline Refresh

At the start of each packet, record the exact repository SHAs and dirty files.
Fetch remotes to update metadata, but do not merge over dirty worktrees.

Current R103 planning refresh:

| Repository | Checked-out SHA | Remote observation |
|---|---:|---|
| `linx-isa` | `6e5b0bffca04c1c0f50c8bb0887e4f4a5cd55041` | Local branch is ahead of `origin/main`; unrelated root files remain dirty. |
| `rtl/LinxCore` | `d56ea4f505b0e4db9dcd81604e8fdd19bf1ce965` | Local branch is ahead of `origin/main`; unrelated architecture Markdown files remain dirty. |
| `model/LinxCoreModel` | `1993e4e749403824a4908548baf77d5e15117068` | Fast-forwarded clean submodule to `origin/main`. |
| `emulator/qemu` | `8dd1dcdbde20a7543fc5081bc52fd81a7b85b985` | QEMU remote uses `origin/master`; local checkout is ahead of that ref. |
| `skills/linx-skills` | `ba85f35d8aa08b089ec605f585ce34c3ec6eba1b` | Local branch is ahead of `origin/main`; unrelated skill files remain dirty. |

If GitHub fetch fails with `LibreSSL SSL_connect: SSL_ERROR_SYSCALL`, set the
repo-local Git transport fallback and retry:

```bash
git -C /Users/zhoubot/linx-isa config http.version HTTP/1.1
git -C /Users/zhoubot/linx-isa/rtl/LinxCore config http.version HTTP/1.1
git -C /Users/zhoubot/linx-isa/model/LinxCoreModel config http.version HTTP/1.1
git -C /Users/zhoubot/linx-isa/emulator/qemu config http.version HTTP/1.1
git -C /Users/zhoubot/linx-isa/skills/linx-skills config http.version HTTP/1.1
```

## Agent Loop

Each packet follows this shape:

| Step | Action | Required output |
|---|---|---|
| Scope | Pick one owner boundary, one module or harness, and owned files. | Packet prompt with write scope. |
| Refresh | Fetch refs, record SHAs, record unrelated dirty files. | Baseline row in packet notes. |
| Learn model | Read the C++ owner files before writing Chisel. | Extracted invariants with file and symbol names. |
| Cross-check QEMU | Identify the QEMU commit-row or direct-boot ELF evidence for the slice. | QEMU command, row source, and subset limits. |
| Document | Update or create the module Markdown page before promotion. | Interface, purpose, state, logic, model alignment, gates. |
| Implement | Write one Chisel owner in a separate file, or patch one harness/runbook owner. | Focused diff only in owned files. |
| Verify | Run the narrow unit gate and affected generated-RTL/QEMU cross-check gates. | Commands, pass/fail, manifest paths. |
| Maintain | If model/QEMU divergence appears, classify first owner before editing. | `chisel`, `qemu`, `model`, `compiler`, `benchmark`, `adapter`, or `unknown`. |
| Evolve | Decide whether a skill update is warranted. | Final `skill-evolve: update ...` or `skill-evolve: no-update ...`. |

## Model Learning Map

Use LinxCoreModel as the microarchitecture source of truth and QEMU as the
architectural execution oracle. The first-pass learning map for full Chisel RTL
development is:

| Domain | LinxCoreModel owner files | Chisel owners |
|---|---|---|
| Block allocation and BROB | `model/bctrl/BROB.{h,cpp}`, `model/bctrl/BCtrl.cpp`, `model/ModelCommon/BlockCommand.*` | `bctrl/BID.scala`, `bctrl/BROB.scala`, `backend/DispatchROBAllocator.scala` |
| Decode to ROB reservation | `model/bctrl/spe/DCTop.cpp`, `model/bctrl/spe/Decoder.*`, `model/bctrl/spe/SPEROB.{h,cpp}` | `frontend/*Decode*.scala`, `backend/DecodeRenameQueue.scala`, `backend/DecodeRenameROBPath.scala`, `rob/ROBEntryBank.scala` |
| Scalar rename and local T/U | `model/bctrl/spe/GPRRename.*`, `model/bctrl/spe/SPERename.*`, `model/bctrl/LocalRegMgr.*` | `rename/GPRRenameCheckpoint.scala`, `rename/ScalarTURenameBridge.scala`, `rename/TULink*.scala` |
| ROB commit/dealloc/block last | `model/bctrl/spe/SPEROB.cpp`, `model/pe/PECommon/PROBCommon.*`, `model/interface/CommitInfo.h` | `rob/ROBEntryBank.scala`, `commit/CommitTrace.scala`, `backend/DecodeRenameROBPath.scala` |
| Frontend F0-F5/F4 bundles | `model/pe/ifu/iside/pe_ifu.cpp`, `model/ModelCommon/bus/FetchReqBus.h`, `model/pe/PECommon/DecodeBundle.h`, `isa/ISACommon/DecodeUtiles.h` | `frontend/FrontendFetchPacketSource.scala`, `frontend/F4DecodeWindow.scala`, `frontend/F4DenseSlotQueue.scala` |
| Scalar issue and ALU | `model/iex/iex_iq.cpp`, `model/iex/iex_dispatch.cpp`, `model/iex/iex_rf.cpp`, `model/iex/pipe/iex_pipe.h`, `model/iex/pipe/alu_pipe.cpp` | `execute/ReducedScalarIssueQueue.scala`, `execute/ReducedScalarIssuePick.scala`, `execute/ReducedScalarRegisterFile.scala`, `execute/ReducedScalarAluExecute.scala` |
| LSU/STQ/SCB/MDB/LIQ | `model/lsu/**`, `model/ModelCommon/LSUUtils.*` | `lsu/*.scala` |
| Recovery and flush | `model/core/FlushControl.*`, `model/ModelCommon/bus/FlushBus.h`, `model/bctrl/BROB.cpp`, `model/bctrl/spe/SPEROB.cpp` | `recovery/*.scala`, `rob/ROBFlushPrune.scala`, `rename/TULinkRecoveryCleanupPath.scala`, `lsu/STQFlushPrune.scala` |

## ROB And Cross-Check First

The next development wave must keep ROB/BROB and cross-check infrastructure
ahead of wider frontend or opcode work. R102 proves dense F4 windows can feed
the reduced serialized path, but `BSTART` and `BSTOP` are still skip-only rows.
Full block correctness starts only when marker rows update real block lifecycle
state:

- `BSTART` belongs to the new block, but retiring it marks scalar done for the
  old active block.
- `BSTOP` retiring marks scalar done for the current active block.
- `BlockROB::commitBlock` retires only oldest `COMPLETED` blocks.
- `SPEROB::commit` marks completed uops `INST_RETIRED`.
- `SPEROB::dealloc` releases retired rows, stops at block-last, and calls
  `CommitLast` / `CommitBlock`.
- `SPEROB::CommitBlock` deallocates all rows for the block, calls
  `SetBlockComplete`, then runs `CleanCMAP` before
  `ReportLocalRegBlockCommit`.

Therefore the immediate packets should be:

| Order | Packet | Primary proof |
|---|---|---|
| 1 | Marker retire to `scalarDone` and BROB completion | Unit tests on `BrobMetaTracker` or its successor plus live marker fixture diagnostics. |
| 2 | Block commit/dealloc event visibility | Generated-RTL manifest with block lifecycle sidebands separate from commit-row compare. |
| 3 | Longer live-QEMU scalar prefix | Reduced live-QEMU ELF gate without PC filters, using real marker lifecycle rather than skip-only markers. |
| 4 | Opcode expansion for scalar prefixes | QEMU-row extractor and ALU module updated only after model arithmetic/logic owner files are cited. |
| 5 | LSU prefix and memory rows | STQ/LIQ/SCB owners produce monitored memory sidebands before QEMU/DUT memory compare claims. |

## Cross-Check Policy

Use three separate sources without collapsing their roles:

- LinxCoreModel C++ owns microarchitectural timing, queues, sidecars, ROB/BROB,
  block lifecycle, and module ownership.
- QEMU owns architectural instruction execution and commit-row truth.
- Chisel generated RTL owns the implementation under test.

The reduced live-fetch RF/ALU gate may consume QEMU rows only after
`tools/chisel/frontend_fetch_rf_alu_qemu_rows.py` proves every row is inside
the implemented subset. Full QEMU-vs-DUT compare remains invalid until the
Chisel DUT emits real live frontend/backend/LSU/recovery commit rows for the
slice under test.

Every non-dry-run generated-RTL or QEMU/DUT comparison routed through the common
wrapper must preserve and inspect `crosscheck_manifest.json`.

Replay-LIQ sideband stats are observation evidence, not promotion evidence by
themselves. Do not claim nonzero replay-LIQ row mutation unless the earlier
path counters show a real sequence through wait-replay capture or relaunch,
LIQ allocation/launch, source-return request/evidence/apply, and then the row
mutation request/write counters. The R448 default smoke and 665-row R274 replay
probe both showed zero wait/replay activity, and the R449 direct-boot
`LDI`/`SDI`/`LDI` architectural store/load probe also passed comparison with
zero replay counters. Use `ReducedStoreWaitReplayToLiqPathSpec` as the current
owner-chain regression, and require a true address-ready/data-late resident
store timing stimulus before claiming live replay-LIQ promotion.
R450 shows that simply delaying split STD after STA in the replay-LIQ emitted
top is not enough: the delayed `LDI`/`SDI`/`LDI` probe still has zero replay
counters. R451 adds `ReducedStoreWaitReplayChiselPathSpec`, a test-only Chisel
composition fixture that wires `STQEntryBank` through the real resident-forward,
wait-slot, store-wakeup, relaunch-queue, and LIQ allocation modules, and
separately locks the reference timing rule that only an STA-only row at younger
load lookup captures wait-replay. The next live promotion still needs scheduler
or fixture timing that makes those R451 path counters nonzero in a generated-RTL
or QEMU/DUT evidence run. R452 turns the fixture into generated-RTL evidence:
`run_chisel_reduced_store_wait_replay_chisel_path.sh` emits the probe, builds a
Verilator executable, and writes a JSON report proving ready-store no-capture
plus split STA/STD wait capture, wake clear, relaunch queue fire, and LIQ
allocation. This still is not live QEMU/DUT promotion evidence because the
timing is harness-driven rather than produced by the reduced top scheduler.
R453 extends that fixture boundary one stage farther: the alloc path now
passes the existing `LoadRefillWakeup` input through to `LoadInflightQueue`,
the live reduced top ties it inactive, and the generated-RTL probe drives a
read-refill after LIQ allocation. The report proves the refilled row becomes a
launch candidate and accepts one gated launch into `Repick`. This is still
fixture evidence, not live replay-LIQ promotion, because both the refill and
launch arm are harness-driven.
R454 extends the executable fixture through the launched row's E4 return path:
the probe exposes fixture-owned source-return and return-ready sidebands plus
the existing E4/LHQ/resolved diagnostics. The generated-RTL report proves
`liq_e4_update=true`, `liq_lhq_record=true`, `liq_resolved=true`, and
`e4_cycles_after_launch=1`. This remains fixture evidence because launch,
refill, source-return, and return readiness are harness-driven; live promotion
still needs nonzero replay-LIQ counters through the reduced top.
R455 composes the same generated-RTL fixture with the real `LoadResolveQueue`
and existing delayed LIQ clear feedback. The report proves
`resolve_queue_push=true`, `liq_clear_resolved=true`, and
`resolve_queue_count=1`, showing that the LHQ record moves into ResolveQ and
the source LIQ row is freed after acceptance. This remains fixture evidence:
launch, refill, source-return, and return readiness are still harness-driven,
so live promotion still needs nonzero replay-LIQ counters through the reduced
top.
R456 adds the commit-style ResolveQ retire watermark to that fixture. The
report proves `resolve_queue_retired=true` and
`resolve_queue_count_after_retire=0` after driving a watermark one LSID newer
than the resolved load. This is still fixture evidence, but it closes the local
ResolveQ lifecycle from LHQ append through LIQ clear and queue retire.
R457 feeds the same ResolveQ row into a fixture-local `MDBConflictDetect`
through `LoadResolveQueue.conflictRows` while active LDQ rows are tied off.
The generated-RTL report proves `mdb_resolve_conflict=true`,
`mdb_nuke_flush=true`, `mdb_resolve_candidate_mask=1`, and
`mdb_conflict_load_lsid=3` for an older overlapping store probe. This is still
fixture evidence: the store probe and replay-return sidebands are harness
owned, and no live MDB fanout or recovery flush is published yet.
R458 converts that selected conflict record into `MDBQueueBus` and feeds a
fixture-local `MDBQueueFanout.recordIn` path. The generated-RTL report proves
`mdb_fanout_record_accepted=true`, `mdb_fanout_record_processed=true`,
`mdb_bmdb_report=true`, and `mdb_fanout_ssit_valid_mask=1`. This advances MDB
record learning and BMDB report intent under the fixture, but lookup/delete,
store wakeup, live BMDB mutation, recovery publication, and ROB nuke retirement
remain future live owners.
R459 extends the same fixture through MDB lookup fanout and store-side wakeup:
after reinforcing the learned SSIT row, the harness proves the first lookup is
suppressed by the model first-after-nuke rule and the second lookup hits,
fans out to LU/SU, matches resident STQ row zero, and emits SU wakeup. The
report records `mdb_fanout_record_reinforced=true`,
`mdb_lookup_first_suppressed=true`, `mdb_lookup_hit=true`, and
`mdb_su_wakeup=true`. This is still fixture evidence because lookup timing is
harness-owned and no live load wait-state mutation, recovery flush, or ROB
nuke retirement is published.
R460 extends the fixture through MDB delete decay and release: after the R459
lookup/wakeup proof, the harness drives three failed-wait delete commands with
the learned load PC and store wait PC. The first two deletes decay the learned
SSIT row below stall threshold while retaining it, and the third zero-weight
delete releases the row. The report records `mdb_delete_accepted=true`,
`mdb_delete_dropped_below_stall=true`, and `mdb_delete_released=true`. This is
still fixture evidence because the failed-wait timer and delete producer are
harness-owned, and no live LDQ oldest-wait wakeup path is publishing
`delete_lu_mdb_q` yet.
R461 adds the standalone MDB LU-hit wait planner before wiring live mutation.
`LoadReplayMdbLookupWaitPlan` scans current LIQ rows for exactly one scalar
`Repick` row matching the MDB load `(BID, LSID)` and exposes
`waitIntentValid`, but it emits a native row-mutation request only after a
future SU/store-row owner supplies the resolved STQ index and store LSID. This
keeps the model `updateMDBInfo` load-side intent separate from Chisel's native
`LoadStoreForwardWait` identity, where BID/PC alone are not enough to drive
store-unit wakeup matching.
R462 composes that planner into the generated replay fixture and proves the
guarded negative case: the existing fixture performs MDB lookup after the
source replay row has resolved, pushed ResolveQ, and cleared out of LIQ. The
LU/SU lookup still hits and SU supplies store row zero, but
`mdb_lookup_wait_plan_candidate_mask=0` and
`mdb_lookup_wait_plan_no_target=true`, so no native LIQ mutation request is
emitted for a stale ResolveQ-only load. The positive proof now needs a learned
SSIT lookup against a later dynamic load that remains resident in LIQ/Repick.
R463 wires the planner request into the generated fixture's existing
`ReducedLoadReplayLiqAllocPath` row-mutation inputs and adds a fixture
live-lookup selector. The harness proves a second dynamic load can be resident
in LIQ row 1 as `Repick` when the learned MDB lookup returns:
`mdb_lookup_wait_plan_live_candidate_mask=2`,
`mdb_lookup_wait_plan_live_target_index=1`,
`mdb_lookup_wait_plan_request=true`, and
`mdb_lookup_wait_plan_bridge=true`. The request intentionally stops at LIQ
control (`mdb_lookup_wait_plan_control_blocked=true`) because this fixture has
not yet supplied the source-return evidence required for a safe row write. The
next positive proof should feed or synthesize that evidence and then require
`rowMutationWriteEnable=true` plus the target row moving back to Wait with a
valid native wait-store payload.

## XiangShan Flow Reference

Assumption: the request's "Xianghan" refers to the OpenXiangShan GitHub flow.
Use it as a process reference, not as a LinxCore dependency:

- [OpenXiangShan/XiangShan](https://github.com/OpenXiangShan/XiangShan)
- [OpenXiangShan/difftest](https://github.com/OpenXiangShan/difftest)
- [XiangShan DiffTest documentation](https://docs.xiangshan.cc/zh-cn/latest/tools/difftest/)

Transferable practices:

- keep Chisel generation, Verilator build, simulator run, and reference compare
  as separate targets;
- bind typed top-level events for commit, trap, memory, and recovery rather
  than treating emitted RTL as sufficient evidence;
- compare bounded DUT streams against a reference and capture the first
  mismatch window;
- keep waveform and event logging opt-in and bounded.

LinxCore adaptation:

- use Linx-native `CommitTraceRow` and `LC-IF-CHISEL-XCHK-*` contracts;
- normalize through `tools/chisel/trace_schema_adapter.py`;
- compare through `tools/trace/crosscheck_qemu_linxcore.py`;
- do not import RISC-V-specific DiffTest bundles or assumptions.

## Skill-Evolve Decision

Every packet closes with exactly one evidence line:

```text
skill-evolve: update <skill-list> (<material reusable finding>)
skill-evolve: no-update (<why this is local or already documented>)
```

Use `update` only for reusable findings that later agents must know before
touching another module:

- a new ROB/BROB/flush/trace invariant;
- a new mandatory gate, command, env var, or manifest artifact;
- a recurring QEMU/model/Chisel first-divergence workflow;
- a superproject or skill-submodule maintenance rule;
- a cross-owner ready/valid or sidecar preservation rule.

Use `no-update` for module-local prose, one-off test vectors, wording cleanup,
or behavior already present in `linx-core`, `linx-model`, `linx-qemu`, or
`linx-superproject`.

When updating skills, patch canonical `skills/linx-skills` first, validate,
install the canonical copy, then repin the superproject after the skill commit.

```bash
python3 /Users/zhoubot/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  /Users/zhoubot/linx-isa/skills/linx-skills/linx-core
python3 /Users/zhoubot/linx-isa/skills/linx-skills/scripts/check_skill_change_scope.py \
  --repo-root /Users/zhoubot/linx-isa/skills/linx-skills --base origin/main
```

## Agent Prompt Template

```markdown
Packet: <module or harness>

Owned files:
- `<path>`

Read first:
- `docs/chisel/skill-evolve-loop.md`
- `docs/chisel/development-loop.md`
- `docs/chisel/agent-loop.md`
- `<module Markdown page>`
- `<LinxCoreModel owner files>`
- `<QEMU or cross-check owner files>`

Model invariants:
- `<file>::<symbol> -> <testable invariant>`

Target:
- `<exact Chisel, harness, test, or doc change>`

Gates:
- `<focused unit gate>`
- `<affected generated-RTL/QEMU gate>`
- `<manifest path to inspect>`

Closeout:
- update module docs and packet ledger
- classify any divergence owner
- report `skill-evolve: update|no-update`
- commit only owned files
```
