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

## Migration adapters are value-only {#PRM-ADAPTER-001}
<!-- ndf: kind=arch level=must layer=L2 status=draft since=0.1 depends-on=PRM-VALIDATION-001 -->

Temporary adapters between the central parameter hierarchy and legacy
parameter records MUST convert immutable values only. They cannot own hardware
state, and they are removed with the old chain.
