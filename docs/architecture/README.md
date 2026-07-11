# LinxCore Architecture Docs

This directory is the canonical source-of-truth for LinxCore architecture and
interface documentation.

## Authority contract (LC-ARCH-DOC-001)

The Markdown pages own normative semantics. The machine-readable
`microarchitecture-contract.json` indexes each contract definition, stage
family, implementation owner, top-shell role, scenario, and migration input.
It must not introduce behavior that is absent from the owning Markdown page.

Run the standalone authority and coverage gate with:

```bash
bash tests/test_microarchitecture_contract.sh
```

## Contract pages

- `overview.md`
- `NAMING.md` (parameter and stage-name governance; not separately published)
- `microarchitecture.md`
- `microarchitecture-contract.json` (machine-readable ownership and
  verification index; not a second prose specification)
- `mechanism-intake.json` (historical source disposition and promotion
  evidence; not a second prose specification)
- `rtl-adapters/*.json` (implementation evidence and declared promotion gaps;
  not a second prose specification)
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

## Janus engine directories

- `Janus/BCC/`: Block Control Core notes, diagrams, and background material
- `Janus/TMU/`: Tile Management Unit specifications
- `Janus/TMA/`: Tile Memory Access specifications
- `Janus/Cube/`: CUBE matrix accelerator specifications and design notes
- `Janus/Vector/`: Vector Core specifications

These subdirectories are subsystem notes and may use local or historical
pipeline names. They do not override the canonical BID or stage taxonomy in
`microarchitecture.md`, `NAMING.md`, and `pipeline-stage-catalog.md`.

The migration inputs were removed after classification and promotion. Their
recoverable snapshots are commits
`f72c0ee6fb21d08b2ac38d1f9918021bf6c28af3` and
`8f6b821f75aa5170766d6ab4d37a9443b9a4f0ec`; the machine-readable disposition
and target mapping is `mechanism-intake.json`. Historical serial `IB/F4`,
`F4DecodeWindow`-as-stage, 64-bit-BID, and ARM-specific architectural
wording is not part of the live contract.

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
