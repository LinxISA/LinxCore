# Select Semantics Model Notes

## Source Mapping

- Sail: `isa/sail/model/execute/execute.sail`
- LinxCoreModel:
  `model/LinxCoreModel/isa/calculate/compound/Compound.cpp`
- LinxCoreModel decode:
  `model/LinxCoreModel/isa/codec/decodefiles/block32.decode`
- QEMU: `emulator/qemu/target/linx/translate.c`
- Chisel issue: `docs/chisel/issues.md`

## CSEL Contract

`block32.decode` maps scalar `CSEL` sources as:

- `src0 = SrcP`
- `src1 = SrcL`
- `src2 = SrcR`

The local LinxCoreModel checkout at
`1993e4e749403824a4908548baf77d5e15117068` and fetched
`origin/SuperScalarModel` at `704a779` both implement:

```text
dst = (SrcP != 0) ? SrcL : SrcR
```

The Sail v0.56 model in `exec_csel` documents the same source order.

## QEMU Divergence

The current QEMU `trans_csel` implementation documents and implements the
opposite data choice:

```text
dst = (SrcP != 0) ? SrcR : SrcL
```

The R136 1660-row CoreMark probe reaches a distinguishing pair of rows:

- `pc=0x40005d32`, `insn=0xe7860177`, `rd=x2`, `SrcL=x12=0`,
  `SrcR=x24/T0=0`, `SrcP=x28/U0=0`; both source-order choices write `x2=0`.
- `pc=0x40005d42`, `insn=0xc7c10f77`, `rd=x30/U0`, `SrcL=x2=0`,
  `SrcR=x28/U0=0x40000768`, `SrcP=x24/T0=1`; QEMU writes `0x40000768`,
  proving the live QEMU lane is true-to-`SrcR`.

## Hardware Direction

Do not add reduced Chisel `OP_CSEL` execute support by copying the current QEMU
translator behavior. The Chisel lane is model-aligned, and the current model
plus Sail evidence selects `SrcL` when `SrcP` is nonzero.

Before promoting a CoreMark gate past the R137 frontier, resolve the contract
in one of these lanes:

- QEMU lane: change QEMU and its tests to match Sail/LinxCoreModel.
- Architecture/model lane: explicitly update Sail and LinxCoreModel, then
  regenerate downstream contracts and record the architecture decision.
- Temporary reduced-QEMU lane: if a short-term QEMU-compatible workaround is
  required, mark it as non-architectural in the module docs and keep it out of
  model-aligned replacement evidence.

## Open Items

- Add a focused CSEL architectural regression once the owner decision is made.
- Re-run the live CoreMark reduced fetch/RF/ALU gate past 1620 rows only after
  the selected source-order contract is implemented consistently.
