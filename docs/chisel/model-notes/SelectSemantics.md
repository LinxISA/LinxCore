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

## QEMU Resolution

R137 found that the previous QEMU `trans_csel` implementation documented and
implemented the opposite data choice:

```text
dst = (SrcP != 0) ? SrcR : SrcL
```

The R136 1660-row CoreMark probe reaches a distinguishing pair of rows:

- `pc=0x40005d32`, `insn=0xe7860177`, `rd=x2`, `SrcL=x12=0`,
  `SrcR=x24/T0=0`, `SrcP=x28/U0=0`; both source-order choices write `x2=0`.
- `pc=0x40005d42`, `insn=0xc7c10f77`, `rd=x30/U0`, `SrcL=x2=0`,
  `SrcR=x28/U0=0x40000768`, `SrcP=x24/T0=1`; QEMU writes `0x40000768`,
  proving the live QEMU lane is true-to-`SrcR`.

R138 resolves the local source-order contract by aligning the implementation
lanes to LinxCoreModel/Sail:

- QEMU scalar `trans_csel` selects `SrcL` when `SrcP != 0` and uses `SrcR` as
  the false case.
- LLVM MC lowering maps the machine node true operand to CSEL `SrcL` and the
  false operand to CSEL `SrcR`.
- The reduced Chisel execute path implements `OP_CSEL` as
  `Mux(srcData(2) =/= 0.U, srcData(0), srcData(1))`.
- The reduced QEMU-row extractor recognizes CSEL rows whose predicate is carried
  by a reduced T/U local queue. Scalar-predicate CSEL remains outside the current
  two-source commit-row schema and must wait for a trace-schema extension.

## Hardware Direction

Do not reintroduce a QEMU-compatible true-to-`SrcR` workaround. The Chisel lane
is model-aligned, and CSEL must keep true-to-`SrcL` behavior unless Sail and
LinxCoreModel are deliberately changed first.

## Open Items

- Rebuild QEMU and LLVM after the local source patches and run the focused CSEL
  regressions before promoting a larger live CoreMark window.
- Extend the trace schema or row reducer before admitting scalar-predicate CSEL
  rows, because the current QEMU-shaped row exposes only two source fields.
