# Parameter-profile verification

## Principal profile elaboration check {#VER-PRM-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=PRM-WIDTH-002,PRM-WIDTH-003 -->

`CoreConfigurationSpec` MUST construct W2, W4, W6 and W8, compare every
box-local principal width with `WidthParams`, and reject unsupported or
under-provisioned combinations before elaboration.

## Resource topology and validation check {#VER-PRM-002}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=PRM-RESOURCE-002,PRM-VALIDATION-001 -->

`CoreConfigurationSpec` MUST check the complete default IEX and LSU topology
and MUST exercise failure cases for invalid profile width, adjacent-width
capacity, D3-prefix dispatch capacity, zero load/store pipe counts, and a
singleton BROB ring that would otherwise publish zero-width native BID fields.
It MUST keep small legal BROB rings and the default 256-entry ring as explicit
native BID width checks.

## Adapter round-trip check {#VER-PRM-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=PRM-ADAPTER-001 -->

Focused tests MUST prove that temporary parameter adapters preserve applicable
width, identity, ROB, rename, queue and pipe sizing without introducing a
stateful hardware owner. The central W8 profile MUST remain constructible
through the OOO adapter while it exists.
