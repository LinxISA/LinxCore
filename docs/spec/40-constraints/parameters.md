# Core parameter contracts

## Principal lane-width profile {#PRM-WIDTH-001}
<!-- ndf: kind=option level=may layer=L2 status=stable since=0.1 -->
<!-- ndf: default=4 explore=2,4,6,8 unit=lanes couples-with=PRM-RESOURCE-001 -->

The maintained profiles MAY select 2, 4, 6, or 8 lanes. The selected profile
sets IFU fetch and CTU transfer width, CTU input and output width, OOO decode,
rename, D3-prefix, dispatch and retire width, and IEX issue width. W4 is the
default; every listed value remains an elaboration target.

## Widths have one configuration source {#PRM-WIDTH-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=PRM-WIDTH-001 -->

Every box-local principal width MUST equal the corresponding value in the
central `WidthParams`. A box-local copy cannot silently select a different
profile.

## Adjacent widths preserve an accepted prefix {#PRM-WIDTH-003}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=PRM-WIDTH-001,D-PREFIX-001 -->

IFU fetch capacity MUST cover the CTU output prefix, CTU input and output
capacity MUST cover their configured transfer, and OOO dispatch capacity MUST
cover the complete accepted D3 prefix. An unsupported or under-provisioned
combination MUST fail before hardware elaboration.

## Execution and LSU resource topology {#PRM-RESOURCE-001}
<!-- ndf: kind=option level=may layer=L2 status=stable since=0.1 -->
<!-- ndf: default=W4-2ALU-1BRU-2AGU-2STD-1SYS-1CMD-2LD-2ST explore=positive-validated-counts unit=instances couples-with=PRM-WIDTH-001 -->

Execution-unit, issue-queue, load-pipe and store-pipe counts are explicit
parameters rather than aliases for lane width. Resource exploration MAY vary
positive counts subject to the cross-parameter capacity rules.

## Default resource topology {#PRM-RESOURCE-002}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=PRM-RESOURCE-001 -->

The default profile MUST provide two ALU pipes, one BRU pipe, two AGU pipes,
two STD pipes, one system/multicycle queue, one independent CMD issue queue,
two load pipes and two store pipes.

## Central validation precedes publication {#PRM-VALIDATION-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=PRM-WIDTH-002,PRM-WIDTH-003,PRM-RESOURCE-002 -->

A `CoreParams` value MUST pass centralized width, identity, storage and
resource validation before it is published to a module or interface
generator. Invalid widths, zero physical paths, insufficient queue capacity,
non-power-of-two identity stores, singleton BROB rings, and incompatible
adjacent widths MUST fail with a configuration error. BROB entries per STID
MUST be a power of two and at least two so native BID width is nonzero.

## PC buffer geometry {#PRM-PC-BUFFER-001}
<!-- ndf: kind=option level=may layer=L2 status=stable since=0.1 depends-on=PRM-WIDTH-001,PRM-VALIDATION-001 -->
<!-- ndf: default=64-entry-3W-6R-3-replica unit=entries-and-ports -->

The OOO PC buffer has independently configured entry、bank、write-port、
read-port、replica、PC-offset、allocation-epoch and recovery-scan parameters.
The maintained W2/W4/W6/W8 profiles use 64 entries, three PC-base write ports,
six readyless read ports and three replicas. W2/W4 use four banks; W6/W8 use
eight banks so one retire prefix remains bank-coverable. A wider decode prefix
does not create extra PC-base write ports: D1/D2 admission must stop before a
packet would require more than three new PC bases.

## Parameter projections own no state {#PRM-PROJECTION-001}
<!-- ndf: kind=arch level=must layer=L2 status=draft since=0.1 depends-on=PRM-VALIDATION-001 -->

`CoreParams` is the sole public configuration source. An internal mechanism
may project immutable geometry from it, but that projection MUST own no
hardware state, create no alternate public boundary and change no transaction
semantics.

## LSU capacities and identities remain independent {#PRM-LSU-SIZING-001}
<!-- ndf: kind=req level=must layer=L1 status=draft since=0.1 depends-on=PRM-RESOURCE-001,PRM-PROJECTION-001 -->

Load-pipe count、load-return-pipe count、LIQ depth、STQ depth、ROB identity
capacity and full-LSID width MUST remain independently configured. The W4
topology uses two load pipes and two store pipes; no forwarding helper may
infer a third load pipe. Unequal `16 STQ / 8 ROB / 40-bit LSID` geometry MUST
elaborate without truncating an identity or changing queue ownership.

## Directed simulation may bound storage only {#PRM-SIMULATION-001}
<!-- ndf: kind=option level=may layer=L2 status=draft since=0.1 depends-on=PRM-WIDTH-001,PRM-LSU-SIZING-001 -->

Directed W2/W4/W6/W8 tests MAY use `SimulationParamProfiles` to reduce local
ROB、rename、IQ、LIQ、STQ and cache-related storage. Those profiles MUST preserve
the selected principal widths、the two-load/two-store topology and fixed
identity-field widths. They cannot replace main-profile interface、capacity、
generated-RTL、timing or workload evidence.
