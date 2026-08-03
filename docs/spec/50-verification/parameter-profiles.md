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

## Parameter projection check {#VER-PRM-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=PRM-PROJECTION-001 -->

Focused tests MUST prove that internal parameter projections preserve
applicable width, identity, ROB, rename, queue and pipe sizing without
introducing a stateful hardware owner or alternate public boundary. The
central W8 profile MUST remain constructible through each active projection.

## Directed-simulation capacity profiles

`SimulationParamProfiles` is test-scope configuration for behavior tests that
do not need the full storage window. Its W2/W4/W6/W8 variants preserve the
selected fetch, CTU, decode, rename, D3, dispatch, issue, and retire widths, the
configured execution and LSU pipe counts, and every fixed transaction or
generation width. They may reduce local ROB, BROB, PC Buffer, rename, IQ, and LSU
queue capacities, including the local index widths derived from those
capacities.

The scalar IQ minimum is four entries: with two banks this preserves two
addressable rows per bank and a nonzero reservation-slot width. Wider directed
profiles use the selected width's next power of two.

The W8 directed profile is intentionally capped at sixteen one-instruction ROB
groups, twelve recipe uops per instruction, eight IQ entries, two LQ entries,
two STQ entries, and two store-commit entries. Sixteen ROB groups are the
smallest power-of-two capacity that can expose the nonwrapped tail after one
complete eight-group prefix. Rename and PC capacities remain at the smallest
legal values that retain that prefix. Twelve recipe uops is the minimum
accepted by the current OOO ordinary-group/CTU recipe invariant. This keeps
width and split-store behavior observable without reproducing the
full-capacity RTL volume in every behavioral simulation.

These profiles are not interface-width, full-capacity, generated-RTL, lint,
activation, workload or final-closure evidence. Those gates continue to use
`ParamProfiles.W2/W4/W6/W8`.
