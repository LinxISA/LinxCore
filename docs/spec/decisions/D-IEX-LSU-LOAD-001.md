# IEX and LSU load-issue ownership decision

## Separate program order, first issue, and LIQ replay ownership {#D-IEX-LSU-LOAD-001}
<!-- ndf: kind=decision level=must layer=L1 status=stable since=0.1 affects=IFC-IEX-LSU-001,MEC-IEX-LSU-001,IFC-RECOVERY-001,MEC-RECOVERY-001 -->

**Context.** A load carries program-order identity from OOO, execution identity
through IEX, and physical residency inside LSU. Giving all three identities to
one queue row would couple architectural age to a reusable physical index and
would make replay, backpressure, and precise recovery ambiguous.

**Decision.** OOO allocates the full program-order `LSID`, the load-only `LID`,
the store-only `SID`, and the `YOST`/`YOLD` older-memory boundaries. IEX
allocates a non-reused memory transaction for
each retained memory uop; the independently issued STA and STD children of one
store share that transaction. IEX allocates both identities on the canonical
Dispatch-to-IQ acceptance edge, when the memory uop first becomes retained
IEX state. Every complete candidate then remains stable through issue and LIQ
backpressure. LSU owns LIQ residency and every later load-attempt transition.

LIQ reissue and LIQ repick are distinct mechanisms. `LoadReissueTxn` is used
when address translation is still required; `LoadRepickTxn` is used when the
physical address is already available and the load-result path can run again.
Each accepted transition carries the exact current and next `MemoryIdentity`.
ROB identity, memory transaction, and `lsid` remain equal; attempt generation
advances exactly once. `pipeId` names the retained route of that attempt and
may change only as part of the accepted reissue or repick transition.
`LoadCancelTxn` flows from LSU to IEX only to cancel speculative dependents of
the current attempt; it neither frees LIQ residency nor creates the next
attempt.

OOO recovery is the only terminal kill protocol. `RecoveryPlan` Prepare fences
affected lifecycle traffic without mutation, Apply prunes the matching IEX and
LSU state on the common transaction, and Abort preserves both. Transaction and
attempt serials never rewind. A LIQ/STQ row, ROB generation, or pipe lane is
never a substitute for the complete identity.

**Consequence.** The selected ownership is option A: IEX owns transaction and
initial attempt allocation; LSU owns replay, repick, and rebind. Stale or
skipped-generation lifecycle inputs are drainable rejection events and cannot
produce resolve, wakeup, memory traffic, or owner mutation.

**Supersession.** A later ownership decision must replace these channels and
both owner mutations atomically. Two live transaction or attempt allocators are
never a permitted compatibility state.
