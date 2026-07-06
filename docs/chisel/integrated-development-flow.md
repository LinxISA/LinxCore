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

The next Chisel packet should start from the R547 replay-return evidence, not
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
`lret_shadow_free_after_prior_commit=0`. The next owner is no longer ROB
row-status lifetime; it is the post-insert return-pipe/W2 request path, because
resolve, lane, TLOAD, final metadata, IEX insert, and residency candidates are
all nonzero while `w2_atomic_request_active=0` and
`w2_atomic_evidence_valid=0`.

Use this packet shape first:

```text
Packet: replay-LIQ LRET W2 atomic request evidence
Owner lane: rtl/LinxCore/chisel LSU replay-LIQ
Files allowed: LoadReplayReturnPipeResidency*, W1/W2 replay-return pipe
  modules, W2 atomic request/prereq/evidence wiring, focused W2 specs, module
  docs, and sideband validator updates only if new evidence fields are required
Source evidence: LinxCoreModel IEX::setMemData and LDAPipe/load-return W2
  handling after returned-load pipe insertion
Expected first gate: focused return-pipe/W2 request coverage proving a
  residency candidate can promote into W2 request evidence without enabling
  unrelated RF/writeback or ready-table side effects
Promotion gate: R547 replay-loop fixture through
  run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh with v20 sideband
  inspection requiring nonzero setMemData, IEX insert, residency, and the
  newly repaired W2 request/evidence counter
Do not run: long CoreMark, marker-row scaling, or superproject closure until
  W2 request evidence is live in the reduced replay-loop fixture
Do not change: LRET FIFO capacity, return-data extraction, ROB deallocation
  holdoff, lane/TLOAD/final metadata, or commit-row compare policy before W2
  request evidence exists
First-divergence owner if the gate fails: Chisel return-pipe residency/W2
  atomic request gating unless the v20 sideband report misreports generated
  signals
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
Packet: replay-LIQ LRET W2 atomic request evidence
Owner lane: rtl/LinxCore/chisel
Files allowed: return-pipe residency, W1/W2 replay-return modules, W2 request
  evidence wiring, focused specs, module docs
Source evidence: LinxCoreModel IEX::setMemData and load-return W2 handling
Expected first gate: focused W2 request/evidence coverage
Promotion gate: R547 replay-loop generated-RTL/QEMU fixture plus v20 sideband
Do not run: long CoreMark or marker-row scaling before W2 request evidence
First-divergence owner if the gate fails: Chisel return-pipe residency/W2
  atomic request gating unless v20 sideband reporting is wrong
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
