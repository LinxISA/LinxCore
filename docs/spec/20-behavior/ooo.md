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

## Publish grouped ROB residency and complete by exact identity {#OOO-010}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-003,OOO-008,D-IDENTITY-001 -->

`linxcore.ooo.ROB` MUST be the canonical grouped ROB residency owner. D3
prepare MUST be side-effect free and MUST return exact `RobIdentity` bindings
for the continuous accepted prefix. Prepare MUST validate the ROB-owned RID
tail, STID, group/member order, free resident rows, and any BROB-prepared
BID/generation binding before it reports readiness. Only the caller-controlled
publication fire may install resident rows, advance the RID tail, and mark
early-complete zero-operand or boundary uops complete. Completion reports MUST
be consumed at the inspection boundary and reported as accepted or rejected
separately; stale, duplicate, wrong-generation, wrong-member, or wrong-BID
completions MUST NOT mutate ROB state. Release readiness MUST be
side-effect-free and MUST validate the complete retained prefix before the
common commit fire can retire or deallocate any row. ROB commit preview
observation MUST NOT move an entry from `Completed` to `Retired`; that state
transition is owned by the explicit common commit-apply boundary. ROB resident
member state is physically indexed by `robBankCount` bank/row geometry, and
unsupported non-divisible bank profiles MUST fail closed.

## Own per-STID BROB residency exactly {#OOO-011}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-010,D-IDENTITY-001 -->

`linxcore.ooo.BROB` MUST own a generation-qualified circular block table per
STID. D3 prepare MUST walk groups older-first, allocate a native BID only at a
typed block start, carry the current block across groups, close it only at a
typed block stop, and mutate table/head/tail state only on the common
publication fire. Release readiness MUST validate the complete retained
prefix and free the head block only when the exact final ROB member for that
block is included. Recovery prepare MUST be non-mutating; recovery apply MUST
match the retained plan and prune only the target-STID suffix while preserving
older blocks, current surviving blocks, exact `used` accounting, and unrelated
STIDs. Stale BID generation release or recovery attempts MUST be rejected
without changing unrelated STIDs.

## Retain in-order commit until every release owner accepts {#OOO-012}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-009,OOO-010,IFC-COMMIT-001 -->

`linxcore.ooo.CommitControl` MUST retain one complete oldest-first commit
prefix, rename-release transaction, BROB release, ROB release, and trap
selection while any consumer backpressures or withholds side-effect-free
readiness. `CommitControl.out.valid` is the common fire candidate and MUST be
asserted for non-empty commit prefixes only when ROB, RENU, and BROB all
report exact release readiness. Zero-lane head trap and interrupt-boundary
transactions MUST be allowed to fire once without fabricating ordinary release
readiness. Duplicate suppression MUST compare the exact accepted
`CommitControlTxn` signature: commit count, active ROB identities, companion
release facts, trap validity, kind, cause, and identity. A changed prefix
length, trap cause/kind, or ordinary/trap shape is a distinct transaction even
when the first ROB identity is unchanged. The retained prefix MUST NOT be
recomputed or repeated while a `Valid` ROB preview remains asserted after the
common fire. `Completed` and `Retired` are separate ROB states; physical ROB
rows and previous physical registers are freed only on the matching common
commit fire.
Secondary destination history MUST remain in the companion
`RenameCommitReleaseTxn` even when `CommitEntry.destination` exposes only the
primary projection.

## Select precise traps, interrupts, and recovery from one ROB plan {#OOO-013}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=OOO-010,OOO-012,IFC-RECOVERY-001 -->

`linxcore.ooo.RecoveryControl` MUST be the sole global recovery event arbiter
and plan distributor. Producer events admitted into one arbitration boundary
are retained as a complete contender set until every active producer has a
matching ROB candidate status. ROB candidate status is the only authority for
exact residency, non-retired eligibility, global allocation age, and
synchronous head-trap priority; RecoveryControl MUST NOT derive global age from
STID/RID concatenation, select from a partial status set, or drop a candidate
before matching status is known. A
synchronous head trap wins over an interrupt at the same precise boundary; an
`Exception` cause without ROB status proving a precise head trap MUST NOT
outrank an older ordinary recovery event. Interrupts are admitted only at an
explicit precise ROB head boundary and cannot bypass an unresolved producer
contender. Recovery prepare MUST ask ROB for one
retained `RecoveryPlan`
containing the exact compact killed suffix, offer the identical plan exactly
once to every target in `Prepare`, wait for every matching acknowledgement,
ignore mismatched acknowledgements, and emit exactly one terminal decision.
If abort arrives before ROB accepts the request, RecoveryControl cancels the
request locally and emits no fabricated owner terminal. A held `robPrepared`
beat observed before ROB accepts the request MUST drain without becoming that
request's plan, and abort-suppressed requests MUST NOT let such a beat attach
to a later request. If ROB has accepted the request but has not yet returned
the ROB-authored plan, RecoveryControl
waits for a response that correlates to the exact retained request and then
emits only the matching ROB abort terminal; target owners that never received
`Prepare` observe no abort. Stale, unrelated, wrong-phase, or otherwise
mismatched ROB responses in a legal ROB response window MUST be drained from
the Decoupled channel but MUST NOT overwrite the retained plan, enter target
prepare, emit ROB abort, clear abort-pending state, or cause the request to be
reissued after it has already fired. Correlation matches the request phase,
transaction ID, cause, full trigger identity, redirect PC, and new epoch while
allowing only the ROB-authored killed-suffix and survivor-tail fields to
differ. If abort arrives while target preparation is pending, the retained
ROB-authored plan is broadcast as non-mutating `Abort` to every target and as
the matching ROB abort terminal. Abort coincident with the final target acknowledgement has
non-mutating priority over `Apply`. Once the common one-cycle `Apply` is
visible, it is irrevocably committed and a same-cycle abort request MUST NOT
schedule a later abort for the same plan.
Branch recovery preserves the trigger and kills younger members; memory-order
replay recovery kills the trigger and younger members. ROB-authored recovery
plans MUST distinguish killed member count from affected ROB group count and
must identify the exact surviving ordered tail. ROB and BROB recovery apply
MUST mutate only once for a retained matching `Apply` transaction; stale,
duplicate, mismatched, or wrong-phase apply is non-mutating. Abort MUST be
non-mutating and MUST clear only the matching retained transaction. If a ROB
or BROB owner observes simultaneous matching `Apply` and `Abort`, it MUST
choose the non-mutating abort path and perform no suffix mutation.
BROB recovery prepare MUST retain the exact local action and apply that action
without recomputing from mutable table state; partial recovery of an open
current block preserves the surviving block and rewinds its last ROB owner.

## ROB/BROB/commit/recovery owner mechanisms {#MEC-OOO-006}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=OOO-010,OOO-011,OOO-012,OOO-013 -->

The Task-9 canonical owner files are `linxcore.ooo.ROB`,
`linxcore.ooo.BROB`, `linxcore.ooo.CommitControl`, and
`linxcore.ooo.RecoveryControl`. Cross-owner payloads live under
`linxcore.top.interface.OOORob` and `linxcore.top.interface.Recovery`.
These owners use `CoreParams` directly and do not instantiate or wrap the
legacy `Ooo*`, `rob.*`, `bctrl.*`, or `recovery.*` owners.

## ROB/BROB/commit/recovery owner verification {#VER-OOO-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=OOO-010,OOO-011,OOO-012,OOO-013,MEC-OOO-006 -->

`OOORobCommitSpec` and `OOORecoverySpec` MUST cover grouped ROB publication,
same-group member commit order, STID arbitration, exact completion acceptance
and rejection, stale slot reuse rejection, per-STID BROB BID/generation
checks, whole-prefix release rejection, final-member BROB release, retained
commit under independent owner readiness, secondary-destination release
history, precise trap priority, memory-order recovery, branch same-group
younger-member kill, exact suffix membership including generation and member
index, distinct killed member/group counts, survivor-tail repair, BROB
survivor accounting, nontrivial ROB bank geometry, recovery age-token width
guarding, source arbitration with delayed and retained ROB statuses, interrupt
holdoff behind unresolved producers, ROB recovery request/response,
wrong-phase and duplicate apply rejection, ROB/BROB abort termination,
one-prepare-per target barriers, mismatched acknowledgement rejection, common
apply, request-phase ROB abort, target-prepare abort priority, visible-apply
abort suppression, legal Decoupled drain without semantic acceptance for
pre-request and stale/unrelated ROB responses in RequestRob, WaitRob, and
WaitRobAbort,
simultaneous terminal fail-closed behavior, and
non-mutating abort.
