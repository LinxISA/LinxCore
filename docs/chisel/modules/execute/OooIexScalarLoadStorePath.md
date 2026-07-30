# OooIexScalarLoadStorePath

## Purpose

`OooIexScalarLoadStorePath` is the production scalar-load attachment installed
between the existing `OooIexCanonicalLoadOwnership` and the existing
`OooIexStoreStqFabric`. It owns one
`ScalarLSULoadPath(useExternalStqForwarding=true)`—and therefore the one live
LIQ/L1D/MDB/LRET graph—but deliberately owns neither another OOO metadata
sidecar nor another STQ.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexScalarLoadStorePath.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexScalarLoadStorePathSpec.scala`

## Closed data path

`OooIexExecutionStorePipeline` makes the following connections private and
atomic through this attachment:

- three retained AGU lanes allocate one canonical LIQ row and one exact OOO
  terminal-metadata row on the same fire;
- a selected LIQ launch publishes the exact `{loadId, attempt}` to the OOO
  speculation owner;
- all three retained STQ query/response lanes connect directly to the one live
  canonical STQ;
- a normal E4 result mutates only its exact resident LIQ attempt;
- LRET/W1/W2 completion and the OOO result share one terminal handshake;
- the full ROB lookup key is exported before `robRowValid/robRowNeedFlush` is
  consumed: PE/STID/TID, BID/GID/RID, full LSID, and load-attempt identity.

The installed production path therefore has one LIQ, one STQ, one
forwarding-result owner, and one terminal metadata owner. Structural
uncertainty is kept on the typed retained `hardBlock` output. The attachment
does not silently reinterpret it as a cache miss or discard it.

## Store/MDB acceptance

An address fill can create an MDB conflict record, so STQ acceptance cannot
precede MDB capacity. `OooIexStoreStqFabric` now exposes a retained
`lateStaCandidate` before the canonical STQ write and accepts it only when
`lateStaPermit` is true. The closed wrapper drives the candidate into the live
MDB, uses `probeReady` as the permit, and asserts `probeCommit` only on the same
accepted STQ fill. A blocked MDB therefore holds the address transaction in
the store pipeline; no accepted STQ address can lose its conflict side effect.

The candidate/permit boundary is now private inside
`OooIexExecutionStorePipeline`; neither the O3 wrapper nor another outer
composition can bypass MDB admission or create a second forwarding owner.

## Recovery

One central recovery transaction presents two owner-native views:

- `OooResidencyRecoveryPlan` for exact grouped-ROB/member owners;
- `FlushBus` for LIQ/MDB/return owners.

Prepare is side-effect free. The wrapper fences new pick/SCB-return/replay/
refill/miss/retire boundary mutation, validates matching PE/STID scope and,
for every resident LIQ row, proves that both views produce the same kill bit.
This is required because a grouped partial-pivot member cut cannot be derived
from BID alone. Because equivalent masks are not yet exposed for MissQ,
ResolveQ, MDB transient state, LRET, refill, and forward transport, prepare is
also rejected unless two consecutive prepared snapshots show all of those
non-LIQ owners empty. The prepared authorization is retained, and both native
views must remain bit-stable until fire. A mismatched or unsupported projection
is rejected without mutation. Only the execution/store wrapper's three-way
recovery join may fire the execution metadata/IQ owner, STQ owner, and this
LIQ/MDB/LRET owner together.

The final global recovery adapter must derive and prove the projection across
every LSU queue and the physical BID/BROB authority before removing this
fail-closed empty-state restriction.

## Reference evidence

The implementation follows the queue ownership and flush behavior in:

- `model/lsu/load_unit/ldq.cpp::LDQInfo::flush`, which removes load transport
  state through the typed flush contract;
- `model/lsu/store_unit/stq.cpp::STQ::lookupForLoad`, which searches exact
  older stores and waits on the nearest data-not-ready store;
- `model/lsu/store_unit/stq.cpp::STQ::flush`, which frees only flushable store
  rows;
- `model/lsu/lsu.cpp::LoadStoreUnit::setFlush`, which distributes one recovery
  event to LSU owners.

`Documents/a.txt` is used for the general separated load/store queue,
backpressure, and central recovery shape. Linx full LSID, native BID/BROB,
grouped ROB member, P/T/U, and precise-recovery contracts remain authoritative.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexScalarLoadStorePathSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipelineSpec
bash tools/chisel/run_chisel_tests.sh --only OooO3IexStorePipelineSpec
bash tools/chisel/build_chisel.sh
```

The dynamic integration test uses unequal `LIQ=4`, `ROB=8`, `STQ=4`, and
`LSID=40` geometry. It covers a real AGU allocation/launch through live STQ
forwarding and W2, the exact external ROB lookup, mutation-free recovery
prepare, retained prepared authorization, LIQ-only common-fire pruning,
post-LIQ recovery rejection, scope mismatch, and same-scope kill-mask mismatch.
The store test holds an address candidate while the side effect is not
permitted and proves the STQ remains unchanged until permit. The MDB test
proves a presented but uncommitted candidate may wait without protocol error.

## Remaining gaps

- Define the retained structural `hardBlock` retry/cancel/recovery policy.
- Replace the checked native-BID projection with the final shared BID/BROB age
  adapter and extend recovery equivalence to all LSU queues.
- Connect the physical SCB return owner, DTLB, PMP/PMA, MMIO/device ordering,
  L1D/coherence, lower-memory faults, and cross-line policy.
- Close default-width sustained-pressure tests, synthesis/timing, and natural
  CoreMark/Dhrystone promotion.
