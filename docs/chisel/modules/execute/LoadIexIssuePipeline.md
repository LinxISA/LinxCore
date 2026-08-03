# LoadIexIssuePipeline

## Purpose

`LoadIexIssuePipeline` is the non-resident issue boundary between the selected
load-address lanes and the canonical LSU Load Inflight Queue. `OooIexIssue`
has already allocated the memory transaction and initial attempt when it
retained the load in IQ. This pipeline copies those identities exactly. LSU
becomes the sole LIQ residency owner on the common allocation fire and owns
every later replay, repick, and attempt rebind.

Source and tests:

- `chisel/src/main/scala/linxcore/iex/LoadIexIssuePipeline.scala`
- `chisel/src/test/scala/linxcore/iex/LoadIexIssuePipelineSpec.scala`

## Contract

The pipeline fairly arbitrates `laneCount` retained
`OooIexAguLoadRequest` inputs into the current one-wide LIQ allocation port.
W4 derives `laneCount=2` from the two load-address pipes. Other legal test
configurations may choose a different positive lane count only when the LSU
return-pipe count covers every selected lane.

One accepted issue maps:

- the complete producer `RobMemberKey` into `LoadAttemptIdentity`;
- one wrap-qualified IEX memory-transaction value and generation which remain
  unchanged across every LSU replay/rebind;
- one non-rewinding initial attempt generation;
- full LSID and exact youngest-older-store LSID from OOO memory order;
- ROB/BROB projections plus PE/STID scope;
- parent PC, effective address, access size/sign, and GPR destination;
- the displaced physical GPR mapping required by the load return contract;
- the selected address lane into one LSU return-pipe identity.

`LoadAttemptIdentity.requireBridgeFits` proves at elaboration that the complete
OOO identity, full LSID, PC, architectural tag, and physical tag fit the LSU
payload. A malformed issue is rejected rather than truncated or consumed.

## Backpressure and recovery

The pipeline owns no request queue or identity serial. An AGU lane retains its
request while the LIQ allocation port is blocked. The candidate allocation and
accepted sideband remain derived from the same selected request; `alloc.fire`
copies the already retained initial attempt without advancing IEX identity.

Recovery Prepare fences the affected STID without mutation. Apply drains an
exact killed visible request and prunes matching retained metadata through the
common recovery transaction; Abort preserves it. The serial owner is upstream
of this bridge and never rewinds, so an old return cannot alias a later load.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadIexIssuePipelineSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexCanonicalLoadOwnershipSpec
bash tools/chisel/run_chisel_tests.sh --only OooMemoryOrderAllocatorSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAguPipelineSpec
```

The directed suite covers W4 two-lane derivation, unequal LIQ/ROB/STQ
capacities, 40-bit full LSID, exact field mapping, retained backpressure,
fair multi-lane admission, selected return-pipe identity, displaced PTag,
missing-PC rejection, recovery Apply/Abort, peer-STID survival, and generation
continuity.

## Remaining boundary

Task 15 connects this mechanism through the public IEX and LSU boxes. That
cutover must preserve the single transaction/initial-attempt owner in
`OooIexIssue` and
the single replay/rebind owner in LSU; it may not reintroduce an adapter or a
second LIQ residency table.
