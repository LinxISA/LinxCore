# LinxCore v0.3 Superscalar Bring-up Program (Strict Cross-Gate, Decision-Complete)

## Summary

Goal: make LinxCore a fully functional superscalar processor in pyCircuit under a strict, blocking, cross-module gate regime.

- Closure definition locked: v0.3 functional closure with U+S+MMU+interrupts, dual-lane (`pin` + `external`) parity, and hard required gates.
- Cadence locked: gate-based phases (no calendar dates), module-agent ownership, per-PR superproject repins, nightly full matrix.
- Canonical source of truth locked: superproject-pinned submodules; standalone `/Users/zhoubot/LinxCore` is mirror-only.

## Locked Decisions

- Architecture scope: v0.3 closure.
- CPU scope: U+S+MMU+interrupts.
- Runtime policy: `pin` + `external` both required.
- Gate policy: hard mandatory set (PR) + nightly full mandatory; PR budget target 30m.
- KPI: per-module testbench gate + functional closure + forward-progress floor.
- Performance floor: fail if regression >10% vs locked baseline.
- Docs authority: LinxArch canonical docs, blocking docs gate.
- Waivers: milestone waivers, phase-bound expiry, evidence required.
- Evidence pack: gate report + SHA manifest + traces.
- Ownership: split by module agents + integration owner.
- Superproject bumps: per-PR repin after required PR matrix passes.
- Trace evolution: SemVer style; breaking schema increments major.
- pyCircuit API policy: strict interface contracts + gate-enforced compatibility.

## Planned File/Artifact Targets

### Architecture docs and nav

- `/Users/zhoubot/linx-isa/docs/architecture/README.md`
- `/Users/zhoubot/linx-isa/docs/architecture/linxcore/overview.md` (new)
- `/Users/zhoubot/linx-isa/docs/architecture/linxcore/microarchitecture.md` (new)
- `/Users/zhoubot/linx-isa/docs/architecture/linxcore/verification-matrix.md` (new)
- `/Users/zhoubot/linx-isa/docs/architecture/linxcore/interfaces.md` (new)
- `/Users/zhoubot/linx-isa/mkdocs.yml`

### Multi-agent strict-gate governance

- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/manifest.yaml`
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/waivers.yaml`
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/architecture_docs.md` (new)
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/linxcore_rtl.md` (new)
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/pycircuit_model.md` (new)
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/testbench_verif.md` (new)
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/trace_linxtrace.md` (new)
- `/Users/zhoubot/linx-isa/docs/bringup/agent_runs/checklists/integration_release.md` (extend IDs)

### Gate orchestration and consistency

- `/Users/zhoubot/linx-isa/tools/bringup/run_runtime_convergence.sh`
- `/Users/zhoubot/linx-isa/tools/regression/strict_cross_repo.sh`
- `/Users/zhoubot/linx-isa/tools/bringup/check_gate_consistency.py`
- `/Users/zhoubot/linx-isa/tools/bringup/check_multi_agent_gates.py`

### Optional new validators (planned)

- `/Users/zhoubot/linx-isa/tools/bringup/check_linxcore_arch_contract.py`
- `/Users/zhoubot/linx-isa/tools/bringup/check_trace_semver_compat.py`
- `/Users/zhoubot/linx-isa/tools/bringup/check_pycircuit_interface_contract.py`

## Planned Public APIs / Interfaces / Types

### LinxTrace contract

- Keep `linxtrace.v1` stable for additive changes.
- Breaking changes require major bump (`v2`) and compatibility gate.
- Enforce schema/version compatibility in superproject gate scripts and viewer sync lint.

### pyCircuit-LinxCore interface contract

- Introduce explicit versioned contract artifact for emitted fields/params consumed by LinxCore TB and trace tooling.
- Add strict compatibility checker gate.
- Breaking changes must bump interface version and include migration notes.

### Gate policy schema

- Extend waiver schema with phase binding metadata while preserving `expires_utc`.
- Enforce phase-expiry rules in validator.

## Strict Cross-Gate Matrix (Blocking)

| Domain | PR mandatory (<30m) | Nightly mandatory | Owner |
| --- | --- | --- | --- |
| Architecture | `check26` + LinxCore arch-contract lint + `mkdocs` strict | docs link/completeness deep audit | `arch` |
| LinxCore RTL | stage/connectivity, opcode parity, runner protocol, trace schema/mem smoke, cosim smoke | CoreMark crosscheck 1000, CBSTOP inflation guard, konata/linxtrace extended | `linxcore` |
| Testbench | ROB bookkeeping + protocol + block-struct pyc flow smoke | extended stress seeds + replay/flush edge suites | `testbench` |
| pyCircuit | `run_linx_cpu_pyc_cpp.sh` smoke + `run_linx_qemu_vs_pyc.sh` | `run_examples.sh`, `run_sims.sh`, nightly sims, API contract audit | `pycircuit` |
| LinxTrace | contract sync lint + sample trace lint | compatibility migration checks + viewer parser checks | `trace` |
| Integration | `strict_cross_repo.sh` + gate consistency + multi-agent runtime closure | full dual-lane convergence + performance floor checks | `integration` |

## Cross Verification Matrix (All-Module Contracts)

- ISA/LinxArch <-> LinxCore decode/commit: opcode parity + trap semantics + block rules.
- LinxCore commit stream <-> QEMU reference: cosim smoke (PR), CoreMark 1000 crosscheck (nightly).
- pyCircuit commit stream <-> QEMU reference: trace diff + schema validation.
- LinxCore trace emitter <-> LinxTrace linter <-> LinxCoreSight parser: contract sync and version checks.
- Testbench invariants <-> pipeline behavior: ROB/order/flush/nuke correctness + forward-progress checks.
- Superproject report <-> SHA manifest <-> lanes: required gate parity and artifact consistency.

## Phase Plan (Gate-Based, No Fixed Dates)

### Phase G0 - Governance and gate wiring

- Add new agents/checklists/assignments in manifest.
- Wire new gate keys into runtime orchestration.
- Enforce blocking docs gate and module gate ownership.

Exit criteria:

- static multi-agent validator passes with new domains.
- required gate rows exist for Architecture/LinxCore/pyCircuit/Testbench/Trace in both lanes.

### Phase G1 - LinxArch contract completion

- Fill LinxCore architecture contracts in LinxArch (pipeline, hazards, privilege, MMU, interrupts, memory ordering, trace/API contracts).
- Add verification matrix doc with gate-to-contract traceability.

Exit criteria:

- architecture docs gate green.
- every required test/gate maps to a documented contract row.

### Phase G2 - LinxCore functional superscalar closure

- Close v0.3 superscalar correctness under current structural limits.
- Keep precise traps/flush/block semantics.
- Mandatory PR gates from `/Users/zhoubot/linx-isa/rtl/LinxCore/tests/README.md` pass.

Exit criteria:

- zero required LinxCore gate failures in both lanes.
- progress/forward-progress checks pass for branch/flush/load-miss/replay paths.

### Phase G3 - pyCircuit API + flow hardening

- Implement required pyCircuit API improvements behind strict interface controls.
- Lock compatibility checks and promotion path from experimental to stable.

Exit criteria:

- pyCircuit PR gates pass in both lanes.
- interface contract checker blocks unversioned breaks.

### Phase G4 - Testbench + LinxTrace hard closure

- Harden testbench scenario matrix (privilege transitions, MMU faults, IRQ entry/return, replay/redirect edge cases).
- Enforce LinxTrace SemVer and emitter/viewer sync gates.

Exit criteria:

- trace and testbench required gates all green.
- schema/version compatibility artifacts generated and verified.

### Phase G5 - Integrated dual-lane closure + continuous repin

- Run full nightly strict matrix with all required gates.
- Enforce per-PR repin workflow only after PR mandatory matrix passes.

Exit criteria:

- both lanes green with identical required gate sets.
- evidence pack complete (report + SHAs + traces) for closure run.
- no active waivers at phase promotion boundary.

## Required Test Scenarios (Acceptance Set)

- Privilege: U->S trap entry, `SRET` return, CSR side effects validated.
- MMU: valid translation, permission fault, page fault trap arguments.
- Interrupts: timer IRQ delivery, nested/edge timing around block boundaries.
- Branch/Block: `BSTART`/`BSTOP` correctness, recovery-to-`BSTART` legality, invalid-target precise trap.
- Memory: load/store forward correctness, miss replay, ordering invariants.
- Superscalar: multi-issue/commit ordering, ROB bookkeeping, flush/nuke correctness.
- Trace: schema validity, `contract_id` sync, SemVer compatibility on evolution.
- Integration: AVS + CoreMark + Linux boot remain green with <=10% performance regression cap.

## Waiver and Gatekeeping Policy

- Waivers remain explicit, owner-bound, issue-linked, and evidence-linked.
- Waivers are allowed only within active milestone phase and must expire at phase boundary.
- Phase promotion requires waiver cleanup (renewal or removal) and revalidation.
- Failure SLA is best-effort, but merge remains blocked by required failing non-waived gates.

## Superproject Repin Workflow (Strict)

1. Module agent lands change on module branch.
2. Run module PR mandatory gates.
3. Update submodule pointer in `/Users/zhoubot/linx-isa` on integration branch.
4. Run superproject PR mandatory matrix (`pin` + `external`).
5. Merge repin only if required gates are green and evidence pack is complete.
6. Nightly full matrix confirms continued closure.

## Assumptions and Defaults

- No fixed wall-clock schedule; phase progression is gate-readiness based.
- Canonical development is inside `/Users/zhoubot/linx-isa` submodules.
- `/Users/zhoubot/LinxCore` stays mirror-only during this program.
- Existing strict bring-up infrastructure remains the canonical report source.
- Critical-formal + simulation split is sufficient for v0.3 closure depth.
