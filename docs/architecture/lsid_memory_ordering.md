# LSID Memory Ordering

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`

## Architectural boundary

LinxISA requires TSO across the BCC and MTC memory channels. Strict scalar
issue in LSID order is the selected LinxCore closure policy, not an additional
ISA-visible ordering promise. A future load-speculation policy is legal only if
it preserves TSO, precise recovery, and the same committed behavior.

## Two related identities

LinxCore distinguishes a row watermark from a memory-operation identity:

1. Every scalar decoded row captures the current pre-increment LSID value.
   This all-row snapshot lets ROB commit and recovery publish the memory-order
   boundary associated with any redirecting or retiring instruction.
2. Only a decoded load, store, atomic, or other cataloged memory operation
   consumes that value and advances the LSID allocator.

The all-row snapshot stays in ROB/LSU sidecars. It is not part of the external
commit schema unless a separately versioned trace change promotes it.

After an R2 commit batch, the ROB publishes one cumulative commit frontier per
affected STID: the first uncommitted `(STID,BID,LSID)` position using the
all-row pre-increment snapshot, or the equivalent tail frontier when the ROB
partition is empty. ResolveQ removes same-STID rows strictly older than that
tuple. Multiple committed rows in one cycle coalesce to the most advanced
frontier for that STID, and separate STIDs publish independent frontiers; no
cleanup watermark is lost and no comparison crosses STIDs.

Loads and stores also use queue-local identities:

- `LID`: load-inflight slot plus wrap;
- `SID`: store-queue slot plus wrap;
- `LSID`: program-order memory watermark/serial identity.

Split `STA` and `STD` work shares one store instruction identity, SID, and
LSID. LID/SID locate resident queue rows; LSID orders architectural memory
operations. They are not interchangeable.

All scalar memory-identity allocators are partitioned by STID. A selected row
reads and advances only its STID's LSID, load-ID, and store-ID lane. Scoped
recovery restores or clears only the selected lane; reset/restart may clear all
lanes. No implementation may serialize independent STIDs through one global
counter merely because the reduced top currently executes one scalar lane.

## Block store ranges

BROB owns a related but separate block store-range frontier. Each live block
records an exact scalar/template store count once that count becomes certain.
Starting at the per-STID range cursor, BROB assigns the current `next_store_id`
as that block's `start_store_id`; if the count is certain, it advances the ID by
the count and continues through consecutive resident blocks. An uncertain
block receives a stable start ID but stops younger range allocation.

- Block range identity is `(STID, full BID, start_store_id, store_count)`.
- The cursor and next ID are independent per STID and parameterized in width.
- A hole, stale BID, flushed/mispredicted/exception block, or uncertain count
  prevents a younger block from bypassing the frontier.
- Recovery rewinds to the first killed block only when that killed suffix had
  already received ranges, restoring `next_store_id` from that block's saved
  start ID. The retained prefix is not renumbered.
- Scalar per-row SID and BROB block range assignment remain distinct owners.
  A future block/tile store consumer may derive row SIDs from its assigned
  range, but it may not overwrite scalar decode's accepted-row sequence.
- An authoritative CTU/tile count crosses a retained exact-identity
  publication boundary. It is admitted only for a live block, survives sink
  backpressure, and is canceled only by an accepted recovery that kills its
  full BID. Same-value duplicates are idempotent; conflicting duplicates are
  errors.
- Scalar block closure and explicit count publication may coincide. A
  different-block collision closes the scalar count first and retains the
  explicit event; a same-block explicit event supplies the authoritative
  value. Neither case changes BROB completion or non-flush policy.

This is an ISA-neutral resource-allocation mechanism. It does not define an
architectural fence, ARM barrier encoding, exclusive monitor, acquire/release
opcode, or exception-level behavior.

## Strict baseline issue

1. Decode stamps the current all-row snapshot in slot order.
2. A memory row reserves the corresponding memory-operation identity.
3. ROB retains the snapshot until deallocation or flush clears the row.
4. The scalar LSU issues a memory row only when its LSID equals
   `lsid_issue_ptr`.
5. On accepted LSU issue, the issue pointer advances. Completion state advances
   only at the owner-defined completion boundary.

The pointers must not advance merely because a request was presented. Device,
MMIO, fence, atomic, replay, and split-store owners may require a stronger
accepted/completed condition.

## Flush and recovery

- Redirect/flush drops younger speculative memory rows through the typed
  `FlushBus` predicate. Depending on the request, matching may include STID,
  PE/thread scope, block-only, group, or default STID+BID+LSID matching.
  Cross-block age is defined only within one STID and comes from that ring's
  BROB-generated kill set/internal context; BID
  magnitude is never an age comparison. LSID orders memory rows within the
  selected block/recovery domain.
- Allocation, issue, and completion state rebases to the first surviving
  memory-order position so squashed identifiers cannot deadlock the strict
  issue pointer.
- A scalar redirect uses the redirecting row's all-row LSID snapshot. It must
  not substitute ROB RID for memory order.
- A marker-only cleanup with no valid LSID stays on the conservative owner-
  defined clear path until recovery supplies a valid memory-order boundary.

## Required invariants

- Same-cycle rows preserve decode-slot program order.
- Independent STIDs preserve independent LSID/load-ID/store-ID state.
- A non-memory row never advances the LSID allocator.
- No squashed memory row can later issue, complete, forward, or wake a load.
- Queue-local LID/SID wrap cannot change LSID order.
- LSID wrap uses an internal wrap/generation sidecar, or reuse waits for that
  STID's memory queues and recovery records to quiesce before a reset. Plain
  unsigned LSID comparison across wrap is forbidden.
- BID wrap cannot change block age; every same-STID cross-block memory
  comparison uses BROB ring context rather than unsigned BID ordering, and no
  comparison invents an age relation across STIDs.
- Any speculative load policy must recover to the same architectural result as
  the strict baseline and must not duplicate Device/MMIO side effects.
- Assigned BROB store ranges are disjoint and consecutive within one STID;
  no range comparison or allocation crosses STIDs.
