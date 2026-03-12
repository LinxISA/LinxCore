# LinxCore Architecture Docs

This directory is the canonical source-of-truth for LinxCore architecture and
interface documentation.

## Contract pages

- `overview.md`
- `microarchitecture.md`
- `interfaces.md`
- `verification-matrix.md`
- `module-catalog.md`
- `pipeline-stage-catalog.md`

These pages are mirrored into the superproject publication paths:

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
- `stages/BROB.md`

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
