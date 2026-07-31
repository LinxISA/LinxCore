# CTU behavior

## Preserve ordinary instruction transfer {#CTU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-IFU-CTU-001,IFC-CTU-OOO-001 -->

CTU MUST transfer every non-template instruction as one `Encoded64`
front-end operation without changing its instruction identity, PC, raw
64-bit container, length, fetch fault, or prediction metadata.

## Describe template children from the canonical catalog {#CTU-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-CTU-OOO-001,D-IDENTITY-001 -->

CTU MUST recognize template parents from the generated opcode catalog and
emit one ordered, non-recursive `TemplateUop` description for each canonical
child. Every child MUST retain the complete architectural parent and MUST
carry its zero-based ordinal, total child count, row kind, and row-specific
immediate. An unsupported or malformed template range MUST NOT create a
partial child sequence.

## Retain the D1 stream across backpressure {#CTU-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-PREFIX-001,IFC-CTU-OOO-001 -->

CTU MUST preserve program order across parents, template children, input
packets, and output packets. Its Instruction Buffer MUST retain all accepted
operations until OOO accepts them, present at most the configured
`ctuOutputWidth` oldest operations as one continuous prefix, and keep that
prefix stable while OOO is not ready. OOO readiness MUST NOT form a
combinational path to IFU readiness.

## Apply recovery only at the terminal phase {#CTU-004}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=IFC-RECOVERY-001,D-IDENTITY-001 -->

CTU MUST retain and echo a recovery prepare transaction without mutating its
packet, expansion, trace, or Instruction Buffer state. A matching apply MUST
remove retained work for the target STID before any such work can transfer;
unrelated STIDs MUST keep their relative order. A matching abort MUST release
the fence without changing retained work.

## Keep backend allocation outside CTU {#CTU-005}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-020,ARC-TOP-031 -->

CTU MUST NOT allocate or duplicate ROB, BROB, rename-map, issue-queue, or LSU
state. Its template children are descriptions that continue through OOO
D1/D2 validation before D3 performs one backend reservation decision.

## Template recipe mechanism {#MEC-CTU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=CTU-002,CTU-005 -->

`TemplateDecode` filters the generated opcode catalog for FENTRY, FEXIT, and
the two return-template forms. It derives the inclusive architectural
register range from the encoded start and end fields, including wrap from
register 23 to register 2. `TemplateExpand` reuses the canonical template row
definition:

- FENTRY emits validation, stack subtract, one store per range member, then
  the final parent row.
- FEXIT emits validation, stack add, one load per range member, then the final
  parent row.
- The return forms add their required target validation, target publication,
  stack update, restore, and final rows around the ordered load sequence.

The parent raw container remains attached to every child so OOO can validate
the recipe rather than trusting a CTU-local decode copy.

## Retained buffer mechanism {#MEC-CTU-002}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=CTU-001,CTU-003,CTU-004 -->

`InstructionBuffer` stores each operation in one valid slot together with a
monotonic 64-bit enqueue order. It selects the oldest valid entries for the
D1 prefix, allocates new input only from pre-cycle free slots, and therefore
does not bypass dequeue readiness into enqueue readiness. Recovery invalidates
only slots whose retained parent STID matches the accepted plan; remaining
orders are not rewritten.

## CTU trace mechanism {#MEC-CTU-003}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=CTU-002,CTU-004 depends-on=IFC-DTU-001 -->

CTU emits one retained pipeline trace packet when the first child chunk of a
template parent enters the Instruction Buffer. The event carries the parent
identity, PC, parent opcode, and total child count. Trace backpressure may
hold the first child chunk but MUST NOT duplicate it.
