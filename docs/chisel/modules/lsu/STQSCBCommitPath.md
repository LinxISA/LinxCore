# STQSCBCommitPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQSCBCommitPathSpec.scala`
- Child state owners:
  - `STQEntryBank.scala`
  - `STQMemoryAttributeOwner.scala`
  - `STQCommitDrain.scala`
  - `STQCommittedStoreSerializer.scala`
  - `SCBRowBank.scala`
- Contract ID: `LC-CHISEL-LSU-STQ-SCB-COMMITPATH-001`

## Purpose

`STQSCBCommitPath` composes the committed-store path from exact ROB ownership
through memory classification and class-dependent completion. It does not
guess the memory type in decode. A translation/PMA result is retained beside
the live STQ residency before ROB commit may promote the row.

The composition has two mutually exclusive terminal paths:

- `NormalCacheable` stores enter `SCBRowBank`; accepted final fragments release
  their STQ rows and complete the logical store.
- `NormalNonCacheable` and `DeviceMmio` stores enter
  `STQCommittedStoreSerializer`; only the last exact external response releases
  every participating row and completes the logical store.

`Unknown` and `Fault` fail closed at ROB commit ingress. The module does not yet
own a real DTLB/PMA adapter, uncached/device interconnect, Device-load path, or
atomic/fence ordering.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `flush` | Speculative STQ recovery cleanup. |
| `insertValid/insert` | Store dispatch allocation into the STQ bank. |
| `memoryClassify` | Generation- and owner-qualified translation/PMA result. |
| `robStoreCommit` | Canonical ROB store token without a physical STQ index. |
| `markCommit*` | Migration-only index commit port. |
| `issueEnable` | Enables memory-side drain launch. |
| `evictEnable` and DCache/L2 inputs | Cacheable SCB egress controls. |
| `rawResp*` | Raw cacheable WriteResp/UpgradeResp stream. |
| `serializedRequest.ready` | Uncached/device transport request readiness. |
| `serializedResponse` | Exact uncached/device transaction response. |

### Outputs

| Signal | Description |
|---|---|
| `memoryClassify*` | Classification acceptance and malformed/missing/duplicate/conflict diagnostics. |
| `robStoreCommit*` | Token acceptance plus missing, duplicate, blocked, classification-missing, and classification-fault diagnostics. |
| `serializedRequest` | Retained non-cacheable/MMIO fragment request. |
| `serialized*` | Busy/waiting, stale-response, malformed-batch, terminal-error, free, and logical-completion observability. |
| `scb*` | Cacheable admission, row-bank, response, egress, and free observability. |
| `stq*` | STQ row image, occupancy, status masks, recovery masks, and final free acknowledgements. |
| `drain*` | CommitQ ordering, retained fragments, memory class, issue, and logical completion observability. |
| `lsuTULinkSource*` | Exact T/U cleanup-source candidate owned by the STQ bank. |

## State Ownership

The wrapper owns no architectural registers directly. Its five child owners
have non-overlapping responsibilities:

1. `STQEntryBank`: physical store residency and final mutation;
2. `STQMemoryAttributeOwner`: class evidence for one exact residency;
3. `STQCommitDrain`: ordered CommitQ and retained fragment batch;
4. `STQCommittedStoreSerializer`: committed uncached/device transaction state;
5. `SCBRowBank`: cacheable line entries and response state.

## Logic Design

1. AGU completion makes a `Wait` row address-ready.
2. The translation/PMA adapter classifies that exact lease, semantic owner, and
   logical beat. Slot reuse cannot expose a stale class.
3. `STQRobCommitIngress` accepts the ROB token only when it finds one exact,
   ready row with a routable retained class. The row promotion and CommitQ
   enqueue occur atomically.
4. `STQCommitDrain` snapshots the class with the logical store and produces one
   or two ordered fragments per row. A pair must have the same class.
5. Cacheable fragments are admitted atomically by the SCB batch gate. SCB
   accepted `last` ownership supplies the cacheable free mask.
6. A non-cacheable/device batch is admitted only when the serializer is idle.
   Its early drain free and completion are suppressed. The serializer sends
   one fragment at a time and supplies a full-row free mask plus one logical
   completion at the final exact response.
7. Cacheable and serialized completions are asserted mutually exclusive before
   the wrapper selects the single visible completion source.

This preserves committed-store residency across downstream backpressure and
prevents a Device/MMIO request handshake from being mistaken for architectural
completion.

## Timing and Recovery

The STQ, CommitQ, serializer, and SCB are registered boundaries. A newly
committed row is visible to drain launch from the next registered row image.
The SCB batch gate uses pre-cycle capacity; a same-cycle free does not reopen
admission in that cycle.

Recovery fences new classification, ROB-commit ingress, drain launch, and new
serialized-batch admission. It prunes speculative `Wait` rows only. Final free
from an already accepted committed SCB or serialized transaction remains live
during recovery; suppressing it could lose a one-cycle terminal response and
leak the committed STQ residency.

Committed CommitQ, SCB, and accepted serializer state are not flushed by
ordinary recovery. Reset remains the architectural abort boundary.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPathSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQMemoryAttributeOwnerSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommittedStoreSerializerSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQRobCommitIngressSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitQueueSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQCommitDrainSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQEntryBankSpec`
- `bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabricSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSUSpec`
- `bash tools/chisel/build_chisel.sh`
- `bash tests/test_microarchitecture_contract.sh`

Focused UT covers class-qualified ROB commit, cacheable SCB routing,
non-cacheable/MMIO serialization, split and pair stores, request/response
backpressure, stale responses, recovery overlap, and exact final row release.
The grouped-ROB integration test starts at the memory-tail batch and supplies a
retained fake PMA adapter so it cannot bypass the classification contract.

## Remaining Integration Gaps

- real DTLB/PMA classification adapter and platform PMA ownership;
- real uncached/device request-response fabric;
- precise ROB/platform handling of serialized terminal errors;
- Device load, atomics, fences, and maintenance ordering;
- transaction-identity wrap/quiescence proof;
- canonical static top-level composition and trace integration.
