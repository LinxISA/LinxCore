# OooIexBruPipeline

## Purpose

`OooIexBruPipeline` is the typed BRU value/control owner after exact I2-to-E1
transfer. It computes a bounded branch-class subset in E1 and retains the
complete value plus BCTRL update as one E2 transaction. It does not mutate
BCTRL, write P/T/U files, complete ROB, publish wakeup, or redirect IFU.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexBruPipeline.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexBruPipelineSpec.scala`

The stage boundary follows
`tools/LinxCoreModel/model/iex/pipe/bru_pipe.cpp::{move,runE1}` and the E1/E2
physical topology described in `Documents/a.txt`. Linx generated recipes,
QEMU behavior, and the ISA manual remain authoritative for opcode semantics;
ARM condition state, flags, and redirect conventions are not imported.

## Ownership and timing

E1 accepts only when opcode, BRU class, dispatch recipe, BCTRL side-effect
owner, source shape, destination shape, immediate, and PC requirements all
agree. The result is captured after the accept edge and retained in E2 under
backpressure. E2 may drain and refill on the same edge without losing owner
continuity.

E2 contains the original execute identity, optional writeback, and optional
BCTRL update. A later atomic sink must accept this transaction before
publishing any RF/BCTRL/ROB/redirect/trace effect. This prevents replay or
backpressure from applying only part of a branch result.

Exact grouped-ROB recovery and exact speculative-load cancellation suppress
the retained E2 owner. Unrelated STIDs and stale load generations do not kill
it.

## Supported subset

The static whitelist contains:

- `ADDTPC` and `HL.ADDTPC`, producing a destination value from page-aligned
  PC plus the D1-normalized signed byte displacement;
- immediate compare/logic value producers `CMP.{AND,EQ,GE,GEU,LT,LTU,NE,OR}I`;
- compact `C.SETC.EQ` and `C.SETC.NE` BCTRL condition updates;
- `SETC.TGT` and `C.SETC.TGT` BCTRL target updates.

The whitelist is audited against the generated recipe catalog. `B.Z`, `B.NZ`,
`J`, and `JR` remain fail-closed: conditional redirects still need an explicit
architectural condition owner, while all redirecting forms need typed
prediction/redirect input and an atomic redirect sink.

## D1 immediate contract

Both ADDTPC encodings reach BRU with a signed byte displacement. The 32-bit
form was already shifted by D1; I0.9b corrects `HL.ADDTPC` to apply the same
`<< 12` normalization. E1 performs only
`(pc & ~0xfff) + normalizedDisplacement` and contains no encoding-aware field
extraction.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooD1DecodeSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexBruPipelineSpec
```

The D1 gate covers equal 32/48-bit page normalization. BRU UT covers catalog
alignment, ADDTPC arithmetic, signed immediate comparison, compact condition
updates, target updates, E2 stability and drain/refill, exact recovery, stale
and exact load cancellation, unsupported redirects, and class mismatch.

## Remaining gaps

- explicit condition-state input and typed B.Z/B.NZ execution;
- J/JR target calculation and prediction comparison;
- atomic E2 BCTRL/RF/ROB/redirect/trace sink;
- connection to the static E1 transfer topology;
- redirect recovery IT, synthesis timing, and workload evidence.
