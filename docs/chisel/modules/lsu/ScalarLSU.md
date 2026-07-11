# ScalarLSU

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala`
- Parameters: `chisel/src/main/scala/linxcore/common/CoreParams.scala`
- Store child: `chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Load child: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala`
- Model reference: `model/LinxCoreModel/model/core/Core.cpp`,
  `Core::BuildScalarLSU`; `model/LinxCoreModel/model/lsu/lsu.h`,
  `LoadStoreUnit`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-RES-001`

## Purpose

`ScalarLSU` is the canonical Chisel ownership boundary for scalar memory
execution. R633 moved the proven STQ-to-SCB store composition beneath this
owner. R634 adds canonical LIQ-to-ResolveQ ownership with shared typed recovery
and connects both `store` and `load` boundaries to `LinxCoreTop`. MDB, cache and
miss queues, live row mutation, and final load-return publication remain
outside this hierarchy, so this is not yet a complete LSU.

## Parameter Contract

`CoreParams.robEntries` controls the slot-plus-wrap width of BID, GID, RID,
LSID, and typed flush identities. `ScalarLsuParams` independently controls:

- `stqEntries`
- `commitQueueEntries` and `commitIssueWidth`
- `scbEntries` and `scbResponseBufferDepth`
- `liqEntries` and `resolveQueueEntries`
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
Top-level idle also requires the load path to be empty.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- focused STQ, drain, insert-probe, and flush-prune suites
- `bash tools/chisel/run_chisel_top_xcheck.sh`

R634 elaborates a 32-entry ROB identity with independent 8-entry STQ, 4-entry
LIQ, and 8-entry ResolveQ capacities. Focused tests cover typed LIQ pruning and
the composed load hierarchy. This remains partial LSU evidence until MDB,
cache/miss, and load-return owners are integrated.
