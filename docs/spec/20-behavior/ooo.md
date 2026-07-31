# OOO behavior

## Normalize every D1 operation before speculative ownership {#OOO-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-CTU-OOO-001,ARC-TOP-031,D-IDENTITY-001 -->

OOO D1 MUST convert every accepted `Encoded64` instruction and every
non-recursive CTU `TemplateUop` into the same `DecodedUop` shape. Encoded
decode MUST retain the original 2-, 4-, 6-, or 8-byte length when selecting a
generated opcode recipe. Template children MUST bypass encoded decode and
retain their parent, ordinal, count, row kind, immediate, prediction, fault,
and instruction identity.

## Admit one complete D1 prefix atomically {#OOO-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-001,D-PREFIX-001,PRM-WIDTH-001 -->

OOO D2 MUST accept or stall the complete `[0, count)` decoded prefix. It MUST
NOT admit a lane suffix independently, create a second validity mask with
holes, or change any retained field while its D2 consumer applies
backpressure. The W2, W4, W6, and W8 profiles MUST elaborate from the central
parameter source.

## Retain virtual ROB identity without publishing residency {#OOO-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-002,ARC-TOP-020,D-IDENTITY-001 -->

For every accepted D2 prefix, OOO MUST retain an older-first virtual ROB
group and member intent using the complete `ridSlot` and `ridGeneration`
widths. D2 MUST NOT advance the physical RID tail, publish a ROB row, allocate
a BROB entry, or present zero-valued BID or resident fields as bound identity.
D3 remains the sole provisional RID-tail mutator, and later BROB/ROB owners
bind residency through explicit state.

## Preserve precise decode faults {#OOO-004}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-001,ARC-TOP-020 -->

An illegal encoded instruction or an IFU fetch fault MUST produce one decoded
row with an explicit typed precise-trap intent. The faulting instruction's
identity and metadata MUST remain attached to that row; the fault MUST NOT be
encoded as a substitute opcode or consumed on behalf of a neighboring lane.

## Fence and cancel retained D2 state by STID {#OOO-005}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-002,IFC-RECOVERY-001,D-IDENTITY-001 -->

Recovery prepare MUST retain and echo the exact plan and fence new or visible
D2 work for only its target STID without mutation. A matching apply MUST
remove that STID's retained D2 row. A matching abort MUST release the fence
without removing the row. Unrelated STIDs MUST remain eligible for admission
and output arbitration.

## Canonical decode mechanism {#MEC-OOO-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-001,OOO-004 -->

`linxcore.ooo.DEC` is a stateless Decoupled transform. For encoded traffic it
uses the generated, length-qualified `OooOpcodeRecipeTable` decode path and
maps the resulting opcode, dispatch class, operand classes, architectural
tags, immediate, boundary, and trap state into the top-level interface
`DecodedUop`. For template traffic it maps `TemplateRowKind` directly to a
named uop class and never decodes the parent instruction again. The exact
canonical `FrontEndOp` is copied from CTU after decode selection so 16-bit
legacy generation defaults cannot narrow architectural identity or prediction
metadata.

## Retained D2 admission mechanism {#MEC-OOO-002}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-002,OOO-003,OOO-005 -->

`linxcore.ooo.OOO` holds at most one immutable `D2AdmissionGroup` per STID.
Each transaction carries `count`, virtual group count and keys, decoded rows,
typed trap intent, and explicit false `residentBound` and `brobBound` state.
Group and member indices are assigned older-first from the D3-owned tail
snapshot; RID wrap increments the full-width virtual generation. A retained
grant keeps the selected transaction stable until fire or a matching recovery
operation.

## D1/D2 verification {#VER-OOO-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=OOO-001,OOO-002,OOO-003,OOO-004,OOO-005 -->

`OOODecodeSpec` MUST cover all four instruction lengths, template bypass,
operand and prediction metadata, illegal and fetch-fault traps, W2/W4/W6/W8
elaboration, atomic stall behavior, RID wrap with non-zero high generation
bits, and STID-scoped recovery. `CTUOOOIntegrationSpec` MUST cover ordinary
and expanded template traffic through the adjacent boxes with backpressure and
without loss, duplication, reordering, or recursive expansion.
