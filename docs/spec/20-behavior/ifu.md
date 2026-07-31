# IFU behavior

## Deliver complete variable-length instructions {#IFU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IFU-CTU-001,PRM-WIDTH-001 -->

IFU MUST assemble every accepted 2-, 4-, 6-, or 8-byte instruction into one
64-bit container before transfer to CTU. The configured W2, W4, W6, or W8
delivery width is a maximum continuous prefix; a shorter retained prefix MAY
be transferred without padding it with invalid instructions.

## Retain exact I-SIDE miss identity {#IFU-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-MEMORY-001,D-IDENTITY-001 -->

Every translation request, cache-line beat, refill, retry, and cross-line
continuation MUST retain a generation-qualified transaction identity.
An unmatched memory response MUST be drained as stale and MUST NOT mutate a
live translation, line fill, cache row, or fetched instruction. A denied or
corrupt instruction-line response MUST become one canonical fetch fault with
the returned error cause and MUST NOT install zero or partial data in L1I.

## Isolate fetch geometry from CTU backpressure {#IFU-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IFU-CTU-001,D-PREFIX-001 -->

The Fetch Buffer MUST retain accepted instructions in program order and hold
the complete visible packet stable while CTU is not ready. CTU readiness MUST
NOT form a combinational path to I-SIDE cache lookup, translation lookup, or
external memory-request readiness.

## Apply scoped recovery after prepare {#IFU-004}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-RECOVERY-001,D-IDENTITY-001 -->

IFU MUST echo an accepted recovery prepare without mutating I-SIDE state. A
matching apply MUST fence IFU-to-CTU transfer, remove retained instructions for
the selected STID, redirect that STID, and reject older-epoch returns. A
matching abort MUST release the fence without removing retained instructions.

## Join prediction before architectural delivery {#IFU-005}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IFU-CTU-001 -->

I-SIDE and prediction MAY run independently, but an instruction MUST NOT enter
the canonical Fetch Buffer until its transaction has a final prediction
sidecar or an explicit sequential result. Later prediction correction MUST use
the same recovery path as every other IFU redirect.

## Keep B-SIDE prediction speculative {#IFU-008}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFU-005,IFU-004,D-IDENTITY-001 -->

B-SIDE MUST be the only IFU predictor state owner. I-SIDE and B-SIDE are
independent and non-lockstep; matching across the engines MUST use STID,
request identity, generation or epoch, and checkpoint identity. Speculative
provider order is B-F4, B-F3, B-F2, B-F1, B-F0, then sequential. Backend typed
recovery is not a prediction provider and MUST override any unpublished
prediction correction through the recovery path.

## Leave backend identity allocation to OOO {#IFU-006}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IFU-CTU-001,IFC-CTU-OOO-001,ARC-TOP-031 -->

IFU MUST attach architectural instruction identity, fetch epoch, prediction
checkpoint, target, and fetch-fault information. IFU and CTU MUST NOT allocate
ROB or BROB residency. OOO D1/D2 validates the encoded or template operation
and OOO D3 allocates the complete backend identity.

## Keep trace observational {#IFU-007}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-DTU-001,IFC-IFU-CTU-001 -->

IFU trace backpressure MUST NOT stall instruction assembly, Fetch Buffer
dequeue, or IFU-to-CTU transfer. Once a trace packet is presented as valid it
MUST remain stable until accepted; later events MAY be dropped while that
single retained observation is blocked.

## Parameterized retained delivery mechanism {#MEC-IFU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-001,IFU-003 -->

The line assembler produces a dense private instruction group. `FetchBuffer`
stores each member with a monotonic local order and presents at most
`fetchWidth` oldest members as `count + Vec`. New input uses only pre-cycle
free slots, so dequeue readiness cannot bypass into the upstream ready path.

## Translation and line-fill mechanism {#MEC-IFU-002}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-002 depends-on=MEC-MEMORY-001 -->

ITLB and L1I lookup remain parallel retained owners. A miss records the
complete fetch request before issuing memory traffic. The memory adapter marks
translation and instruction-line requests with distinct `MemoryAccessKind`
values, converts a cache line to ordered 64-bit beats, and accepts a response
only when both transaction value and generation match the retained request.
An erroneous line beat terminates assembly, retains the original fetch request
and error cause, and enters the same canonical fault packet path as a
translation access fault.

## Prediction rendezvous mechanism {#MEC-IFU-003}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-005 -->

The prediction join retains I-SIDE groups and prediction updates under the
same fetch transaction. It publishes groups in order only after I-SIDE
completion and final prediction availability. Correction and terminal steering
remain explicit events rather than edits to already transferred packets.

## B-SIDE prediction mechanism {#MEC-IFU-006}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-008 -->

`linxcore.ifu.BSide` is the public owner boundary for B-F0 through B-F4 and
reuses the existing retained predictor/history implementation as its body.
BTB, BIM, TAGE, loop, RAS, GHR, response, training, and checkpoint recovery
state MUST remain below that one boundary. `linxcore.ifu.IFURecovery` is the
canonical redirect arbiter, and `linxcore.ifu.IFUBackendFeedback` converts
OOO-authored validation into paired training and typed backend recovery. IFU
MUST NOT expose a direct IEX control input.

## IFU recovery mechanism {#MEC-IFU-004}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-004,IFU-006 -->

Prepare retains the exact plan and fences no state. Apply prunes the canonical
Fetch Buffer immediately, retains the redirect until the I-SIDE accepts it,
and keeps IFU-to-CTU transfer fenced throughout that interval. Physical line
requests already issued before recovery may drain as orphans; their identity
cannot recreate killed architectural work.

## Non-blocking trace mechanism {#MEC-IFU-005}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFU-007 -->

One retained trace packet isolates the Decoupled trace endpoint. A new
IFU-to-CTU fire fills an empty trace slot or replaces a trace accepted in the
same cycle. When the slot is blocked, the trace payload remains stable and new
observations are dropped without changing Fetch Buffer readiness.
