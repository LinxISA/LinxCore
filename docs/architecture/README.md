# LinxCore Architecture Docs

This directory is the canonical source-of-truth for LinxCore architecture and
interface documentation.

## Authority contract (LC-ARCH-DOC-001)

The Markdown pages own normative semantics. The machine-readable
`microarchitecture-contract.json` indexes each contract definition, stage
family, implementation owner, top-shell role, scenario, and migration input.
It must not introduce behavior that is absent from the owning Markdown page.

Run the authority and coverage gates from an initialized superproject checkout:

```bash
bash tests/test_microarchitecture_contract.sh
python3 docs/architecture/test_check_v0581_consistency.py
python3 docs/architecture/check_v0581_consistency.py
python3 docs/architecture/check_v0581_consistency.py --authority-root ../..
```

## Contract pages

- `overview.md`
- `ifu.md` (canonical I-SIDE/B-SIDE IFU architecture)
- `NAMING.md` (parameter and stage-name governance; not separately published)
- `microarchitecture.md`
- `microarchitecture-contract.json` (machine-readable ownership and
  verification index; not a second prose specification)
- `mechanism-intake.json` (historical source disposition and promotion
  evidence; not a second prose specification)
- `rtl-adapters/*.json` (implementation evidence and declared promotion gaps;
  not a second prose specification)
- `conformance/event-schema.json` and `conformance/scenarios.json` (normalized
  owner-boundary comparison schema and shared invariant vectors)
- `interfaces.md`
- `verification-matrix.md`
- `module-catalog.md`
- `pipeline-stage-catalog.md`

The six publication pages below are mirrored into the superproject paths:

- `docs/architecture/linxcore/overview.md`
- `docs/architecture/linxcore/microarchitecture.md`
- `docs/architecture/linxcore/interfaces.md`
- `docs/architecture/linxcore/verification-matrix.md`
- `docs/architecture/linxcore/module-catalog.md`
- `docs/architecture/linxcore/pipeline-stage-catalog.md`

`ifu.md` is the canonical IFU deep contract in this repository and must be
mirrored or referenced by any published LinxCore architecture set.

Do not edit the superproject mirrors by hand.

## Deep dives retained here

- `linxcore_top_design.md`
- `branch_recovery_rules.md`
- `linxisa_block_control_flow.md`
- `block_fabric_contract.md`
- `code_template_unit.md`
- `lsid_memory_ordering.md`
- `block_private_rf.md`
- `stages/BROB.md`

## Archived Janus subsystem notes

- `Janus/BCC/`: Block Control Core notes, diagrams, and background material
- `Janus/TMU/`: Tile Management Unit specifications
- `Janus/TMA/`: legacy Tile Memory Access implementation notes
- `Janus/Cube/`: CUBE matrix accelerator specifications and design notes
- `Janus/Vector/`: Vector Core specifications

These subdirectories are non-normative subsystem and historical design notes.
Legacy TMA/TAU names in this subtree describe implementation topology only;
they do not define architectural engines or ownership. The Tile execution
engines are exactly `VEC`, `SFU`, `TLSU`, and `CUBE`. `TEPL` is the unchanged
Mode/Function carrier for VEC/SFU, not an engine. Whenever these notes describe
the LinxCore/BCC IFU, they must use the I-SIDE/B-SIDE contract in `ifu.md` and
the stage taxonomy in `NAMING.md` and `pipeline-stage-catalog.md`.

The migration inputs were removed after classification and promotion. Their
machine-readable disposition and target mapping is `mechanism-intake.json`;
they are not part of the live contract.

## RTL evidence adapters

Each RTL lane has one adapter registered by `microarchitecture-contract.json`.
An adapter binds normative contract IDs to implementation facts: named top
shells, parameter defaults and guards, module/state owners, promotion state,
and declared gaps. The generic `tools/architecture/check_rtl_adapter.py`
validator reads Python structure through the AST and Scala declarations through
dependency-free lexical structure checks, so a renamed or removed owner cannot
retain promotion by stale prose alone.

New ISA-neutral IFU, cache, execution, or memory mechanisms extend the existing
lane adapter. They do not create a parallel architecture page. A capability may
be `integrated` only when its owner and composition evidence exist; incomplete
work stays `stub` or `absent` with a precise `known_gap`. Rejected ARM-specific
architectural state and behavior remain prohibited even when the underlying
queue, arbitration, replay, or cache mechanism is reusable.

Every module family declares one `extension_categories` category in the golden
manifest. The contract self-test appends synthetic IFU and cache families with
both RTL lanes, proves strict validation without changing existing contract
identities, and rejects an undeclared architectural category. New modules use
that path before adding owner evidence to each lane adapter.

The conformance checker distinguishes comparator fixtures from implementation
evidence. `harness-fixture` events test normalization and mismatch detection but
cannot promote a mechanism. Promotion to `cross-rtl-aligned` requires both
lanes to emit `owner-trace` events from adapter-mapped owner boundaries and to
match the scenario outcome; architectural promotion additionally requires the
existing commit trace contract and reference comparison.

## Structural specification chapters

- `module-catalog.md`: canonical module families, file ownership, and top-level
  composition rules.
- `pipeline-stage-catalog.md`: architecturally visible stage list, owner
  modules, and per-stage design intent.

## Related trace docs

- `../trace/linxtrace_v1.md`
- `../trace/linxtrace_pipeline_refresh_rule.md`
- `../trace/block_pipeview_contract.md`
- `../trace/pipeview_verification_playbook.md`
