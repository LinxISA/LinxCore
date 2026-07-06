# LinxCore Integrated Development Flow

Date: 2026-07-06

## Purpose

This is the short launch surface for LinxCore Chisel development. Use it before
opening the long packet ledger in `docs/chisel/agent-loop.md`.

The goal is to use the existing Linx toolchain stack as a staged proof system:
ISA and model sources define intent, the compiler produces real workload ELFs,
QEMU proves architectural row streams, Chisel generated RTL proves DUT behavior,
LinxCoreModel proves model convergence, and the superproject gates prove that
the change still works across repos.

## Current Handoff

The next Chisel packet should start from the R550 replay-return evidence, not
from another broad CoreMark scan. The reduced frontend/rename/scalar
execute/ROB/block-marker/store/STQ/SCB path is mature enough for the current
reduced top, and the generated-RTL/QEMU comparator infrastructure is producing
usable manifests. The active blocker is narrower: replay-LIQ source-returned
rows now publish LRET payloads and drain the FIFO, and the post-FIFO
`LoadReplayReturnIexDataCandidate` now reaches model-equivalent
`IEX::setMemData` admission. R547 holds ROB deallocation for outstanding
replay-LIQ load RIDs from LIQ allocation until LRET FIFO drain, so the replay
fixture records `lret_iex_data_rob_row_valid=3`,
`lret_iex_data_set_mem_data_valid=3`,
`lret_iex_data_rob_row_blocked_by_free=0`, and
`lret_shadow_free_after_prior_commit=0`. R548 enables the E4 residency slot to
advance into W1 only when W1 is empty, so the replay fixture now records
`lret_residency_advance_valid=2`, `lret_w1_slot_accepted=2`,
`lret_w2_slot_accepted=1`, `lret_w2_slot_occupied=74`, and
`w2_atomic_evidence_valid=75`. The next owner is no longer ROB row-status
lifetime, setMemData admission, IEX insert, residency write, or W2 slot
evidence; it is W2 atomic request promotion, because
`w2_atomic_request_active=0`, `w2_atomic_blocked_by_request_disabled=111`,
`w2_side_effect_ready=0`, `w2_row_fill_candidate_valid=0`, and
`w2_lifecycle_ready=0`. R549 splits the aggregate request-disabled counter:
`w2_atomic_blocked_by_mode_disabled=0`, `w2_atomic_blocked_by_policy=111`,
`w2_atomic_blocked_by_no_side_effect_sink=74`,
`w2_atomic_blocked_by_no_evidence=36`, and the clear/row-fill/lifecycle
policy blockers remain zero. The next owner is W2 side-effect sink readiness
and live enable for the resident returned-load slot, before row-fill or
lifecycle policy promotion. R550 changes the atomic prereq snapshot to sample
pre-request sink capacity from the existing W2 resolve/writeback/wakeup
`*Armed` signals, while actual side-effect mutation remains gated by
`LoadReplayReturnPipeW2SideEffectLiveControl`. The replay-loop fixture still
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The v21 sideband report records the ordered blocker movement:
`w2_atomic_blocked_by_no_side_effect_sink=7`,
`w2_atomic_blocked_by_no_clear_commit=67`,
`w2_atomic_blocked_by_no_row_fill_candidate=0`,
`w2_atomic_blocked_by_no_lifecycle_row=0`, `w2_clear_intent=0`,
`w2_clear_commit_ready=0`, `w2_side_effect_fire_complete=0`, and
`w2_atomic_request_active=0`. R551 keeps the live clear-commit guard as the
post-fire coherence diagnostic, but feeds the atomic prerequisite snapshot with
pre-request clear/ROB capacity: side-effect sink capacity, a valid resident
slot RID, and an idle replay ROB-complete sink. The replay-loop fixture still
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The v21 sideband report records the ordered blocker movement:
`w2_atomic_blocked_by_no_clear_commit=0`,
`w2_atomic_blocked_by_no_row_fill_candidate=67`,
`w2_atomic_blocked_by_no_side_effect_sink=7`,
`w2_atomic_blocked_by_no_lifecycle_row=0`, `w2_clear_intent=0`,
`w2_clear_commit_ready=0`, `w2_row_fill_candidate_valid=0`,
`w2_side_effect_fire_complete=0`, and `w2_atomic_request_active=0`. The next
owner is the pre-request row-fill candidate path, not clear-commit capacity,
side-effect sink capacity, lifecycle clear, or generic W2 evidence. R552 adds
sideband counters for the commit-row trace-source and candidate prerequisites.
The replay-loop fixture still passes with 9 compared rows, zero mismatches, and
zero QEMU/DUT CBSTOP rows. The new counters show `lret_w2_slot_source_trace_valid=74`,
`w2_commit_row_trace_source_rob_lookup_row_valid=0`,
`w2_commit_row_trace_source_rob_lookup_instruction_valid=0`,
`w2_commit_row_trace_source_blocked_by_no_metadata=74`,
`w2_commit_row_trace_source_blocked_by_no_source_trace=0`,
`w2_commit_row_candidate_blocked_by_no_metadata=74`, and
`w2_commit_row_candidate_blocked_by_no_source_trace=0`. R553 drives the
read-only ROB commit-trace lookup from the LRET drain RID, latches the
instruction raw/length provider keyed by RID, and feeds that metadata when the
same returned load reaches W2. The replay-loop fixture still passes with 9
compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The sideband
report records `w2_commit_row_trace_source_rob_lookup_instruction_valid=3`,
`w2_commit_row_trace_source_instruction_ready=33`,
`w2_commit_row_trace_source_source_ready=33`,
`w2_commit_row_fill_candidate=33`, `w2_row_fill_candidate_valid=33`,
`w2_row_fill_prerequisites_ready=0`, and `w2_lifecycle_ready=0`. The next owner
is row-fill prerequisite/lifecycle readiness, not ROB instruction metadata,
source-trace provenance, size/destination shape, clear capacity, or side-effect
sink capacity. R554 adds sideband splits for the existing row-fill and
lifecycle blocker outputs. The replay-loop fixture still passes with 9 compared
rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The split counters show
`w2_lifecycle_candidate_valid=74`, `w2_lifecycle_slot_identity_valid=74`,
`w2_lifecycle_resolved_row_match=0`,
`w2_lifecycle_blocked_by_no_resolved_row=74`,
`w2_lifecycle_blocked_by_multiple_resolved_rows=0`,
`w2_row_fill_blocked_by_request_disabled=33`, and
`w2_row_fill_blocked_by_no_side_effect_commit=33`. The next owner is the LIQ
resolved-row lifecycle match for the returned W2 load, not identity validity,
duplicate matching, row-fill candidate formation, or sideband schema. R555
keeps return-complete replay rows resident through the W2 lifecycle match and
retires the corresponding ResolveQ row on accepted lifecycle clear. The
replay-loop fixture passes with 9 compared rows, zero mismatches, and zero
QEMU/DUT CBSTOP rows. Its sideband report records
`w2_lifecycle_resolved_row_match=6`, `w2_lifecycle_row_clear_ready=6`,
`w2_lifecycle_ready=3`, `w2_lifecycle_blocked_by_no_resolved_row=0`,
`w2_row_fill_enable=3`, and `w2_atomic_request_active=3`. R556 adds harness
and validator coverage for the W2 promotion/refill/slot-replace/advance
owners and reruns the same replay-loop fixture. The comparator still passes
with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The
new sideband counters prove the current fixture reaches live side-effect
completion, clear intent, row-fill, lifecycle, promotion, live clear,
same-cycle refill readiness, and future-advance selection:
`w2_side_effect_fire_complete=3`, `w2_clear_intent=3`,
`w2_clear_commit_ready=3`, `w2_promotion_live=3`,
`w2_promotion_live_clear=3`, `w2_promotion_advance_live=3`,
`w2_refill_ready_same_cycle_ready=3`, and `w2_advance_enable=111`.
Invalid promotion/refill/advance evidence stays zero. The same fixture does
not yet exercise a same-cycle W2 storage replacement because
`w2_slot_replace_same_cycle_eligible=0` and
`w2_slot_replace_same_cycle_ready=0`, while empty/future write acceptance does
fire. The next owner is a narrow same-cycle W2 slot replacement proof, likely
by constructing or selecting a replay-return sequence with a valid W1 write
candidate while W2 live clear fires, not replay-LIQ row identity, ResolveQ
drain, side-effect commit, or promotion-control observability. R557 adds
overlap counters that combine W1 advance-candidate, W2 occupancy, clear intent,
live clear, and advance-valid evidence in the generated-RTL harness. The same
fixture still passes with 9 compared rows and zero mismatches, and it proves
the replacement gap is stimulus phasing: `w2_slot_replace_live_clear_without_w1_candidate=3`,
while `w2_slot_replace_overlap_candidate_occupied=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. The next packet should add or select a
denser replay-return fixture that keeps W1 occupied while W2 live clear fires
before changing W2 storage or broadening to CoreMark.

Use this packet shape first:

```text
Packet: replay-LIQ LRET W2 same-cycle slot replacement
Owner lane: rtl/LinxCore/chisel LSU replay-LIQ
Files allowed: replay-return fixture builder/wrapper, W1/W2 occupancy/advance
  sideband expectations needed to create back-to-back returned-load occupancy,
  focused W2/top specs, and module docs; change W2 storage only after the
  fixture proves nonzero same-cycle replacement eligibility
Source evidence: LinxCoreModel IEX::setMemData and LDAPipe/load-return W2
  handling after returned-load pipe insertion, plus LDQ resolved-row movement
Expected first gate: focused W2 slot/advance coverage proving same-cycle live
  clear plus W1 write candidate can replace the resident W2 entry without
  losing the consumed row's side effects
Promotion gate: R557 replay-loop fixture, or a minimally extended fixture,
  through
  run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh with v21 sideband
  inspection requiring nonzero setMemData, IEX insert, residency, W1/W2 slot,
  W2 evidence, W2 slot source trace, W2 policy blocker split, zero clear-commit
  policy blocks, nonzero ROB instruction metadata evidence, nonzero row-fill
  candidate evidence, valid lifecycle slot identity, nonzero lifecycle
  resolved-row match, nonzero row-fill enable, nonzero W2 promotion/live-clear
  and refill/advance counters, nonzero `live_clear_without_w1_candidate` in
  the old fixture, and nonzero same-cycle slot replacement evidence in the new
  fixture
Do not run: long CoreMark, marker-row scaling, or superproject closure until
  same-cycle W2 replacement has a focused generated-RTL/QEMU proof
Do not change: LRET FIFO capacity, return-data extraction, ROB deallocation
  holdoff, lane/TLOAD/final metadata, ROB metadata latch, commit-row compare
  policy, or replay-LIQ lifecycle ownership before same-cycle replacement
  evidence exists
First-divergence owner if the gate fails: fixture/stimulus until the sideband
  report proves a W1 candidate exists while W2 is occupied and live clear fires;
  only then move ownership to Chisel W2 slot replacement or W1/W2 advance
  ordering
Closeout evidence: unit log, generated-RTL/QEMU manifest, sideband counters,
  module doc row, agent-loop row, and skill-evolve decision
```

## Toolchain Roles

| Lane | Owns | Primary evidence | Hand off when |
|---|---|---|---|
| `isa/` and `docs/isa/` | Normative opcode, register, block, trap, CSR, and memory contracts | ISA manual row, generated catalog, encoding metadata | The behavior is not specified or the generated catalog disagrees |
| `compiler/llvm` and `compiler/ptoas` | Workload ELF generation, assembly, disassembly, relocation, call/return lowering | Compile log, object/ELF, MC tests, disassembly | Source is valid but compile, assemble, link, or relocation fails |
| `emulator/qemu` | Executable architectural reference for direct-boot and Linux/runtime rows | QEMU log, JSONL trace, first architectural row | ELF fails before Chisel sees a valid reference row |
| `model/LinxCoreModel` | Cycle/model behavior, block/ROB/BROB/LSU ownership details, final model convergence | Source reference, `gfsim -f <elf>` log after QEMU pass | QEMU passes but model loops, mismatches, or times out |
| `rtl/LinxCore/chisel` | Chisel RTL, typed monitors, generated-RTL harnesses, DUT row compare | Unit tests, Verilator lint, xcheck manifest, first DUT mismatch | QEMU/reference rows are valid and generated RTL diverges |
| `tools/chisel` | Row normalization, memory fixtures, trace adapters, Chisel wrappers | Adapter self-test, dry-run, `crosscheck_manifest.json` | Schema, manifest, or row filtering is wrong while DUT/reference rows are valid |
| `tools/bringup` and superproject tests | Cross-repo smoke, benchmark, AI workload, Linux/runtime promotion | flow report JSON, PR/nightly profile, strict closure log | A leaf lane is green but integrated bring-up regresses |

## Optimized Loop

1. Pick one packet.
   Name the owner, files, source evidence, expected first gate, and promotion
   gate before editing. If the packet touches two owners, split it unless the
   interface contract itself is the change.
2. Record SHAs once.
   Capture the superproject, `rtl/LinxCore`, `model/LinxCoreModel`,
   `emulator/qemu`, and `compiler/llvm` SHAs when those lanes are involved. Do
   not repeat status scans unless the worktree, fetched refs, or generated
   artifacts changed.
3. Read only live context.
   Use this file, `docs/chisel/development-loop.md`, the assigned module spec,
   the nearest source files in LinxCoreModel or pyCircuit, and the latest
   relevant rows in `docs/chisel/agent-loop.md`.
4. Design the harness boundary first.
   Keep the DUT top, test harness, and test driver separate:
   - DUT top emits typed architectural or stage-owner rows.
   - Test harness owns clock/reset, memory image loading, trace feeding, and
     optional debug enable.
   - Test driver owns JSONL, manifests, compare policy, and first-mismatch
     reporting.
5. Edit the smallest owner.
   Update the module Markdown with the contract and evidence shape before or
   with the RTL change. Do not add permanent debug IO for one failing test;
   prefer typed monitor rows or optional probe-style diagnostics.
6. Stop at first failing tier.
   Classify the first divergence before editing again. Do not run the wider
   gate stack after a smaller required gate has already failed.
7. Promote only after a smaller pass.
   Increase from module unit evidence to generated RTL, QEMU rows, model, and
   superproject closure in order. Long CoreMark, Linux, and AI workload gates
   are promotion evidence, not the edit loop.
8. Close the packet.
   Record commands, report paths, first mismatch if any, SHAs, and whether the
   finding updates `linx-core` skill guidance. If reusable, update the skill;
   otherwise close with `skill-evolve: no-update`.

## Gate Ladder

| Tier | Use when | Typical commands from `rtl/LinxCore` | Exit condition |
|---|---|---|---|
| 0. Wrapper and static sanity | Any Chisel, adapter, or fixture edit | `bash tools/chisel/build_chisel.sh`; touched Python adapter self-tests; relevant `--dry-run` wrapper | Build and wrapper selection work without entering a broad compare |
| 1. Module contract | One module or bundle owner changed | `bash tools/chisel/run_chisel_tests.sh --only <Module>`; `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` for ROBID/ROB semantics | The assigned module contract is green |
| 2. Adjacent generated RTL | Composition, top, monitor, or trace-owner changed | `bash tools/chisel/run_chisel_verilator_lint.sh`; nearest `run_chisel_*_xcheck.sh` wrapper | Generated RTL elaborates and the nearest harness compare is green |
| 3. QEMU row replay | The packet claims architectural row equivalence | `bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh ...`; `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh ...` | `crosscheck_manifest.json` shows zero mismatches and records raw/arch row counts |
| 4. Model/toolchain convergence | Workload behavior or model parity is part of the claim | From superproject: `python3 tools/bringup/run_ai_workload_flow.py --profile smoke ...`; `python3 tools/bringup/run_benchmark_linux_flow.py --profile pr ...`; `model/LinxCoreModel/bin/gfsim -f <elf>` only after the same ELF passed QEMU | QEMU-passing ELF also passes the selected model or workload profile |
| 5. Closure | PR, submodule pin, or cross-lane behavior changed | `tests/test_stage_connectivity.sh`; `tests/test_runner_protocol.sh`; `tests/test_cosim_smoke.sh`; `tests/test_opcode_parity.sh`; `tests/test_trace_schema_and_mem.sh`; `tests/test_rob_bookkeeping.sh`; `tests/test_block_struct_pyc_flow.sh`; superproject strict closure | All required cross-repo gates for the claim pass |

Scale row windows in order. Start with a tiny deterministic fixture or 16 to 64
architectural rows, then 512, 4k, and only then long CoreMark or Linux windows.
For current CoreMark-style direct-boot ELFs, pass explicit QEMU memory such as
`-m 1280M` when the wrapper invokes QEMU.

## First Divergence Ownership

| First failing fact | Owner |
|---|---|
| Source or benchmark harness is invalid before compile | source/benchmark |
| Valid source fails compile, assemble, link, relocation, or disassembly | compiler |
| ELF fails in QEMU before a valid row stream exists | QEMU |
| QEMU row stream is valid but the Chisel generated-RTL row differs | Chisel |
| Reference and DUT rows are valid but schema, filtering, row count, or manifest is wrong | adapter |
| QEMU passes but `gfsim` fails, loops, times out, or computes the wrong digest | model |
| Leaf lanes pass but pinned SHAs, submodule state, Linux/runtime, or bring-up profile fails | superproject |

Do not repair a downstream lane before the upstream lane has produced a valid
artifact. A QEMU-invalid ELF is not a Chisel bug. A QEMU-passing, DUT-mismatched
row is not a compiler bug until the row normalization has been checked.

## Harness Rules

- Keep benchmark-specific address, memory, and compare setup in harness or
  driver code, not in the DUT.
- Emit typed rows for commit, trap, memory, redirect, block, and owner-specific
  diagnostics. Do not scrape waveforms as the primary compare interface.
- Generate or normalize traces once per packet and reuse the artifact for
  syntax-only Chisel iterations. Regenerate when the ELF, QEMU, adapter, or
  compare policy changes.
- Preserve `crosscheck_manifest.json` for every generated-RTL or QEMU/DUT
  compare. The manifest is the evidence bundle, not a side effect.
- Waves are opt-in failure evidence. Do not enable them in the default inner
  loop.
- pyCircuit remains a parity oracle until the Chisel path has equivalent
  module, generated-RTL, QEMU-row, and model evidence.

## Agent Packet Template

Use this shape when launching a LinxCore packet:

```text
Packet:
Owner lane:
Files allowed:
Source evidence:
Expected first gate:
Promotion gate:
Do not run:
First-divergence owner if the gate fails:
Closeout evidence:
```

Current example:

```text
Packet: replay-LIQ LRET W2 row-fill candidate readiness
Owner lane: rtl/LinxCore/chisel
Files allowed: W2 commit-row candidate/trace-source, row-fill enable control,
  ROB trace lookup provider, W2 request evidence wiring, focused specs, module
  docs
Source evidence: LinxCoreModel IEX::setMemData and load-return W2 handling
Expected first gate: focused W2 row-fill candidate readiness coverage
Promotion gate: R551 replay-loop generated-RTL/QEMU fixture plus v21 sideband
Do not run: long CoreMark or marker-row scaling before W2 row-fill candidate readiness
First-divergence owner if the gate fails: Chisel W2 commit-row candidate /
  trace-source readiness unless v21 sideband reporting is wrong
Closeout evidence: unit log, xcheck manifest, sideband counters, module doc
  row, skill-evolve decision
```

## Speed Rules

- No raw `sbt`; use `tools/chisel/chisel_env.sh` through repo wrappers.
- No repeated broad `git status` loops inside a packet.
- No full closure in the inner edit loop.
- No widening row windows until the previous smaller window is green.
- No permanent debug ports for temporary diagnosis.
- No claiming replacement evidence from a unit test alone.
- No model run on an ELF that has not passed QEMU in the same packet.
- No submodule pin or PR closeout without the superproject evidence row that
  matches the claim.
