# LinxCore Architecture Docs

This directory is the canonical source-of-truth for LinxCore architecture and
interface documentation.

## Contract pages

- `overview.md`
- `NAMING.md` (parameter and stage-name governance; not separately published)
- `microarchitecture.md`
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

The ARM material and Linx migration drafts under `docs/temp/` are
non-normative reference inputs. In particular, serial `IB/F4`,
`F4DecodeWindow`-as-stage, and 64-bit-BID wording there must not be copied into
the canonical contract.

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
