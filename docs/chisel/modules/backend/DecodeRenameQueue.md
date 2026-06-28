# DecodeRenameQueue

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameQueue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DecodeRenameQueueSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPE.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/ModelCommon/SimQueue.h`

## Purpose

`DecodeRenameQueue` is the registered boundary between frontend decode and
scalar rename. It is the Chisel owner for the model `dec_ren_q` timing point:
decode may enqueue a raw `DecodedUop`, and rename sees it only after it is
resident at the queue head.

The queue is intentionally payload-agnostic. It does not allocate ROB rows,
assign LSIDs, split stores, or rename operands. `DecodeLoadStoreIdAssign`
annotates the selected row before enqueue in the current reduced composition;
the queue only stores the payload it is given.

## Interface

Inputs:

- `push`: raw decoded-uop payload. `push.valid` qualifies enqueue.
- `popReady`: rename-side readiness to consume the visible head row.
- `flushValid`: synchronous clear used for frontend/backend recovery cleanup.

Outputs:

- `pushReady`: queue can accept the current `push` row. A full queue may still
  report ready when the visible head also pops in the same cycle.
- `pushFire`: enqueue accepted in this cycle.
- `out`: visible head row. `out.valid` is suppressed while empty or flushing.
- `popFire`: rename-side head row consumed in this cycle.
- `head`, `tail`, `count`, `empty`, `full`: queue state observability used by
  reduced composition tests and future top-level trace/debug hooks.

## State And Timing

The module is a finite circular FIFO. It has registered payload entries,
registered `head`, registered `tail`, and registered `count`. Empty pushes do
not flow through combinationally to `out`; the pushed row becomes visible after
the registered queue state updates.

Simultaneous push and pop are allowed. When the queue is full, a same-cycle
pop opens capacity for the push and preserves `count`. `flushValid` has higher
priority than push and pop, clears all valid bits, resets pointers to zero, and
blocks traffic in that cycle.

## Model Alignment

The C++ model wires `DCTop.dec_ren_q` and `SPERename.dec_ren_q` in
`SPE::Build()`. `SPE::Xfer()` calls `dec_ren_q.Work()` between the decode and
rename owners, and `SPE::Flush()` calls `dec_ren_q.FlushIf(...)` for younger
matching rows. The Chisel queue captures the same stage separation, but uses a
coarse `flushValid` clear in the reduced backend path until full BID-scoped
queue pruning is owned.

The current `DecodeRenameROBPath` stores LSID-annotated decoded rows in this
queue and stamps reduced ROB identity when the head is presented to rename.
Full model-like `DCTop::Work()` ROB reservation before queue enqueue is
deferred until the allocator exposes a true enqueue-time reservation owner.

## Deferred Owners

- BID-scoped per-row queue pruning equivalent to `SimQueue::FlushIf`.
- Width-wide decode enqueue and rename dequeue.
- Enqueue-time ROB reservation.
- Width-wide LSID/SID assignment and full `load_id`/`sid` payload carry.
- Store split into separate store-address and store-data uops.
- Top-level frontend backpressure wiring that advances D1/F4 only on
  `pushFire` or an equivalent accepted-decode event.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
```

Affected gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
```

The current tests cover the registered boundary reference rule, full-queue
simultaneous pop/push behavior, flush priority, IO widths, and CIRCT
elaboration as a separate queue owner.
