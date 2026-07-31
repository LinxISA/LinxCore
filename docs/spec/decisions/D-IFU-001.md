# IFU line-error decision

## Reject erroneous line data before L1I installation {#D-IFU-001}
<!-- ndf: kind=decision level=must layer=L1 status=stable since=0.1 affects=IFU-002,MEC-IFU-002,VER-IFU-002 -->

**Context.** The lower-memory response distinguishes denied and corrupt
instruction-line beats and carries an architectural error cause. Treating
those beats as zero data would discard the error and create executable bytes
that memory never supplied.

**Decision.** The I-SIDE memory adapter terminates the active line assembly on
the first denied or corrupt beat. It retains the exact fetch request and error
cause, emits one canonical fetch-fault instruction, and does not publish an
L1I refill. Recovery may later discard that retained fault only by changing
the affected STID epoch.

**Consequence.** CTU and OOO receive memory and translation fetch faults through
the same `FetchedInstruction` contract. No cache row or ordinary instruction
may be produced from partial or substituted line data.
