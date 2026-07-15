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
cd /path/to/linx-isa
python3 tools/bringup/sync_linxcore_arch_docs.py --root .
```

The sync helper lives in the superproject, not in a standalone `LinxCore`
checkout.

## Sections

- `architecture/`: canonical LinxCore contract pages and implementation deep dives
- `trace/`: LinxTrace, pipeview, and viewer-side contracts
- `cosim/`: co-simulation protocol notes
- `flows/`: implementation and tooling workflow notes
- `archive/`: explicitly retired, non-normative design and ISA snapshots

## Janus engine specs

- `architecture/Janus/BCC/README.md`: Block Control Core background and design
  notes
- `architecture/Janus/TMU/TMU_SPEC_EN.md`: Tile Management Unit specification
- `architecture/Janus/TMA/TMA_spec_en.md`: Tile Memory Access specification
- `architecture/Janus/Cube/README.md`: CUBE matrix accelerator specification
  index
- `architecture/Janus/Vector/VECTOR_CORE_SPEC_EN.md`: Vector Core specification
