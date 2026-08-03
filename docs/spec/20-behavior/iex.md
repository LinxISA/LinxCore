# IEX behavior

## Retain classed dispatch until one exact terminal fire {#IEX-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-OOO-IEX-001,D-IDENTITY-001 -->

IEX MUST accept the canonical ALU, branch, load/store address, store-data,
system, and CMD dispatch classes without translating them through a reduced
issue boundary. Backpressure MUST preserve the complete transaction and its
`RobIdentity`. A completed instruction MUST update ROB resolve, every required
register-file destination, wakeup, trace, and recovery output in one atomic
terminal transaction; no consumer may observe a partial fire.

## Allocate memory identities before LSU admission {#IEX-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IEX-001,IFC-IEX-LSU-001,D-IEX-LSU-LOAD-001 -->

IEX MUST allocate a non-reused memory transaction when a memory uop becomes
resident. One logical store shares that transaction across its STA and STD
children. A load also receives its initial attempt generation on the same
Dispatch-to-IQ acceptance edge. LSU backpressure, cancellation, and recovery
MUST NOT rewind either serial.

The architectural P-register boot map is initialized before dispatch becomes
eligible. Every STID begins with architectural tag `n` mapped to physical tag
`n`, generation zero; no classed dispatch may observe a partially initialized
map.

The public IEX-to-LSU boundary carries semantic identities only. Physical LIQ
or STQ indices, lease generations, and private queue rows MUST remain inside
LSU. Store reservation MUST succeed before the associated STA/STD pair becomes
eligible to issue.

## Rebind and complete only the retained load attempt {#IEX-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IEX-002,IFC-RECOVERY-001 -->

IEX MUST accept load reissue, repick, cancel, launch, and result events only
for the exact retained `(RobIdentity, memory transaction, LSID, attempt
generation)` tuple. Reissue and repick advance the attempt once and may select
a new retained pipe. Stale, unknown, or skipped-generation events MUST NOT
write a register, wake a dependent uop, resolve ROB, or emit memory traffic.

The retained replay policy is Option A: IEX owns the unchanged memory
transaction and advances exactly one attempt generation, while LSU atomically
rebinds its existing allocation. One accepted rebind publishes one cancel for
the old attempt and one new unique-attempt launch. Physical LIQ passes are
counted separately from unique `{transaction, attempt}` launches and from
lower-memory requests. A refill may therefore cause another physical pass for
the same new attempt without creating another attempt, cancel, or request.
Only an exact canceled predecessor with the same live row, transaction, pipe,
destination, and well-formed outcome may be consumed as stale; future,
skipped, malformed, wrong-row, wrong-transaction, and wrong-pipe returns remain
rejected and protocol-visible.

Recovery Prepare MUST fence only the affected STID without mutation. Apply
MUST prune matching IEX metadata; Abort MUST preserve it. A peer STID remains
eligible throughout the transaction.

## Keep system and CMD ownership separate {#IEX-004}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IEX-001,D-IEX-SYS-CMD-001 -->

System/multicycle and CMD operations MUST remain in independent resident
queues. A system side effect requires the exact OOO head permit and exits via
`SystemIssueTxn`, which OOO forwards losslessly to TOP. CMD leaves IEX only
through `CmdIssueTxn`. Generated CMD recipes remain CMD-class,
`ENGINE_COMMAND`-capable, commit-owned, and nonspeculative; they MUST NOT be
reclassified into the System queue. For both classes, the permit, side effect, no-value ROB
resolve, execution trace, and resident-owner release MUST fire atomically. A
stalled CMD MUST
NOT block an older branch recovery, and permit withdrawal MUST prevent a head
side effect from firing. An open-current branch recovery that kills the
original closing marker MUST drain killed ROB/BROB state while retaining the
survivor PC base for a redirected-epoch closing marker; only that redirected
closure permits the PC owner to drain. A killed CMD MUST remain unable to
publish after its external sink is later released.

## Canonical public composition {#MEC-IEX-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IEX-001,IEX-002,IEX-003,IEX-004 -->

`linxcore.iex.IEX` is a state-free public shell around the retained
`OooIexExecutionPipeline`. The private issue queues, operand files, functional
pipelines, load metadata, and terminal rendezvous remain the sole owners of
their state. The shell projects only canonical `OOOIEXIO`, `IEXLSUIO`, CMD,
and trace transactions; OOO losslessly projects System issue to its public
side-effect boundary.

## IEX verification {#VER-IEX-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IEX-001,IEX-002,IEX-003,IEX-004 -->

`IEXIssueSpec`, `IEXPipesSpec`, and `IEXTerminalSpec` MUST cover stable
backpressure, complete identity retention, the W4 2-ALU/1-BRU/2-AGU/2-STD
topology, independent system/CMD queues, exact load lifecycle transitions,
continuous oldest-first terminal transport, and atomic terminal behavior.
`OOOIEXLSUActivationSpec` and the generated-RTL
activation probe MUST observe nonzero activation for every W4 execution class
before a displaced owner is deleted. The activation evidence MUST distinguish
unique attempt launches, raw LIQ passes, and lower-memory requests, and MUST
finish with zero mismatches.
