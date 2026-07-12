# LinxCoreFrontendFetchRfAluTraceTop

## Status

- Package: `linxcore.top`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTopSpec.scala`
- Generated RTL gate: `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- CoreMark replay gate: `rtl/LinxCore/tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh`
- Architecture source of truth: `rtl/LinxCore/docs/architecture/microarchitecture.md`

This top is the live scalar Chisel integration harness. It connects fetch,
decode, rename, ROB/BROB, issue, RF, scalar execute, reduced store execution,
LIQ replay, MDB recovery, load-return queues, W1/W2 completion, and commit
trace publication. It is not a second architecture definition; this page
describes how the golden contracts are composed in the current RTL.

## Configuration

The top is parameterized by `CoreParams`, interface widths, and local queue
depths. Important independent capacities include:

| Parameter | Owner |
| --- | --- |
| `robEntries`, `commitWidth` | ROB and architectural commit. |
| `decRenQueueDepth`, `issueQueueDepth` | Scalar frontend/backend buffering. |
| `storeDispatchQueueDepth`, `storeExecBufferEntries` | Split STA/STD execution. |
| `ScalarLsuParams.stqEntries` | Speculative store rows. |
| `ScalarLsuParams.liqEntries` | Active scalar load rows. |
| `ScalarLsuParams.mdb*Entries` | Predictor, fanout, wait-plan, and recovery queues. |
| `ScalarLsuParams.loadReturnQueueEntries` | Entries in each STID/return-pipe LRET lane. |
| `ScalarLsuParams.loadReturnPipeCount` | Scalar IEX return pipes. The current reduced W1/W2 integration exposes one shared pipe. |
| `scalarStidCount` | Independent scalar STID lanes. |

Queue capacities do not define BID/GID/RID/LSID widths. The current reduced
W1/W2 integration requires `loadReturnPipeCount == 1`; the queue bank itself
supports multiple pipes and preserves the parameter for the later W1/W2
widening packet.

## Ownership Graph

```text
FrontendFetchPacketSource
  -> F4DecodeWindow / F4DenseSlotQueue
  -> DecodeRenameROBPath
       -> GPR/T/U rename, ROB/BROB, recovery fabric
       -> split STA/STD dispatch
  -> ReducedScalarIssueQueue
  -> ReducedScalarRegisterFile
  -> ReducedScalarAluExecute
       -> scalar completion / redirect
       -> live load request

Store side:
  STA/STD -> StoreDispatchSTQPath -> STQ -> commit queue -> SCB
                                     |       |
                                     +-> ScalarLSUMDBPath

Load side:
  load request -> ReducedLoadReplayLiqAllocPath
    -> forwarding / source-return / MDB mutation
    -> return extraction and publication
    -> ScalarLSULoadReturnQueueBank
    -> IEX E4 -> W1 -> W2
    -> ROB resolve + RF writeback + issue wakeup + LIQ clear
```

The reduced timing modules are implementation adapters. They do not redefine
Linx memory ordering, block recovery, or architectural state.

## Frontend and Backend

- Fetch requests and responses are retained through explicit ready/valid
  boundaries.
- Dense decode slots enter one rename/ROB allocation boundary. A row cannot
  become visible to issue unless its rename, ROB, and required block metadata
  allocations are all accepted.
- GPR rename uses SMAP/CMAP/MapQ state. T/U local links use the local queue and
  relation-map contract.
- Issue observes registered ready state. Wakeups produced in a cycle become
  eligible for selection on the next cycle.
- ROB completion from execute and W2 load return shares an explicit arbiter;
  execute priority cannot cause the W2 row to clear before its completion is
  accepted.

## Store Path

- STA and STD reserve one shared store identity at dispatch and may complete
  independently.
- Address-bearing STQ insertion is admitted only when the canonical MDB owner
  can consume the probe or a finite replay adapter has credit.
- Data-only STD completion bypasses address-side MDB backpressure.
- The actual accepted STQ fragment type qualifies MDB commit. Pre-permit
  intent is used only to compute readiness and cannot create a side effect.
- STQ rows become SCB-visible only after ROB commit and the strong per-STID
  non-flush prefix authorize the exact full block BID.
- SCB completion and response ownership remain separate from request issue.

## Load and MDB Path

- A scalar load allocation and its PC-keyed MDB lookup are accepted atomically.
- LIQ rows retain PE/STID/TID, BID/GID/RID/load-LSID, destination, store
  snapshot, line data, and replay state.
- Store forwarding is byte-selective. A missing byte from the youngest
  eligible older store blocks rather than falling through to stale data.
- `ScalarLSUMDBPath` owns lookup, SSIT state, conflict detection, multi-row
  wait plans, failed-wait decay/delete, store wakeup, and retained recovery.
- Source-return and MDB mutations share one native row-mutation arbiter with
  source-specific qualification.
- MDB recovery enters the central backend fabric only after report-STID
  watermark selection and exact resident full-BID lookup.

## Load-Return Pipeline

### Publication

Returned line data passes source readiness, consumer readiness, return-pipe
budget, pipe permit/select, byte extraction, and publish control. Publication
is atomic with enqueue into `ScalarLSULoadReturnQueueBank`.

The queue-bank admission split is deliberate:

1. STID-local `preEnqueueReady` participates in consumer readiness before a
   return pipe is selected.
2. Final `enqueueReady` validates the selected STID/pipe lane.
3. An assertion requires every live published return to be accepted.

This prevents a cycle from queue readiness through return-pipe selection back
to queue readiness.

### Retention and Recovery

- Each STID/pipe lane is an independent FIFO sized by
  `loadReturnQueueEntries`.
- Shared IEX drain uses round-robin arbitration across nonempty lanes.
- An empty queue does not bypass combinationally to IEX.
- Start, restart, and frontend hard reset clear all lanes.
- Accepted typed backend recovery selectively prunes matching PE/STID/TID and
  BID/GID/LSID entries while preserving older and independent entries.
- Queue-head validity, ROB row status, and E4 residency are checked before
  drain.

### E4, W1, and W2

- IEX drains only when the residency stage can retain the payload.
- E4 and W1 are registered storage boundaries.
- W2 forms resolve, writeback, and wakeup requests from one resident entry.
- Required sinks must all be ready before the side-effect vector fires.
- ROB completion, commit-row data, RF writeback, issue wakeup, and exact LIQ
  lifecycle clear correspond to the same W2 transaction.
- A held W2 entry remains stable under execute writeback or completion-port
  contention.

## Recovery

Recovery producers are independently retained. The central fabric arbitrates
BCC, IEX, PE, scalar redirect, and LSU sources, resolves full block identity,
and publishes one consumed cleanup intent.

The top applies that accepted intent consistently to:

- ROB and BROB pruning;
- rename checkpoint cleanup;
- issue and execution stages;
- speculative STQ and LIQ state;
- MDB report and probe retention;
- scoped LRET queue pruning;
- frontend restart selection.

No local LSU or return-pipe helper may independently choose a different flush.

## Commit and Trace

- Commit rows come from the resident ROB, not reconstructed execute payloads.
- Load W2 completion may supply returned destination data and source metadata
  to the same commit-row owner used by ordinary scalar completion.
- Marker filtering and block termination preserve Linx BSTART/BSTOP semantics.
- The generated trace is normalized and compared against QEMU. CoreMark replay
  is a no-regression gate; it does not by itself prove activation of every
  recovery or contention path.

## Required Invariants

1. Rename, ROB, BROB, and issue admission agree on every accepted row.
2. LIQ allocation and MDB lookup acceptance are equal.
3. Every accepted address-bearing store enters canonical MDB processing.
4. Every published load return enters exactly one scoped LRET queue.
5. A queue entry is removed only by accepted IEX drain, typed recovery prune,
   or hard reset/restart.
6. W2 clears only after all required physical side effects are accepted.
7. RID selects the ROB row; STID selects the scalar lane; BID/GID/LSID retain
   ordering and recovery meaning.
8. No ARM exception level, condition flag, exclusive monitor, barrier
   encoding, or acquire/release policy is imported into Linx behavior.

## Verification

The normal packet gate includes:

```bash
cd rtl/LinxCore/chisel
sbt --server --batch --no-colors --mem 4096 'testOnly *'

cd rtl/LinxCore
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh \
  --qemu-trace <normalized-coremark-trace> \
  --build-dir generated/<packet>-coremark \
  --max-commits 426 --replay-rows 14656
```

Focused R661 coverage includes `ScalarLSULoadReturnQueueSpec`,
`LoadReplayReturnLretPayloadSpec`, and
`LinxCoreFrontendFetchRfAluTraceTopSpec`.

## Remaining Work

- Move the live return queue and E4/W1/W2 boundary beneath the complete
  `ScalarLSU` hierarchy instead of the reduced integration top.
- Widen the live W1/W2 datapath beyond one return pipe while preserving
  per-pipe queue credit and atomic side effects.
- Integrate cache, miss queue, refill, cross-line merge, and memory-response
  ownership under the same typed recovery boundary.
- Connect natural BCC/IEX/PE trigger generation and workloads that activate
  sustained recovery and contention paths.
- Replace residual historical diagnostic names in RTL interfaces as their
  corresponding owners become canonical; git history remains the archive.
