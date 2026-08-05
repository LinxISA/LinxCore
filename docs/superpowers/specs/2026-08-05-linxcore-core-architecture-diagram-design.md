# LinxCore Core Architecture Diagram Design

## Goal

Create one editable draw.io architecture diagram that explains the current
LinxCore core without presenting a reduced bring-up shell as the complete
implementation.

## Considered approaches

1. Draw only the canonical pyCircuit `LinxCoreTop`. This is architecturally
   accurate but hides the active Chisel replacement lane.
2. Draw only the Chisel `TOP`. This is compact and matches current Scala
   composition, but it would incorrectly imply that the reduced typed shell is
   the complete core.
3. Draw the canonical pyCircuit core as the main view and add a small Chisel
   `TOP` inset. This preserves the full contract while showing the active
   replacement boundary. This is the selected approach.

## Main view

Use a left-to-right data-flow layout with six visually distinct regions:

- Frontend: I-SIDE `I-F0..I-F4`, Instruction Buffer, D1, plus the decoupled
  B-SIDE `B-F0..B-F4` predictor path.
- Decode and OOO: D2, D3/Rename, S1, S2/S3-IQ, P0/P1, I1/I2, and IEX.
- Commit and recovery: ROB, R0..R4, commit, PC buffer, RENU, and flush/restart.
- LSU and memory: LIQ, LHQ, MDB, STQ, L1D, SCB/store drain, and external
  instruction/data memory.
- Block fabric and engines: BISQ, BRENU, BCTRL, BROB, VEC, CUBE, TMA, TAU,
  and TMU.
- Observability: commit, block, and pipeview probes plus trace/debug output.

Solid arrows show primary data or command movement. Red dashed arrows show
flush, recovery, replay, or redirect control. Grey dashed outlines identify
current bring-up or staged-integration behavior rather than a fully promoted
owner.

## Chisel inset

Add a compact inset labelled `Reduced typed composition lane (not the full
core)` with the current public chain `IFU -> CTU -> OOO -> IEX -> LSU`, DTU
observability, external instruction/data memory, and OOO recovery feedback to
IFU/CTU/LSU.

## Outputs

- `docs/architecture/diagrams/linxcore-core-architecture.drawio`
- `docs/architecture/diagrams/linxcore-core-architecture.png` for review
- After approval, `linxcore-core-architecture.drawio.png` with embedded XML

## Validation

- Run the bundled `validate.py --strict --score` structural checks.
- Export a clean PNG without embedded XML for visual inspection.
- Check for clipped labels, overlaps, off-canvas nodes, missing connections,
  edge-through-node collisions, and ambiguous ownership.
- Keep all labels in English to match canonical source and architecture docs.

