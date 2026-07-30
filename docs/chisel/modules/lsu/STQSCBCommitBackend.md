# STQSCBCommitBackend

## Purpose

`STQSCBCommitBackend` is the production committed-store backend for an STQ
owned by another module. It exists so `OooIexStoreStqFabric` remains the sole
physical STQ owner while the exact ROB commit ingress, CommitQ, typed
translation/PMA sidecar, cacheable SCB path, and serialized non-cacheable/MMIO
path are composed behind it.

Unlike `STQSCBCommitPath`, this backend does not allocate, insert, flush, or
retain STQ rows. Its only row mutations are requests returned to the canonical
owner:

- one exact WAIT-to-COMMIT index on an accepted ROB token;
- one terminal free mask from either SCB admission or the final serialized
  response.

## Ownership and atomicity

The backend consumes `STQRobCommitToken`, which carries the complete
generation-qualified semantic owner and logical store range but no physical
STQ index. `STQRobCommitIngress` rediscovers exactly one converged WAIT row and
requires a retained translation/PMA classification. The ROB token may fire
only when the CommitQ can accept the same row. Assertions require STQ promotion
and CommitQ insertion to occur on that edge together.

`NormalCacheable` fragments drain to `SCBRowBank`. The SCB-accepted fragment
carrying `last && ownsStqRow` is the only cacheable free source.
`NormalNonCacheable` and `DeviceMmio` batches are retained by
`STQCommittedStoreSerializer`; all rows remain resident until the final exact
transaction response. `Unknown` and `Fault` classifications fail closed.

## Recovery

Recovery request presence fences new classification, ROB-token acceptance,
CommitQ launch, and new serialized-batch admission. Already committed CommitQ,
SCB, and serializer state survives ordinary branch recovery. A terminal SCB or
serialized free is retained until the canonical STQ acknowledges the complete
mask, so a final response overlapping recovery prepare cannot leak a committed
row.

The external STQ owner independently prepares and applies WAIT-only recovery.
This separation is intentional: speculative row pruning belongs to the STQ;
non-flush committed transport belongs to this backend.

## Production composition

`OooIexExecutionStorePipeline` instantiates one `OooIexStoreStqFabric` and one
`STQSCBCommitBackend`. The backend reads that fabric's rows and drives its
mark/free ports privately. Production IO exposes exact ROB tokens and typed
memory-classification tokens; raw physical mark/free controls are no longer an
external architectural seam.

`STQSCBCommitPath` remains a lower-level compatibility/reference composition
that owns its own STQ. It must not be instantiated beside the production store
fabric.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
bash tools/chisel/run_chisel_tests.sh --only STQRobCommitIngress
bash tools/chisel/run_chisel_tests.sh --only STQMemoryAttributeOwner
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_tests.sh --only STQCommittedStoreSerializer
bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPath
```

The dynamic production-adjacent IT sends STA and STD through real formal
execution lanes, observes their convergence in one pre-reserved STQ row,
classifies the exact lease, commits it without a physical-index sideband, and
proves CommitQ/SCB admission plus terminal row release. The structural gate
elaborates the full fourteen-lane wrapper with the backend in hierarchy. A
second dynamic case returns the final serialized response while recovery
prepare fences STQ mutation and proves that the row frees only after the fence
drops.

## Remaining gaps

- connect `OooO3RenameCoordinator.storeCommit` directly at the next canonical
  O3-to-IEX top boundary;
- replace the test PMA producer with the physical DTLB/PMP/PMA result owner;
- connect the serialized request/response transport and precise terminal error
  publication;
- add store-to-load forwarding, overlap/violation replay, physical store-data
  banking, Device loads, atomics, fences, and coherence;
- consolidate the legacy self-owned-STQ wrapper after all consumers migrate;
- close serial-wrap quiescence, sustained bandwidth, synthesis timing, O9 top
  promotion, CoreMark, and Dhrystone.
