# ROBID Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/ROBID.h`
- LinxCoreModel: `model/LinxCoreModel/model/ModelCommon/ROBID.cpp`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
- Gate: `bash rtl/LinxCore/tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only`

## Model Contract

`ROBID` is a reorder identity with three fields: `valid`, `wrap`, and `val`.
`valid` can disable an identity. `wrap` is the age epoch. `val` is the slot
inside the reorder structure.

The model arithmetic is single-wrap within one structure size:

- increment moves `val + 1` when it remains below the structure size;
- increment at the last entry toggles `wrap` and returns to slot zero;
- add/sub offsets are asserted to be smaller than the structure size;
- add/sub toggle `wrap` only when the operation crosses the circular boundary.

The model comparison is wrap-aware:

- same `wrap`: lower `val` is older;
- different `wrap`: higher `val` is older.

`CalGap(newer, older, size)` returns `newer.val - older.val` when the wrap bits
match, otherwise `(newer.val + size) - older.val`.

## Hardware Direction

The Chisel `ROBID` bundle preserves the model fields and exposes helper methods
for add, increment, subtract, equality, ordering, and gap calculation. The
helpers are combinational and do not allocate state. State owners such as ROB,
BROB, LIQ, LHQ, STQ, and SCB must instantiate registers around this bundle in
their own modules rather than hiding ownership inside the helper.

## Open Items

- The first packet covers one-level ROBID arithmetic. The two-level and
  three-level `(bid, gid, rid)` ordering helpers must be added when integrated
  commit and block control use them.
- The full Chisel compile/test gate is blocked in the current environment until
  a JDK and `sbt` are available.

## Skill Evolve

`skill-evolve: no-update` for this packet. The ROBID wrap and comparison rules
are already consistent with existing `linx-core` guidance and are captured here
as module-local documentation.
