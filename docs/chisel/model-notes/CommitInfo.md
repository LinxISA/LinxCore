# CommitInfo Model Notes

## Source Mapping

- LinxCoreModel: `model/LinxCoreModel/model/interface/CommitInfo.h`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitIdentity.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTrace.scala`

## Model Contract

`CommitInfo` is a value identity tuple with `bid`, `gid`, and `rid`, each stored
as `uint32_t` in the C++ model. Equality requires all three fields to match.
The hash combines the three fields but does not define architectural ordering.

## Hardware Direction

The Chisel `CommitIdentity` bundle carries the same three 32-bit fields and is
embedded in `CommitTraceRow` as `identity`. It is only an identity payload.
Commit ordering is owned by ROB/BROB/FlushControl logic and must not be inferred
from the hash behavior in the model.

The hardware block identity used by BROB/BCTRL remains a separate 64-bit
`blockBid` sideband in `CommitTraceRow`. Do not truncate the 64-bit hardware BID
into the model `CommitInfo.bid` field. The normalized QEMU adapter preserves
both domains: `bid/gid/rid` for model identity and `block_bid` for hardware BID
triage.

## Open Items

- The reduced ROB harness still needs live row production, duplicate identity
  checks, skipped-slot checks, and memory/trap ownership assertions.
