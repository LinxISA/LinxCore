# OooIexCanonicalLoadOwnership

## Purpose

`OooIexCanonicalLoadOwnership` is the production ownership boundary between
the three retained OOO AGU load lanes and the canonical scalar LSU. It composes
the non-resident `OooIexLoadLiqAllocAdapter` with the metadata-only
`OooIexLoadTerminalMetadata` sidecar without adding a second address, miss,
replay, refill, or result queue.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexCanonicalLoadOwnership.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala`

`Documents/a.txt` motivates speculative-load generations and retained return
state. Linx canonical LIQ row leases, complete `LoadAttemptIdentity`, full LSID,
grouped-ROB member identity, and P/T/U destinations remain authoritative.

## Atomic allocation

The three AGU inputs are fairly serialized by
`OooIexLoadLiqAllocAdapter`. One accepted transaction simultaneously:

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

Canonical `ScalarLSULoadReturnEntry` completion is accepted only when its full
row lease, attempt, producer, destination, and data/fault outcome match the
sidecar. W2 backpressure retains that metadata until the same `result.fire`
releases the canonical completion and sidecar. Fault results require zero data;
ordinary data results carry no fault.

## Recovery

Recovery prepare and recovery apply are separate:

- prepare fences allocation, rebind, and terminal publication but does not
  consume any AGU producer;
- the metadata owner validates the retained plan and reports the exact kill
  mask;
- only one externally authorized common `recoveryFire` applies the kill to the
  terminal sidecar and destructively acknowledges killed AGU producers.

Surviving AGU requests remain held and can proceed after the recovery fence is
removed. Hard flush remains a separate destructive operation.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexCanonicalLoadOwnershipSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexLoadLiqAllocAdapterSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexLoadTerminalMetadataSpec
```

The IT uses unequal `LIQ=4`, `ROB=8`, `STQ=4`, and `LSID=40` geometry. It
covers three-lane allocation, LIQ/sidecar atomicity, exact slot-wrap identity,
terminal backpressure, ordinary data, precise fault, atomic rebind, stale
completion rejection, common recovery fencing/kill, and generated SystemVerilog
structure. The emitted graph must contain the allocation adapter and terminal
metadata owner and must not contain `OooIexLoadUnit`.

## Remaining cutover gaps

- connect canonical LIQ launch and attempt rebind events to exact speculative
  wakeup/cancel policy;
- instantiate this owner in `OooIexExecutionCluster` and join its recovery
  readiness with IQ/execution/STQ recovery;
- connect `ScalarLSULoadPath` allocation, launch, return, and all three
  canonical STQ result pipes;
- delete `OooIexLoadUnit` and its abstract request/response boundary in the
  same packet that replacement cluster IT becomes green;
- attempt-qualify every asynchronous cache/refill return, then add physical
  DTLB/PMP/PMA/L1D/coherence/device and cross-line handling;
- close synthesis timing and natural Dhrystone/CoreMark workloads.
