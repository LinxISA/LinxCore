# RecoveryProvenance

## Source Mapping

- Chisel bundle: `chisel/src/main/scala/linxcore/recovery/RecoveryProvenance.scala`
- Arbitration owner: `chisel/src/main/scala/linxcore/recovery/RecoverySourceArbiter.scala`
- Class owner: `chisel/src/main/scala/linxcore/recovery/RecoveryClassMerge.scala`
- Intent owner: `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala`
- Fabric owner: `chisel/src/main/scala/linxcore/recovery/RecoveryFabric.scala`
- Model behavior: `model/LinxCoreModel/model/core/FlushControl.cpp`
- Contract ID: `LC-CHISEL-RECOVERY-PROVENANCE-001`

## Purpose

`RecoveryProvenance` is implementation bookkeeping that keeps retained
producer ownership coherent while `FlushControl` arbitration cancels, merges,
or serializes recovery reports. It is not Linx architectural state and must not
change request age, class, scope, restart identity, or cleanup behavior.

## Fields

| Field | Meaning |
|---|---|
| `causeMask[sourceCount]` | Every source report whose disposition depends on the resident request. |
| `payloadSourceValid` | The request payload has an authoritative source owner. |
| `payloadSource` | Source index that owns the exact request payload and any private sidecars. |

`sourceCount` is a constructor parameter. A full-BID report enters arbitration
with a one-hot cause mask and the same payload-source index. Ring-only
compatibility input carries empty provenance and cannot authorize source-private
sidecars.

## Transformation Rules

1. Ordinary retention, class dispatch, output staging, and cleanup registration
   preserve both fields unchanged.
2. A model `mergeSignal` transformation unions both cause masks. Because the
   merged request copies the destination request payload and changes only its
   recovery type, `payloadSource` follows that copied destination payload.
3. An incoming report dropped by older state or completed-oldest policy emits
   its cause mask on `resolvedMask` without creating cleanup intent.
4. Replacement or cancellation emits every removed resident cause mask on
   `resolvedMask`. A cause moved into a merged request is not resolved early.
5. Consumed cleanup emits the registered cause mask on
   `consumedProvenanceMask` and one-hot payload ownership on
   `consumedPayloadSourceMask`.
6. `RecoveryFabric.sourceResolvedMask` is the union of class-resolution and
   accepted-cleanup masks. Every accepted report must eventually resolve once.

## Sidecar Rule

Source-private order, LSID, predictor, or replay sidecars may be consumed only
when that source's bit is set in `consumedPayloadSourceMask`. A source may
release retained sidecars when its bit is set in `sourceResolvedMask`, including
drop and cancellation. Cause membership alone does not authorize sidecar use.

This distinction is required for merges: multiple reports may cause one cleanup,
but only the source whose exact request payload survives owns the RID/LSID/order
sidecars applied with that cleanup.

## Exclusions

Provenance does not encode ARM exception level, condition flags, exclusive
monitor state, power state, register identity, or instruction semantics. It
does not widen BID or create cross-STID order.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryClassMergeSpec`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControlSpec`
- `bash tools/chisel/run_chisel_recovery_class_merge_probe.sh`
- `bash tests/test_ooo_recovery_class_merge_cross_rtl.sh`

Named scenarios cover dropped-input resolution, cancellation resolution,
merged cause union with destination payload ownership, output backpressure,
consume-and-replace, and accepted-cleanup payload-owner publication.
