# ReducedLiveLoadLiqCapture

`ReducedLiveLoadLiqCapture` is the E1 admission boundary for an ordinary scalar
load owned by the reduced load-inflight queue (LIQ). It converts the current
E-stage lookup metadata into one `ReducedLoadReplayCandidate` and keeps execute
authoritative until the LIQ allocator accepts that candidate. It does not
fabricate a result or read sparse memory.

The shape follows the model's separation between IEX address formation,
`LDQInfo::insert`, `LDQInfo::pickL1`, and the later return/writeback path.
The reduced implementation uses the existing `LoadInflightQueue`,
`LoadForwardPipeline`, and LRET/W1/W2 owners for later stages; this module owns
only the E1-to-LIQ contract.

## Contract

Live capture owns only first-pass LIQ admission. A captured row enters LIQ in
`Wait` and must be launched through `LoadForwardPipeline` before it may become
an E4 `Repick` return candidate. The legacy source-return snapshot mutation
path is intentionally disabled while this owner is active: allowing that path
to mutate a newly allocated live row would let it return without a LIQ launch.
For live rows, E2's SCB, STQ, and return-port inputs are initially satisfied;
resident-store forwarding and its replay wakeup remain the owner when an E4
load actually waits on unavailable store data. LRET capacity is checked only
by the post-E4 return owner, so it cannot block the pipeline before the row's
first return candidate exists.

| Signal | Direction | Meaning |
| --- | --- | --- |
| `captureEnable` | input | Enables live ordinary-load admission. Disabled or flushed captures are inert. |
| `loadValid` plus metadata | input | Current E1 lookup and its destination, source trace, and ROB/LS identities. |
| `allocReady` | input | LIQ-adapter consumption permission for the current candidate. |
| `candidateValid`, `candidate` | output | Complete LIQ allocation request; metadata is meaningful only while valid. |
| `captureAccepted` | output | LIQ accepted the E1 load. Execute may retire the entry without producing its direct W1 result. |
| `blockedByAlloc` | output | A valid live load could not enter LIQ; E1 holds the same entry and retries. |

`captureAccepted` is never asserted when disabled, flushed, invalid, or
backpressured. The top-level boundary maps `RenamedDestination` to
`LoadReplayDestination` before this module, preserving the old physical tag
needed by LRET/W1/W2 writeback and wakeup ownership.

## Pipeline ownership

```text
E1 scalar load
  -> ReducedLiveLoadLiqCapture
  -> ReducedLoadReplayLiqAllocPath / LoadInflightQueue
  -> E2/E3/E4 LoadForwardPipeline
  -> LRET -> W1 -> W2 completion, writeback, wakeup
```

The capture path intentionally has no storage of its own. Backpressure holds
the source E1 register, preventing duplicate allocations and preserving the
metadata needed by the LIQ adapter. Flush takes precedence over both the
candidate and acceptance.

For the live-load wrapper, LIQ E2 launch is gated by base-data and source
readiness, not by pre-existing LRET-pipe readiness. LRET is a post-E4 return
consumer; allowing it to gate E2 would create a circular wait in which no
launch can create the result required to reserve the return pipe.

## Verification

`ReducedLiveLoadLiqCaptureSpec` locks the admission truth table and elaborates
the metadata surface. The adjacent execute gate verifies that a captured load
leaves E1 only after the acceptance handshake. The bounded CoreMark/QEMU gate
must show nonzero live-load LIQ allocation and launch counts before this owner
is considered exercised.

## Deliberate limits

This is not a cache, memory-response owner, or replay-slot replacement. Load
misses, store-forward waits, refills, and architectural CoreMark termination
remain owned by their corresponding LSU and harness contracts.
