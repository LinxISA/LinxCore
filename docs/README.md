# LinxCore Docs

This directory is the canonical authoring home for LinxCore implementation and
architecture documentation.

## Canonical contract pages

- `docs/architecture/overview.md`
- `docs/architecture/microarchitecture.md`
- `docs/architecture/interfaces.md`
- `docs/architecture/verification-matrix.md`
- `docs/architecture/module-catalog.md`
- `docs/architecture/pipeline-stage-catalog.md`

Published mirrors for superproject architecture navigation are generated into:

- `../../docs/architecture/linxcore/overview.md`
- `../../docs/architecture/linxcore/microarchitecture.md`
- `../../docs/architecture/linxcore/interfaces.md`
- `../../docs/architecture/linxcore/verification-matrix.md`
- `../../docs/architecture/linxcore/module-catalog.md`
- `../../docs/architecture/linxcore/pipeline-stage-catalog.md`

Use:

```bash
python3 /Users/zhoubot/linx-isa/tools/bringup/sync_linxcore_arch_docs.py --root /Users/zhoubot/linx-isa
```

## Sections

- `architecture/`: canonical LinxCore contract pages and implementation deep dives
- `trace/`: LinxTrace, pipeview, and viewer-side contracts
- `cosim/`: co-simulation protocol notes
- `flows/`: implementation and tooling workflow notes
