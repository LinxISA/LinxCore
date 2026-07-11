# ScalarLSU

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala`
- Parameters: `chisel/src/main/scala/linxcore/common/CoreParams.scala`
- Store child: `chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Load child: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- MDB child: `chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala`
- Recovery-source child:
  `chisel/src/main/scala/linxcore/lsu/ScalarLSURecoverySource.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala`
- Model reference: `model/LinxCoreModel/model/core/Core.cpp`,
  `Core::BuildScalarLSU`; `model/LinxCoreModel/model/lsu/lsu.h`,
  `LoadStoreUnit`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-RES-001`

## Purpose

`ScalarLSU` is the canonical Chisel ownership boundary for scalar memory
execution. R633 moved the proven STQ-to-SCB store composition beneath this
owner. R634 adds canonical LIQ-to-ResolveQ ownership with shared typed recovery.
R635 integrates scalar MDB conflict detection, SSIT/fanout, live wait mutation,
and typed conflict-flush publication. R636 adds per-row failed-wait age plus
atomic LIQ release and MDB delete feedback. R637 adds a parameterized retained
recovery-report boundary and wrap-qualified oldest BID/RID eligibility. R638
adds exact allocator/ROB full-BID lookup and promotion. R639 proves the central
multi-source arbiter. R640 removes local cleanup selection from ScalarLSU and
publishes its exactly promoted MDB report through `ScalarLSURecoverySource`.
Cache and miss queues, canonical-top source/lookup wiring, and final load-return
publication remain outside this hierarchy, so this is not yet a complete LSU.

## Parameter Contract

`CoreParams.robEntries` controls the slot-plus-wrap width of BID, GID, RID,
LSID, and typed flush identities. `ScalarLsuParams` independently controls:

- `stqEntries`
- `commitQueueEntries` and `commitIssueWidth`
- `scbEntries` and `scbResponseBufferDepth`
- `liqEntries` and `resolveQueueEntries`
- MDB SSIT, command, output, retained wait-plan, and recovery-report queue entries
- `mdbFailedWaitTimeoutCycles`
- MDB confidence/weight policy
- `loadSizeWidth`, architectural/physical register-tag widths, and PC width
- address, data, PE, STID, TID, size, and SIMT-lane widths
- `lineBytes` and `mapQDepth`

The STQ family retains backward-compatible constructors, but an explicit
`robEntries` value now propagates through insert probing, flush pruning, row
storage, commit ordering, and drain requests. STQ capacity no longer determines
architectural identity width.

## Linx Adaptation

The owner preserves ISA-neutral queueing, merge, drain, response buffering,
and cache-line coalescing mechanisms. Recovery uses Linx `FlushBus` scopes and
wrap-qualified BID/GID/RID/LSID sidecars. The module has no ARM exception-level,
exclusive-monitor, flags, barrier-encoding, or acquire/release instruction
state.

## Current Composition

The `store` child `STQSCBCommitPath` owns speculative STQ rows, ordered committed-store
drain, SCB coalescing/state, and buffered raw responses. An SCB-accepted final
fragment is the only committed-row free source. `LinxCoreTop.idle` now requires
an empty reduced ROB, STQ, commit-drain queue, SCB row bank, and SCB response
buffer. The `load` child `ScalarLSULoadPath` owns active LIQ rows, resolved
load records, shared typed pruning, transfer credit, and source-row clear.
It also owns `ScalarLSUMDBPath`; accepted address-bearing stores are capacity
gated before conflict publication, and accepted scalar loads enter MDB lookup
before launch. Stable failed predictions age per LIQ row and release only with
accepted SSIT delete enqueue. Top-level idle also requires MDB transient queues
and retained wait/wakeup/recovery state to be empty.

`ScalarLSU` connects the retained MDB report to
`ScalarLSURecoverySource`. The caller supplies the source-STID oldest BID/RID
watermark, exact ROB lookup result, and central source readiness. Non-immediate
reports remain queued until age, identity echo, full-sideband validity, ring
projection, and arbiter acceptance all pass. Lookup failure cannot fall back
to ring-only cleanup.

The LSU exports `FullBidFlushReq source` and does not instantiate
`RecoveryCleanupControl`. It therefore cannot impose full-over-MDB or any other
competing-source priority. `RecoverySourceArbiter` owns retained producer
selection; `RecoveryCleanupControl` owns the selected registered intent and
side-effect fanout.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- focused STQ, drain, insert-probe, and flush-prune suites
- `bash tools/chisel/run_chisel_top_xcheck.sh`

R635 elaborates a 32-entry ROB identity with independently sized STQ, LIQ,
ResolveQ, SSIT, MDB command/output queues, and wait-plan queue. Focused tests
cover typed conflict recovery, retained multi-row waits, lookup hold-until-
mutation, and pre-launch `Wait` row mutation. This remains partial LSU evidence
until cache/miss, final recovery, and load-return owners are integrated.

R636 adds `LoadWaitStoreTimeoutSpec` and extends the canonical generated-RTL
probe through three timeout-driven delete commands: two SSIT weight decays and
one final release. The implementation uses deterministic row-keyed age instead
of the model's ineffective shared `oldestPending` counter enable. The full
suite passes 247 suites and 1,466 tests; canonical top xcheck passes 3 rows
with zero mismatches; and reduced CoreMark passes 665 rows with zero mismatches
at `generated/r636-final-failed-wait-coremark/report/crosscheck_manifest.json`.

R637 proves report retention in the canonical MDB generated-RTL lane and a
separate `RecoveryCleanupControl` to real `ROBEntryBank` path. Ring-only input
never asserts full-BID BCTRL/BROB cleanup authority. The generated path also
proves non-oldest retention, full-over-ring priority, and consume-plus-replace.
The real ROB prune also preserves a younger different-STID row. The full suite
passes 249 suites and 1,474 tests; canonical top xcheck passes 3
rows with zero mismatches; and reduced CoreMark passes 426 rows with zero
mismatches at
`generated/r637-final-mdb-recovery-coremark/report/crosscheck_manifest.json`.

R638 adds focused `ROBFullBidLookupSpec` and
`RingFullBidRecoveryBridgeSpec`. The evolved generated recovery probe rejects a
wrong RID, recovers allocator-stamped full BIDs, enables block cleanup only
after exact promotion, and retains the different-STID row during ROB prune.
The full suite passes 251 suites and 1,481 tests; canonical top xcheck passes 3
rows with zero mismatches; and reduced CoreMark passes 426 rows with zero
mismatches and zero CBSTOP at
`generated/r638-final-full-bid-recovery-coremark/report/crosscheck_manifest.json`.

R639 extends the real-ROB generated probe with `RecoverySourceArbiter` and
proves simultaneous admission, same-STID oldest selection, losing-source
retention, invalid-STID rejection, and cross-STID fairness. This is harness
evidence; production ScalarLSU-to-central-arbiter wiring remains open.

R640 extracts `ScalarLSURecoverySource`, removes LSU-local cleanup arbitration,
and uses that same production source owner in the real-ROB generated path ahead
of `RecoverySourceArbiter`. Focused elaboration rejects any
`RecoveryCleanupControl` child beneath ScalarLSU.
The full suite passes 251 suites and 1,483 tests; canonical top xcheck passes 3
rows with zero mismatches; and reduced CoreMark passes 426 rows with zero
mismatches and zero CBSTOP at
`generated/r640-final-scalar-lsu-recovery-source-coremark/report/crosscheck_manifest.json`.
