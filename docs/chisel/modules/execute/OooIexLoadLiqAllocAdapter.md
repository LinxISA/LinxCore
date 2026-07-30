# OooIexLoadLiqAllocAdapter

## Purpose

`OooIexLoadLiqAllocAdapter` is the typed allocation boundary between the three
production scalar-load AGUs and the one canonical LSU load-inflight queue. It
does not retain a load request: AGU lanes hold `valid` under backpressure, and
the LIQ becomes the sole lifecycle owner on the common allocation fire.

Source and tests:

- `chisel/src/main/scala/linxcore/ooo/OooIexLoadLiqAllocAdapter.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexLoadLiqAllocAdapterSpec.scala`

## Contract

The adapter fairly arbitrates three `OooIexAguLoadRequest` inputs into the
current single LIQ allocation port. An accepted transaction maps:

- the complete producer `RobMemberKey` into `LoadAttemptIdentity`;
- an allocation-serial attempt generation which never rewinds on recovery;
- full LSID and exact youngest-older-store LSID from the OOO memory-order tail;
- native ROB/BROB projections plus PE/STID scope;
- parent PC, effective address, access size/sign, and GPR destination;
- the displaced physical GPR mapping required by the canonical return ABI;
- the physical AGU lane into one of three LSU return-pipe identities.

The bridge invokes `LoadAttemptIdentity.requireBridgeFits` at elaboration and
also proves the OOO RID, BID projection, LSID, PC, scope, architectural tag,
and physical tag widths fit the selected LSU configuration. It rejects rather
than truncates.

## Admission and recovery

Admission requires exact OOO member scope, scalar-load recipe/owner/class,
one-request memory-order evolution, a canonical current/previous GPR mapping,
and a valid parent PC. Flush or exact grouped-ROB recovery suppresses
allocation and asserts `ready` only as a destructive cancellation handshake
for the affected AGU request. This drains visible pre-recovery requests without
allocating them, so they cannot reappear after the recovery pulse. Recovery
does not reset or consume the allocation generation.

This packet deliberately sets `specWakeup=false`. I0.15c-b may enable it only
when exact speculative wakeup and cancellation are composed atomically with
canonical LIQ launch/return ownership.

`OooMemoryIdState.youngestStoreLsid` is an architectural implementation
boundary for forwarding, not an optimization. `storeId - 1` identifies the
youngest store in the type-local stream, while only this retained field gives
its position in the unified load/store LSID stream.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexLoadLiqAllocAdapter
bash tools/chisel/run_chisel_tests.sh --only OooMemoryOrderAllocator
bash tools/chisel/run_chisel_tests.sh --only OooIexPickP1Bridge
bash tools/chisel/run_chisel_tests.sh --only OooIexAguPipeline
```

The directed adapter suite uses unequal `LIQ=4`, `ROB=8`, `STQ=4`, and
`LSID=40` capacities. It covers exact field mapping, all three lanes, fair
serial admission, downstream backpressure, lane-to-return-pipe identity,
exact narrow ROBID projections beside full identities, displaced PTag mapping,
missing-PC rejection, destructive hard/precise recovery drain, and generation
continuity.

## Remaining gaps

- connect the cluster's propagated `alloc` and returned `loadId` to live
  `ScalarLSULoadPath` residency;
- compose the three STQ snapshot/result pipes with LIQ E1/E3/E4;
- make one recovery prepare/fire fence OOO, LIQ, MissQ, ResolveQ, LRET, and
  W1/W2. The immutable lease-checked terminal sidecar, launch/rebind policy,
  and cluster cutover are implemented.
