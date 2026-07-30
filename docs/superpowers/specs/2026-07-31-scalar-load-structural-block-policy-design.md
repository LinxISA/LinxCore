# Scalar Load Structural-Block Policy Design

## Purpose

The installed OOO/IEX scalar-load path currently exports STQ forwarding
`hardBlock` as an external `Decoupled` response.  If that response is accepted,
the canonical LIQ row remains `Repick` with `forwardPending = true`; no normal
E4 result can subsequently clear it.  This design replaces that uncontrolled
production seam with one retained structural-block policy owner and an atomic
OOO-metadata/LIQ retry transaction.

## Scope

This packet owns only structural forwarding outcomes between the private STQ
forwarding fabric and the installed scalar LIQ.  It does not add cross-line
load execution, invent a cache-miss classification, change STQ ordering, or
create a second load-lifecycle owner.

## Classification

The policy captures each accepted hard-block response in a compact resident
record containing the exact canonical load identity, exact current attempt,
return-pipe identity, optional exact wait-store identity, and reason bits.  It
classifies the record in this priority order:

1. Missing or ambiguous full LSID authority, malformed query identity,
   cross-line store, cross-line load, or an `unknownOlder` response without an
   exact wait-store key is `Unsupported`.
2. A stale STQ snapshot is `RetrySnapshot`, even when the response also reports
   an unknown older store, because the captured store key may itself be stale.
3. An unknown older store with a complete exact key is `WaitStore`.

`WaitStore` and `RetrySnapshot` increment the attempt generation by exactly
one while preserving the producer identity.  `Unsupported` remains resident,
raises `protocolError`, and can be cleared only by hard flush or by an accepted
typed recovery that proves the owning load is killed.

## Ownership and handshake

`LoadStructuralBlockPolicy` is the sole consumer of the private forwarding
fabric's raw `hardBlock` output.  It retains compact state under downstream
backpressure.  The production external O3 boundary exposes diagnostics, not a
payload dequeue port.

For retryable records, the policy supplies the internal candidate to the
existing `OooIexCanonicalLoadOwnership` rebind path.  External rebind remains
supported but waits while an internal structural retry is resident.  The
canonical owner must atomically accept all three effects:

- cancel the old OOO terminal-metadata generation;
- bind the next generation;
- mutate the exact LIQ row from `Repick + forwardPending` to `Wait`.

The LIQ structural-retry mutation validates the full load ID, current attempt,
next consecutive attempt, return pipe, lifecycle state, and wait-store key.
On acceptance it clears `forwardPending` and all partial forwarding-result
state.  `WaitStore` installs the exact wait-store record; `RetrySnapshot`
installs no wait-store so the normal picker can retry with a fresh STQ
snapshot.  No producer drops its resident record until this common handshake
fires.

## Recovery

Recovery prepare is side-effect free.  A resident policy record permits prepare
only when both the OOO recovery plan and LSU projection identify the same
canonical LIQ row as killed.  A surviving resident structural record blocks
prepare because a wait-store key captured before pruning cannot be assumed
valid after recovery.  The record clears only on the common recovery fire.
Hard flush clears it unconditionally.

The raw forwarding pipeline must still be empty before a recovery snapshot is
accepted; this prevents a response that has not yet reached policy ownership
from crossing the recovery boundary.

## Diagnostics

The production external interface reports:

- structural block pending;
- structural block unsupported;
- structural disposition and reason bits;
- exact load ID and attempt for the resident record;
- aggregate protocol error.

These signals are observation-only.  They cannot consume or rewrite the owner.

## Verification

Unit tests must prove retained stability under backpressure, classification
priority, exact generation increment, fail-closed unsupported residency, hard
flush clearing, and recovery kill versus survivor behavior.  LIQ tests must
prove the exact `Repick + forwardPending` mutation and reject stale identity,
wrong pipe, malformed wait-store, and concurrent mutation conflicts.
Integration tests must drive a real forwarding hard block through the installed
OOO/IEX path and observe one atomic metadata cancel/rebind plus LIQ retry state.
Generated RTL and structure gates must prove that the raw O3 `hardBlock`
dequeue port is absent and only one canonical load lifecycle owner remains.

## Alternatives rejected

- Treat every hard block as ordinary `Wait`: this masks missing ordering
  authority and can create an unwakeable wait.
- Treat every hard block as a cache miss: structural STQ uncertainty is not a
  cache hierarchy outcome.
- Raise a precise architectural trap now: no approved Linx fault cause or ROB
  trap payload contract exists for these implementation failures.
- Add a second LIQ/metadata retry owner: it would violate the existing atomic
  canonical ownership boundary.
