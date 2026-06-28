# CommitInfo Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/interface/CommitInfo.h`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitIdentity.scala`

## Model Contract

`CommitInfo` is a value identity tuple with `bid`, `gid`, and `rid`, each stored
as `uint32_t` in the C++ model. Equality requires all three fields to match.
The hash combines the three fields but does not define architectural ordering.

## Hardware Direction

The Chisel `CommitIdentity` bundle carries the same three 32-bit fields. It is
only an identity payload. Commit ordering is owned by ROB/BROB/FlushControl
logic and must not be inferred from the hash behavior in the model.

## Open Items

- The first packet does not yet define the full commit slot payload. Memory
  side effects, traps, next-PC, and trace fields will be added through the
  Phase 0B neutral cross-check adapter.
