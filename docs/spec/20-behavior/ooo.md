# OOO behavior

## Normalize every D1 operation before speculative ownership {#OOO-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-CTU-OOO-001,ARC-TOP-031,D-IDENTITY-001 -->

OOO D1 MUST convert every accepted `Encoded64` instruction and every
non-recursive CTU `TemplateUop` into the same `DecodedUop` shape. Encoded
decode MUST retain the original 2-, 4-, 6-, or 8-byte length when selecting a
generated opcode recipe. Template children MUST bypass encoded decode and
retain their parent, ordinal, count, row kind, immediate, prediction, fault,
and instruction identity. A continuous packet MAY contain both operation
kinds after Instruction Buffer repacking; OOO MUST normalize it per lane and
preserve the original order.

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
named uop class and never decodes the parent instruction again. A sparse
encoded-lane projection preserves encoded fusion and compaction only across
adjacent encoded lanes; a template lane is an ordering and fusion boundary.
The encoded results and direct template results are then compacted together
in original input-lane order. The exact
canonical `FrontEndOp` is copied from CTU after decode selection so 16-bit
legacy generation defaults cannot narrow architectural identity or prediction
metadata.

## Retained D2 admission mechanism {#MEC-OOO-002}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-002,OOO-003,OOO-005 -->

The public D1/D2 payloads and complete slice IO live in
`linxcore.top.interface.OOOD1D2`, while `linxcore.ooo.OOO` holds at most one
immutable `D2AdmissionGroup` per STID.
Each transaction carries `count`, virtual group count and keys, decoded rows,
typed trap intent, and explicit false `residentBound` and `brobBound` state.
Group and member indices are assigned older-first from the D3-owned tail
snapshot; RID wrap increments the full-width virtual generation. A retained
grant keeps the selected transaction stable until fire or a matching recovery
operation.

## D1/D2 verification {#VER-OOO-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=OOO-001,OOO-002,OOO-003,OOO-004,OOO-005 -->

`OOODecodeSpec` MUST cover all four instruction lengths, template bypass,
mixed encoded/template ordering and fusion isolation,
operand and prediction metadata, illegal and fetch-fault traps, W2/W4/W6/W8
elaboration, atomic stall behavior, RID wrap with non-zero high generation
bits, and STID-scoped recovery. `CTUOOOIntegrationSpec` MUST cover ordinary
and expanded template traffic, including an Instruction Buffer-repacked mixed
prefix, through the adjacent boxes with backpressure and without loss,
duplication, reordering, or recursive expansion.
## Rename absolute P registers exactly {#OOO-006}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-021,OOO-003 -->

The absolute scalar GPR namespace has 24 architectural registers per STID.
`PRename` MUST be the sole owner of per-STID SMAP, per-STID CMAP, the shared
physical P free list, P generation tokens, and per-STID P MapQ
head/tail/count. P rename MUST resolve same-prefix RAW and WAW dependencies
from older destinations in the accepted prefix before consulting SMAP, and
each P destination MUST retain an exact history row for its current and
previous physical tags.

## Rename relative T and U sequences exactly {#OOO-007}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-002,OOO-006,D-PREFIX-001 -->

T and U are independent ordered relative namespaces. A source with relative
index `n` resolves the sequence entry `tail-(n+1)` in its own namespace, with
same-prefix destinations participating in the visible sequence before older
MapQ rows. T and U destinations MUST allocate independent sequential local
tags without using P maps or the P free list. Physical tag index/generation
and MapQ sequence index/generation MUST be distinct tokens so unequal physical
and MapQ capacities remain exact across either wrap point.

## Publish rename atomically through D3 {#OOO-008}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-006,D-IDENTITY-001,IFC-COMMIT-001 -->

RENU MUST prepare one complete D2 prefix atomically only when P, T, U,
provisional retention, and all required history capacities can accept the
whole prefix. From-D2 acceptance MUST retain only the provisional D3 payload.
Visible P SMAP, P free-list, P MapQ, T/U tails, and T/U MapQs MUST mutate only
on the common D3 fire. If D3 backpressures the prefix, the complete renamed
payload and all history rows MUST remain stable and MUST NOT be published
twice. A recovery prepare targeting a D3 transaction already presented under
backpressure MUST remain unaccepted until that irrevocable transaction fires;
it MUST NOT switch the presented payload to another STID. Recovery MAY proceed
in parallel when the presented transaction belongs to an unrelated STID.

## Recover rename state by target STID and surviving tail {#OOO-009}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-005,OOO-008,IFC-RECOVERY-001 -->

Commit release MUST match the owning MapQ head, exact `RobIdentity`, and
generation-qualified history before mutating committed state. The complete
continuous release prefix MUST be accepted or rejected as one transaction
across P, T, and U; a validity hole or stale token in any domain MUST prevent
all three owners from applying it. Accepted P commit updates CMAP and returns
only the previous P tag. Accepted T/U commit advances only the matching
namespace head. Commit release MUST NOT race an active recovery transaction.
Recovery prepare MUST be non-mutating and MUST fence only its target STID;
unrelated STIDs remain eligible for admission and publication. Matching
recovery apply MUST affect only the target STID,
preserve the surviving ordered prefix, prune the killed suffix, return killed
current P tags, rewind T/U cursors to the survivor boundary, and rebuild P
SMAP from CMAP plus P survivors. Abort MUST leave all owner state unchanged.

## Parameterize rename generations independently {#PRM-RENAME-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=PRM-WIDTH-001 -->

The P tag generation width and T/U local sequence generation width MUST be
configured in the central OOO parameter record. Adapters MUST NOT fall back to
an implicit 8-bit generation. P MapQ depth, P physical capacity, T/U physical
capacity, and ROB capacity are independent domains; each MUST be validated for
its own worst accepted prefix rather than derived from another domain.

## D2/D3 rename interface mechanism {#MEC-OOO-003}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-006,OOO-007,OOO-008 -->

`linxcore.top.interface.OOOD2D3` defines `RENUD2D3IO`,
`RenameCommitReleaseEntry`, `RenameCommitReleaseTxn`, and the public D2/D3
payloads. The D3
group preserves D2 virtual ROB/group/member intent, decoded uop data, renamed
source and destination tags, early-complete sideband, and exact per-slot
history rows. RENU combines the P and T/U owner overlays into this single
payload and sends the same retained row back to both owners on the common D3
publication pulse.

## P rename owner mechanism {#MEC-OOO-004}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-006,OOO-008,OOO-009 -->

`linxcore.ooo.PRename` owns the P-domain speculative and committed maps,
physical free state, per-tag generation tokens, and per-STID P MapQ. P rename
prepare resolves sources from SMAP plus older same-prefix destinations and
records each destination's MapQ index and generation token. Publication
appends exact history rows. Release mutates CMAP and returns previous tags
only when the release matches the current MapQ head order and exact ROB
identity. Recovery rebuilds the target STID from CMAP plus surviving rows and
does not mutate non-target STIDs.

## T/U rename owner mechanism {#MEC-OOO-005}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-006,OOO-007,OOO-009 -->

`linxcore.ooo.TURename` owns independent T and U MapQ local-sequence
index/generation state and separate physical-tag cursors. Prepare rejects the
whole prefix if any relative source underflows the visible local history. D3
rows carry the T and U sequence snapshots seen before each uop. Publication
advances only the target STID local tails and counts. Recovery applies only to
the target STID and leaves aborts non-mutating.

## Rename owner verification {#VER-OOO-002}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=OOO-006,OOO-007,OOO-008,OOO-009,PRM-RENAME-001,MEC-OOO-003,MEC-OOO-004,MEC-OOO-005 -->

`RENUSpec`, `RENUAtomicSpec`, and `TURenameSequenceSpec` MUST cover D3-only
publication, P same-prefix forwarding, same-lane
dual-destination WAW history, T/U relative allocation, T/U underflow
whole-prefix rejection, pending D3 backpressure stability, zero-destination
boundary sidecars, cross-domain all-or-none exact release, malformed-prefix
rejection, stale generation rejection, STID-scoped recovery fencing with
survivors, target-row irrevocability and unrelated progress, independent
physical/MapQ capacities and generations,
and W2/W4/W6/W8 behavioral publication.

## Rename parameter verification {#VER-PRM-004}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=PRM-RENAME-001 -->

`CoreConfigurationSpec` MUST prove that rename generation widths are centrally
configured and fail closed at zero. It MUST also prove that P MapQ, T/U MapQ,
P physical, T physical, and U physical capacities are validated independently
against one worst-case rename prefix.
