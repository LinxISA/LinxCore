# LinxCore contract glossary

## Box {#D-BOX-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->

A **box** is one of TOP, IFU, CTU, OOO, IEX, LSU, or DTU. A box owns its local
state and exposes typed transactions; it does not reach into another box's
state.

## Transaction fire {#D-FIRE-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->

A transaction **fires** when both `valid` and `ready` are true in the same
cycle. A state owner may consume or allocate state only on the fire it owns.

## Continuous prefix packet {#D-PREFIX-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->

A **continuous prefix packet** represents lanes `[0, count)` with lane 0 oldest.
No valid lane may appear after an invalid lane. Any mask used for observation
is derived from `count`, not maintained as independent state.

## Instruction and micro-operation identities {#D-IDENTITY-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->

`bid` identifies the block context, `rid` identifies the ROB allocation, and
`uid` distinguishes micro-operations belonging to one instruction. `atag` is
the architectural register identity; `ptag`, `ttag`, and `utag` are renamed
physical or relative identities.

## Recovery prepare and apply {#D-RECOVERY-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->

**Prepare** stops new affected work and collects owner acknowledgements.
**Apply** changes architectural visibility and releases or restores state.
Separating the phases prevents one owner from observing a partially recovered
machine.
