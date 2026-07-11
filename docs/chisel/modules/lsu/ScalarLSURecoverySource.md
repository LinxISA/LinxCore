# ScalarLSURecoverySource

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSURecoverySource.scala`
- Parent: `chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala`
- Exact-ROB composition proof:
  `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala`,
  `chisel/src/test/scala/linxcore/recovery/RecoveryCleanupROBProbeSpec.scala`
- Model evidence: `model/LinxCoreModel/model/core/FlushControl.cpp`,
  `FlushControl::CheckFlush`; `model/LinxCoreModel/model/lsu/lsu.cpp`,
  `lsu_flush_rpt_q`
- Contract ID: `LC-CHISEL-LSU-RECOVERY-SOURCE-001`

## Purpose

`ScalarLSURecoverySource` is the LSU-specific boundary between the retained MDB
recovery queue and central recovery arbitration. It performs two operations:

1. checks non-immediate BID/RID eligibility against the caller-supplied oldest
   scalar ROB watermark;
2. recovers the allocator-stamped full block generation through exact resident
   ROB lookup and publishes one `FullBidFlushReq`.

It does not select against BCC, IEX, PE, or other LSU sources. It does not
register cleanup intent or mutate ROB, BROB, rename, frontend, LSU, or cache
state. Those are central `RecoverySourceArbiter` and `RecoveryCleanupControl`
responsibilities.

## Interface

| Direction | Signal | Description |
|---|---|---|
| input | `ringReq` | Retained typed MDB report with Linx BID/GID/RID/LSID and scope. |
| output | `ringReqReady` | Upstream report may dequeue; asserted only when the promoted source is accepted. |
| input | `oldestValid/oldestBid/oldestRid` | Scalar STID oldest/head watermark. |
| output | `fullBidLookupRequest` | Exact RID-indexed ROB lookup key. |
| input | `fullBidLookup` | Echoed exact lookup result and allocator-stamped block generation. |
| output | `source` | Eligible, exactly promoted full-BID recovery source. |
| input | `sourceReady` | Per-source readiness from `RecoverySourceArbiter`. |
| output | `sourceAccepted` | Full source transferred and the retained MDB head may dequeue. |
| output | `eligible`, `blockedByNoOldest`, `blockedByAge` | Age diagnostics. |
| output | `lookupMatched`, lookup blocker signals | Exact identity diagnostics. |

## Retention Contract

The upstream MDB recovery queue remains the storage owner. A report advances
only when all conditions are true in one cycle:

- immediate recovery or wrap-qualified oldest BID/RID eligibility;
- exact echoed `(BID,GID,RID,PE,STID,TID)` match to a resident ROB row;
- valid allocator-stamped full block generation;
- full generation projects to the report's ring BID;
- the central arbiter accepts this LSU source slot.

Any failed condition keeps `ringReqReady` low. There is no weaker ring-only
fallback and no local full-over-MDB priority mux.

## Linx Adaptation

The mechanism preserves ISA-neutral report retention, precise-head gating, and
implementation-generation recovery. Identity and scope are Linx BID, GID, RID,
LSID, PE, STID, and TID. The module defines no ARM exception, register-bank,
condition-code, exclusive-monitor, or memory-ordering architectural behavior.

## Integration Status

R640 instantiates this module under production `ScalarLSU` and in the exact
real-ROB generated recovery path. The latter connects its source directly to
`RecoverySourceArbiter`, then `RecoveryCleanupControl`, and proves retained
lookup blockers, full-generation promotion, multi-source ordering, and scoped
ROB pruning.

Canonical backend top wiring still must connect `fullBidLookupRequest/result`,
all BCC/IEX/PE/LSU source slots, and cleanup consumers. `LinxCoreTop` remains a
reduced shell with `ReducedCommitROB`, so it exports this source boundary rather
than pretending to own the missing real-ROB connection.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSU`
- `bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupROBProbeSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`

R640 passes 251 suites and 1,483 tests. The generated real-ROB path and canonical
MDB probe pass. The canonical top cross-check passes 3 rows with zero
mismatches. Reduced CoreMark passes 426 rows with zero mismatches and zero
CBSTOP at
`generated/r640-final-scalar-lsu-recovery-source-coremark/report/crosscheck_manifest.json`
as no-regression evidence, not natural recovery activation.
