# LSU behavior

## Own each scalar memory row exactly once {#LSU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IEX-LSU-001,D-IDENTITY-001 -->

LSU MUST be the sole owner of physical STQ, SCB, CommitQ, LIQ, MDB, miss,
refill, load-return, and scalar L1D state. The public boundary MUST resolve
semantic identities to private rows without exposing a physical queue index or
lease generation. A second compatibility or reduced owner MUST NOT receive the
same architectural transaction.

## Join store reservation, address, data, class, and commit exactly {#LSU-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=LSU-001,IFC-COMMIT-001 -->

A logical store MUST reserve all required STQ beats atomically before either
STA or STD issues. Address and data may arrive in either order and on different
cycles. Pair stores retain one logical identity and two consecutive beats.
Translation/protection classification and ROB commit authorization MUST match
the complete semantic owner, logical first LSID/store ID, request count, and
beat. Stale or ambiguous matches MUST fail closed and mutate no row.

Only a fully joined, classified, committed store may become externally
visible. Store forwarding MUST honor unresolved older stores and must not
replace exact program-order comparison with a physical row index.

## Retain the complete load-attempt lifecycle {#LSU-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=LSU-001,D-IEX-LSU-LOAD-001 -->

An accepted load issue MUST atomically bind the IEX-owned initial attempt to
one LSU-owned LIQ row. LSU owns every later reissue, repick, launch, replay,
MDB wait, miss/refill, and cancellation transition. Responses MUST match the
current attempt and allocation generation; stale responses MUST NOT resolve or
write back. The two W4 load pipes retain independent backpressure and return
identity.

An accepted replay rebind from `Wait`, `L1DcMiss`, or `L2Wait` MUST preserve
the LIQ allocation, address, destination, transaction, and pipe while advancing
only the attempt. LIQ and every mutable old-attempt owner apply the transition
on one common ready/fire edge. An issued MissQ dependent is removed by exact
`{loadId, attempt, pipe}` match and its lower-memory identity remains as a
tombstone. The tombstone accepts and drops the eventual old response without
refill; a new attempt on the same line allocates a distinct lower-memory
identity and completes only from its own response. Terminal, completed,
nonconsecutive, stale, or ambiguous rebinds fail closed.

SCB lookup MUST use the exact semantic store identity. A stale or ambiguous
SCB match returns no forwarding authority and mutates neither SCB nor LIQ.

## Apply one scoped recovery transaction {#LSU-004}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=LSU-001,IFC-RECOVERY-001 -->

Recovery Prepare MUST fence new lower-memory publication and compute the exact
STQ, LIQ, MDB, miss, refill, and return rows that Apply would remove. Prepared
MUST remain withheld until translation, cache-miss, load-return, store-drain,
and every public lower-memory transaction are quiescent. Prepare and Abort MUST
NOT mutate those owners. Apply MUST prune the same killed set from every owner
while preserving unrelated STIDs and non-killed older rows.

## Canonical public composition {#MEC-LSU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=LSU-001,LSU-002,LSU-003,LSU-004 -->

`linxcore.lsu.LSU` is the one public composition owner.
`OooIexStoreStqFabric` owns store reservation and STQ/data rows,
`STQSCBCommitBackend` owns classification, CommitQ, and SCB state,
`ScalarLSULoadPath` owns LIQ, MDB, L1D, miss/refill, and load-return state,
`DSideTranslation` owns retained D-side translation, and
`LSULowerTransactionRecovery` owns exact outstanding lower-memory identities.
Translation replay and data-miss replay remain separate. Scalar L1D line and
global invalidation MUST fail closed while any affected line is dirty; neither
operation may silently discard dirty data.

## LSU verification {#VER-LSU-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=LSU-001,LSU-002,LSU-003,LSU-004 -->

`LSUStoreSpec`, `LSULoadSpec`, `LSUMemorySpec`, `LSUIntegrationSpec`, and
`IEXLSUIntegrationSpec` MUST cover crossed
STA/STD arrival, pair stores, exact classification and commit, forwarding,
unresolved older stores, independent load pipes, MDB replay, miss/refill,
stale-response rejection, translation, memory attributes, dirty maintenance,
lane-local backpressure, exact lower-transaction drain, and scoped recovery.
Generated-RTL activation MUST observe nonzero load and store traffic before a
displaced LSU public shell or alternate memory owner is deleted. It MUST also
prove exact MissQ tombstone drain, distinct replay request identity, and one
architectural load completion.
