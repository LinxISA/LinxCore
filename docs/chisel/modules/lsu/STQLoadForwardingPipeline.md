# STQLoadForwardingPipeline

`STQLoadForwardingPipeline` is the production load-side query boundary of the
canonical store queue. It does not allocate STQ rows, store payload, mutate
LIQ/LHQ state, or own recovery policy.

## Ownership

- `STQEntryBank` remains the metadata, status, lease, and recovery owner.
- `STQDataBank` remains the physical mask/data owner.
- `STQLoadForwardingPipeline` owns replicated E1 tag snapshots and retained E3
  query responses.
- `LoadStoreForwarding` remains the per-byte nearest-older selector.
- `ScalarLSUMDBPath` remains the late-STA conflict, wait mutation, learning,
  and typed recovery owner. `OooIexStoreStqFabric.lateStaProbe` is only its
  accepted-STA event source.

There are three load pipes in the production wrapper. Each pipe has its own
elastic request/response state and therefore does not serialize through a
shared snapshot register.

## Pipeline contract

1. E1 accepts one query and snapshots every canonical STQ metadata tag for
   that pipe.
2. The snapshot identifies strictly older, same-STID scalar stores using
   wrap-safe ROB order and authoritative full LSID order inside one BID.
3. Any older row without an address becomes `unknownOlderMask`; the query is
   blocked even if all known rows miss the load address.
4. E3 reads the live `STQDataBank` image and revalidates the snapshotted lease
   generation, exact semantic owner, full serial identity, address, and size
   against the live metadata row.
5. Stable address hits are converted to positioned 64-byte masks/data and fed
   to `LoadStoreForwarding`, which selects the nearest older store separately
   for every load byte.
6. A selected store with unavailable data produces `waitMask`; row reuse,
   changed metadata, missing full-LSID authority, half-range ambiguity, and a
   cross-line store, malformed query identity, and a cross-line load all fail
   closed through `blocked`.

I0.15c-b3a makes every accepted query attempt-qualified. In addition to the
ordering fields, the request carries:

- `loadId`: the exact canonical LIQ slot-plus-wrap lease;
- `attempt`: the complete producer-qualified load-attempt generation;
- `returnPipeIndex`: the physical load-return pipe selected at allocation.

The STQ pipe preserves all three fields without interpreting them. A malformed
identity may occupy the elastic lookup pipe, but result qualification fails
closed if the LIQ lease is invalid, its current bridge generation is not
representable, the attempt is malformed, the attempt STID differs from the
ordering STID, or the request is presented to a different physical STQ pipe.
The upper canonical LIQ owner remains responsible for checking the slot against
its configured capacity when the result is applied.

Recovery prepare asserts `hold`, suppressing both request ready and response
valid so neither admission nor response fire can race the store recovery
projection. The common accepted recovery edge asserts `flush` and removes both
E1 snapshot and E3 response residency.

## Result interpretation

- `bypassComplete`: every requested byte comes from ready stores and no hard
  blocker exists.
- `forwardMask` / `mergedLineData`: ready store bytes and the store-overlaid
  cache-line image.
- `waitMask` / `waitStore`: the nearest selected address-known store whose data
  is unavailable.
- `unknownOlderMask` / `unknownWaitStore`: address-unknown older stores; these
  are not reported as uncovered cache bytes.
- `staleSnapshotMask`: E1/E3 identity or generation drift; the consumer must
  replay rather than use the response.

I0.15a adds `baseValidMask`, `loadDataReturned`, and `scbReturned` to the query
sidecar and connects the retained response to
`STQLoadForwardResultPipeline`. Partial L1D/SCB data and canonical STQ bytes can
therefore share the common E3/E4 result owner without expanding STQ rows into
a second CAM image. I0.15c-b3a binds every asynchronous response to the exact
canonical row, attempt generation, and physical return pipe. Direct LIQ result
application and replay mutation remain the next integration packet.

## Verification

Directed UT uses unequal `STQ=4`, `ROB=8`, `LSID=40` and covers:

- three simultaneous independent load-pipe snapshots;
- per-byte nearest-older selection across two overlapping stores;
- same-BID missing and half-range-ambiguous full-LSID rejection;
- multiple unknown older addresses with nearest wait-owner selection;
- E3 metadata reuse and physical-data generation rejection;
- malformed identity and cross-line load rejection;
- canonical-row, producer-attempt/STID, and physical-pipe mismatch rejection;
- overlapping cross-line-store rejection without false non-overlap blocking;
- retained response backpressure across recovery prepare;
- recovery-prepare response suppression and recovery removal of residency.

The `OooIexStoreStqFabric` integration test additionally drives formal
STA/STD execution into the canonical metadata/data owners, observes the single
accepted late-STA probe with its complete BID/GID/RID/LSID/address payload,
proves probe suppression on rejected and recovery-fenced mutation, completes a
load bypass through the new query port, and checks cross-line overlap at the
production wrapper boundary.

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQLoadForwardingPipeline
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
bash tools/chisel/run_chisel_tests.sh --only OooO3IexStorePipeline
```

## Model and reference evidence

- `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp::STQ::lookupForLoad`:
  address overlap, old-to-young merge, and nearest not-ready store wait.
- `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp::LDQInfo::handleDetect`:
  accepted store-address conflict scan, wait-store mutation, oldest resolved
  load selection, MDB record, and recovery publication.
- `/Users/zhoubot/Documents/a.txt` load/store section: replicated per-load-pipe
  local STQ tags, E1 CAM, E3 mask/data read, unknown older physical-address
  blocking, and youngest-store multi-hit behavior.
