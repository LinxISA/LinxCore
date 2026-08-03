# OooIexCanonicalLoadOwnership

## Purpose

`OooIexCanonicalLoadOwnership` is the canonical ownership boundary between
the parameterized OOO AGU load lanes and the canonical scalar LSU. W4 uses two
lanes. It composes the non-resident `LoadIexIssuePipeline` with the metadata-only
`OooIexLoadTerminalMetadata` sidecar without adding a second address, miss,
replay, refill, or result queue.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexCanonicalLoadOwnership.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala`
- `chisel/src/main/scala/linxcore/iex/OooIexLoadTerminalMetadata.scala`
- `chisel/src/test/scala/linxcore/iex/OooIexLoadTerminalMetadataSpec.scala`

Linx canonical LIQ row leases, complete `LoadAttemptIdentity`, full LSID,
grouped-ROB member identity, and P/T/U destinations define the contract.

## Atomic allocation

The configured AGU inputs are fairly serialized by
`LoadIexIssuePipeline`. One accepted transaction simultaneously:

- allocates the canonical LIQ row exposed on `liqAlloc`;
- captures the exact LIQ slot-plus-wrap lease from `liqAllocLoadId`;
- installs `{loadId, attempt, OooIexLoadGeneration, AGU request}` in the OOO
  terminal metadata sidecar.

Neither half can fire alone. Backpressure from the LIQ or a still-live terminal
sidecar propagates to the originating AGU. The ownership wrapper retains no
load request payload; the AGU remains the producer until the common fire and
the LIQ becomes the lifecycle owner afterward.

## Replay and terminal ownership

An OOO replay-policy rebind is also an all-or-none transaction. The wrapper
projects the canonical row lease back to the LIQ `ROBID` shape and requires the
LIQ and OOO sidecar to accept the same current-to-next attempt transition.
Skipped generations, stale leases, producer mismatch, and terminal races fail
closed in the two canonical owners.

The scalar LSU reports an accepted physical attempt through
`attemptLaunch={loadId,attempt}`. Only an exact resident sidecar match emits a
`SpeculativeLoad` wakeup, on the original AGU lane, with the complete P/T/U
destination and `OooIexLoadGeneration`. Allocation itself never wakes a
consumer. An accepted rebind cancels the old generation on that lane; the next
exact launch wakes the new generation.

Canonical `ScalarLSULoadReturnEntry` completion is accepted only when its full
row lease, attempt, producer, destination, and data/fault outcome match the
sidecar. W2 backpressure retains that metadata until the same `result.fire`
releases the canonical completion and sidecar. Fault results require zero data;
ordinary data results carry no fault.
An ordinary retained result also publishes lane-qualified W1 bypass. A fault
publishes no bypass and emits its exact cancel only on terminal fire. If a
fault and an unrelated rebind target the same physical load lane in one cycle,
fault cancel wins and the rebind is held for the next cycle; neither cancel is
merged or dropped.

## Recovery

Recovery Prepare, Apply, and Abort are separate phases of the common typed
transaction:

- Prepare fences only the affected STID and does not consume an AGU producer;
- the metadata owner validates and retains the exact plan before reporting
  `prepared`;
- matching Apply prunes the exact terminal sidecars and drains only visible
  killed requests;
- matching Abort preserves both the sidecars and visible requests.

Surviving AGU requests remain held and can proceed after the recovery fence is
removed. There is no second hard-flush protocol at this boundary.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexCanonicalLoadOwnershipSpec
bash tools/chisel/run_chisel_tests.sh --only LoadIexIssuePipelineSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexLoadTerminalMetadataSpec
```

The IT uses unequal `LIQ=4`, `ROB=8`, `STQ=4`, and `LSID=40` geometry. It
covers parameterized allocation including the W4 two-lane profile,
LIQ/sidecar atomicity, exact slot-wrap identity,
terminal backpressure, ordinary data, precise fault, atomic rebind, stale
completion rejection, exact launch wakeup, replay/fault cancellation, same-lane
cancel serialization, W1 bypass, common recovery fencing/kill, and generated
SystemVerilog structure. The emitted graph contains the non-resident issue
pipeline and terminal metadata owner and contains no migration tracker.

## Remaining integration gaps

Task 13 removed the combined execution/store shell. Task 15 must connect the
`OooIexIssue` identity owner, this atomic wrapper, its non-resident
`LoadIexIssuePipeline`, the existing `ScalarLSU` children, and the
canonical STQ directly through the public IEX/LSU interfaces without
introducing another load-lifecycle owner or another combined private shell. Small
default OOO profiles cap default LIQ population to the available ROB identity
domain rather than widening or truncating native BID projection. Remaining
work is to:

- extend the local LIQ recovery kill-equivalence proof to
  MissQ/ResolveQ/LRET and the final global recovery authority;
- attempt-qualify every asynchronous cache/refill return, then add physical
  DTLB/PMP/PMA/L1D/coherence/device and cross-line handling;
- close synthesis timing and natural Dhrystone/CoreMark workloads.
