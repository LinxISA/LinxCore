# LinxCoreIfu

## Purpose

`LinxCoreIfu` is the production composition baseline of the decoupled I-SIDE
and B-SIDE engines. It is the only IFU owner that connects F0 through D1,
allocates canonical redirect epochs, and routes PTW and L1I memory traffic.
The composition admits multiple sequential cacheline transactions while
preserving ordered I-F3 consumption and exact cross-line prefix ownership. It
has a dedicated generated-RTL hot-cache throughput proof and is instantiated by
the natural CoreMark/Dhrystone benchmark graph. The remaining width gap is
downstream: D1 accepts a dense four-lane group, while the current D2/D3 path
serializes those lanes before rename/dispatch.

## Pipeline Composition

```text
I-F0 -> I-F1 -> ITLB + L1I -> I-F2 -> I-F3 -> I-F4
  |                                               |
  +-> B-F0 -> B-F1 -> B-F2 -> B-F3 -> B-F4 <----+
                                                  |
I-F4 groups + B-F4 final -> prediction join -> IB -> D1
```

I-F0 request acceptance, prediction-join allocation, and ordered line-context
allocation are atomic. The
B-SIDE request is retained in I-F0's independent queue, so B-SIDE
backpressure does not form a ready loop through I-F1.

## I-F1 and I-F2 Routing

The single I-F1 input is selected in this order:

1. an L1I miss-table retry;
2. an I-F3 cross-line continuation;
3. a new I-F0 transaction.

Every selected request launches ITLB and L1I in the same cycle. I-F2 then
routes the exact joined result:

| Result | Destination |
|---|---|
| Hit | I-F3 resident input or exact cross-line response |
| ITLB miss | retained PTW-pending row plus canonical redirect proposal |
| L1I miss | atomic miss-table allocation plus external line-read request |
| Access fault | retained `fetchFault` output |
| Stale | consumed and reported, never sent to I-F3 |

I-F2 accepts a translation/cache join only when PE, transaction, STID, fetch
packet UID, fetch sequence, checkpoint, epoch, PC, and line VA all match. An
L1I refill is accepted only by the matching live miss identity and physical
line. An orphaned refill may populate L1I but cannot replay stale work.

Only the first ITLB miss for the unresolved transaction publishes a PTW
request. The canonical ITLB redirect removes the stale fetch work but retains
the PTW-pending row, so F0 cannot loop on the same VPN before refill. A matching
refill clears the row and allows the restarted PC to retry with the canonical
epoch. Backend restart or a new start may cancel logical waiting; a later
refill still updates physical ITLB state.

## Ordered Multi-Line and Cross-Line Correctness

`ISideLineContextQueue` keeps I-F0 allocation order separate from I-F2 return
order. Exact younger hits may complete early, but only the oldest completed
line reaches I-F3. I-F3 is the sole instruction-byte carry owner. A
continuation request traverses the normal I-F1/I-F2 and miss/refill path; it
does not bypass translation or cache ownership. If the successor context is
resident but incomplete, I-F3 waits for its original completion; if already
complete, its data satisfies the continuation without a duplicate lookup.

On acceptance of a crossing instruction, I-F3 publishes an exact successor
carry. The context queue changes only that successor's semantic start PC, or
retains the carry until the row allocates. Request identity used to match the
I-F2 completion is unchanged. This separates physical lookup ownership from
the first unconsumed byte and prevents the consumed prefix from being decoded
twice while multiple cachelines are in flight.

## Redirect and Epoch Ownership

`IfuRedirectArbiter` is the sole canonical epoch allocator. Proposal priority
is:

```text
backend > ITLB miss > prediction correction
```

The accepted redirect is broadcast in one cycle to F0, every I-SIDE transient
owner, B-SIDE, the prediction join, Instruction Buffer, and D1. A start event
seeds the selected STID epoch and applies `KillAllThreadState` to old frontend
state. Redirects never invalidate physical ITLB/L1I contents or learned
predictor tables.

The same broadcast is the sole speculative-history ordering point. A B-SIDE
correction proposal carries an exact prediction tag, corrected conditional
delta, and typed Call/Return RAS delta, and only marks its STID recovery pending
when accepted. The returned canonical prune restores the request-owned B-F0
GHR and complete RAS snapshots, then applies each delta once. ITLB miss carries
unkeyed GHR/RAS restore actions so B-SIDE can use the trigger row or oldest
killed snapshot; start carries explicit GHR/RAS reset.
`IfuBackendFeedbackBridge` constructs backend BRU recovery with the retained
mispredict row key plus actual conditional and RAS deltas. The accepted event
still enters through `backendRedirect`; the bridge never bypasses canonical
epoch allocation.
Non-branch OOO exception/nuke/debug/CTU recovery uses the typed
`OooRecovery` reason. It follows the same canonical epoch owner and broadcast
path; only the proposal source and prune metadata differ.

## External Interface

- `start`: activate or replace one STID fetch context;
- `backendRedirect`: architectural BRU/recovery restart proposal;
- `branchResolve`: resolved prediction training;
- `ptwRequest` / `ptwRefill`: instruction translation miss transport;
- `lineRead` / `lineRefill`: physical I-cache line miss transport;
- `fetchFault`: execute-permission/access fault;
- `invalidateItlb` / `invalidateL1I`: explicit physical-state invalidation;
- `d1ThreadId` / `d1`: selected STID and four-wide fixed-64-bit D1 group.

The feedback bridge connects its `resolve` and `backendRecovery` outputs to
`branchResolve` and `backendRedirect`. Dispatch/BRU event-producer wiring
belongs to the production backend composition, not to `LinxCoreIfu` itself.

`IfuLineMemoryBridge` is the production adapter for `lineRead/lineRefill`. It
retains the complete IFU request behind a monotonic external tag, checks tag
plus physical line on out-of-order responses, and reconstructs every refill
identity without address or UID inference. The standalone IFU keeps the typed
request/refill interface so cache/memory transport remains outside I-SIDE stage
ownership.

## Production Naming Boundary

The canonical production owner graph is deliberately small:

| Boundary | Production owner |
|---|---|
| I-SIDE | `ISideF0PcSelect` through `ISideF4Predecode`, `ISideITLB`, `ISideL1I`, `ISideFetchMissTable`, `ISideLineContextQueue` |
| B-SIDE | `BSidePredictionPipeline`, `BSideHistoryQueue` |
| Join and queue | `IfuPredictionJoin`, `InstructionBuffer`, `D1DecodeGroupGather` |
| External transport | `IfuLineMemoryBridge`, `IfuBackendFeedbackBridge` |
| Production composition | `LinxCoreComposition` |

The historical body-geometry helpers were renamed from migration-era
`ReducedBfu*` identifiers to the neutral `Bfu*` family. They remain
compatibility-only components used by `LinxCoreFrontendFetchRfAluTraceTop` and
are not instantiated by `LinxCoreIfu` or `LinxCoreComposition`.
Removing the `Reduced` prefix is therefore an API/file-name cleanup, not a
promotion claim.

| Previous identifier | Current identifier |
|---|---|
| `ReducedBfuBodyCutArm` | `BfuBodyCutArm` |
| `ReducedBfuBodyCutPredictor` | `BfuBodyCutPredictor` |
| `ReducedBfuGeometryPredictionLatch` | `BfuGeometryPredictionLatch` |
| `ReducedBfuLocalBodyWindow` | `BfuLocalBodyWindow` |
| `ReducedBfuPendingRuntimeBodyEndCandidate` | `BfuPendingRuntimeBodyEndCandidate` |
| `ReducedBfuPromotedRuntimeBodyEndOracle` | `BfuRuntimeBodyEndOracle` |
| `ReducedBfuResolvedBodyEndOwner` | `BfuResolvedBodyEndOwner` |
| `ReducedBfuResolvedBodyEndPending` | `BfuResolvedBodyEndPending` |
| `ReducedBfuResolvedBodyEndSource` | `BfuResolvedBodyEndSource` |
| `ReducedBfuStaticGeometryProducer` | `BfuStaticGeometryProducer` |

## Remaining Production Gaps

| Priority | Gap | Completion evidence required |
|---|---|---|
| P0 | Complete TAGE policy: medium/additional long-history tables, provider/alternate selection, usefulness, allocation, aging, and deterministic training-port arbitration | Focused assertions plus generated-RTL direction/provider conflict stimulus |
| P0 | Put path history and loop speculative state under request-owned checkpoint and canonical recovery | Late correction, backend recovery, ITLB fallback, and multi-STID rollback tests |
| P0 | Replace serialized D2/D3 consumption with four-row atomic resource reservation and dispatch | Four-lane all-or-none rename/ROB/issue admission under independent backpressure |
| P1 | Add independent B-F4 provider-rank and direction-override assertions/coverage | Coverage proving every legal provider pair and final-rank override |
| P1 | Terminate lower-memory denied/corrupt responses explicitly | Generated-RTL fault/termination tests with no leaked miss or bridge credit |
| P1 | Bind PTW and line-read interfaces to the selected SoC hierarchy and replace the direct-mapped L1I policy | Integration proof with production associativity, replacement, and refill ownership |
| P2 | Close synthesis concerns for predictor SRAMs, timing, area, and physical cache structures | Post-synthesis timing/area report and SRAM mapping review |

CoreMark/Dhrystone benchmark-top promotion and the canonical 32-cycle
four-wide hot-cache gate are closed. They remain workload and mechanism
evidence respectively; neither closes the P0/P1 gaps above.

## Verification

`LinxCoreIfuSpec` proves:

1. L1I miss allocation, refill, retry, final B-F4 join, and D1 delivery;
2. ITLB miss PTW transport and one canonical epoch redirect;
3. retained PTW ownership cancellation by a new start;
4. back-to-back cross-line continuation through the normal lookup path, with
   each next instruction starting after consumed prefix bytes;
5. context recycling across ten hot cachelines and twenty consecutive full
   four-entry D1 groups from a hot L1I;
6. backend redirect priority and removal of younger frontend state;
7. simultaneous start and backend redirect ordering.
8. an architectural 64-byte-line hot-cache run that sustains thirty-two
   consecutive full four-entry D1 groups, carries B-F4 final prediction on
   every lane, and keeps multiple joins and line contexts in flight.

`LinxCoreIfuThroughputProbe` emits the canonical composition with a
synthesizable line responder. Its Verilator gate checks the same thirty-two
cycle stream and observes join/context peaks of eight/six. The proof is scoped
to an eligible dense sequential hot-cache window; it does not substitute for
predictor-recovery stress, four-wide D2/D3 admission, memory-error termination,
or physical-design closure. Mixed-length traffic and natural CoreMark/Dhrystone
integration are covered by the promoted benchmark graph.

The R682 identity/rank packet additionally proves that I-F2 rejects a
checkpoint collision, I-F3 rejects a continuation collision despite matching
transaction/STID/epoch, B-F4 keeps long-TAGE above static fallback, and
non-conditional resolutions do not train BIM/TAGE.

Run:

```bash
SBT_OPTS='-Xms512m -Xmx4g -XX:+UseG1GC' \
  bash tools/chisel/run_chisel_tests.sh --only frontend

bash tools/chisel/run_chisel_ifu_throughput_gate.sh
bash tools/chisel/run_chisel_ifu_line_memory_bridge_probe.sh
```
