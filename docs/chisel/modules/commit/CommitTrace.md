# CommitTrace Packet A

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitIdentity.scala`
- Previous pyCircuit owner: `src/probes/*`, `src/bcc/backend/commit.py`
- LinxCoreModel evidence: `model/LinxCoreModel/model/interface/CommitInfo.h`
- Contract IDs: `LC-IF-CHISEL-XCHK-001`

## Purpose

Packet A introduces only the identity part of the commit/cross-check payload.
The neutral Phase 0B cross-check adapter will extend this into the full
commit-row schema used by QEMU and LinxCore.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| payload | `bid` | `UInt(32.W)` | none | Block identity component from model `CommitInfo`. |
| payload | `gid` | `UInt(32.W)` | none | Group identity component from model `CommitInfo`. |
| payload | `rid` | `UInt(32.W)` | none | Row/reorder identity component from model `CommitInfo`. |

## State

No state is owned by this bundle. ROB, BROB, and commit control own production
and ordering.

## Logic Design

`CommitIdentity` is a passive bundle. Equality and ordering are intentionally
not encoded here. Ordering belongs to ROB/BROB/FlushControl and must use the
appropriate identity domain.

## Timing

The bundle is emitted with the commit slot that fires in a commit cycle. Later
Phase 0B work will define the commit slot valid mask and multi-commit ordering.

## Flush/Recovery

Flush metadata must carry enough identity to distinguish surviving and killed
rows. This packet only supplies the `(bid, gid, rid)` tuple.

## Trace/Observability

The tuple is the first bridge between Chisel commit events and the existing
QEMU/DUT trace comparator.

## Verification

- Source presence is checked by `tools/chisel/robid_semantics_check.py`.
- Full row schema verification is deferred to `tools/chisel/trace_schema_adapter.py`.
