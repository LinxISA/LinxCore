# I-SIDE I-F0–I-F4

## Status

The production I-SIDE is composed by `LinxCoreIfu`. Only the owners listed
below define I-SIDE stage behavior.

The current owners are:

| Stage | Chisel owner | Implemented responsibility |
|---|---|---|
| I-F0 | `ISideF0PcSelect` | Per-STID PC/epoch/fetch-sequence state, backend-restart priority, prediction-correction restart, sequential line allocation, decoupled B-SIDE request queue |
| I-F1 | `ISideF1Lookup` | Atomic same-cycle launch to `ISideITLB` and `ISideL1I` |
| I-F2 | `ISideF2Resolve` | Exact-identity join and `Hit/ItlbMiss/AccessFault/L1IMiss/Stale` classification |
| I-F3 | `ISideF3LineAssembler` | 2/4/6/8-byte extraction and exact-identity cross-cacheline assembly |
| I-F4 | `ISideF4Predecode` | Generated-rule boundary-only BSTART/BSTOP recognition, 64-bit normalization, dense group truncation after BSTOP |

`ISideFetchMissTable` retains L1I miss transactions, updates physical L1I state
on an exact refill, retries a live request, and drains an orphaned refill
without reviving a stale frontend request.

`LinxCoreIfu` connects these stages to the canonical redirect arbiter,
B-SIDE, final-prediction join, Instruction Buffer, and D1.

## I-F0 PC and Identity

I-F0 is the only owner that changes the fetch PC. Control priority is:

```text
backend restart > prediction correction > start > sequential allocation
```

Each request carries transaction ID, STID, fetch sequence, checkpoint, and
epoch. Accepted requests are copied into a small B-SIDE request queue, so
B-F0 backpressure does not directly pull down I-F1. A queued request whose
epoch no longer matches its STID is discarded and counted after restart.

An accepted request advances provisionally to the next aligned cache line.
The B-F4 final response installs the exact next PC before the next sequential
transaction is admitted. This matters when the final instruction crosses the
line: the continuation bytes consumed from the second line are skipped rather
than decoded again. No predictor writes the PC directly; every correction
passes through the canonical redirect arbiter.

## I-F1 Parallel Lookup

`ISideF1Lookup` asserts both lookup valids only when both target owners are
ready. Consequently either both ITLB and L1I handshakes happen in one cycle or
neither happens. A hardware assertion enforces this invariant.

`ISideITLB` stores VPN-to-PPN mappings and execute permission.
`ISideL1I` reads a virtually indexed candidate containing its physical line
tag and data. Both produce registered, backpressured responses tagged with the
original request.

An inner flush applies its explicit prune scope to matching transient
transactions. Prediction correction preserves its producer and removes only
younger work; ITLB miss removes the trigger and younger work; architectural
restart removes all transient state for the STID. It does not invalidate TLB
or cache contents.

## I-F2 Join and Miss Handling

I-F2 retains ITLB and L1I responses independently, then requires exact
transaction ID, STID, fetch sequence, and epoch equality before classifying
them. The translated PPN plus page offset supplies the physical line tag used
to validate the L1I candidate.

An ITLB miss emits a retained typed IFU redirect proposal with PE, STID,
transaction, fetch sequence, old epoch, checkpoint, original restart PC, and
`KillTriggerAndYounger`. `IfuRedirectArbiter`, not I-F2, assigns the canonical
new epoch. It does not emit backend recovery. Access faults and L1I misses
remain distinct results.

The miss table distinguishes speculative request lifetime from physical refill
lifetime:

- a live exact refill updates L1I and generates a retry;
- an inner flush marks an outstanding miss orphaned;
- an exact orphan refill still updates L1I but cannot retry stale work;
- a mismatched transaction, epoch, STID, or physical line is rejected and
  reported.

## I-F3 Assembly

I-F3 is the only cross-line instruction owner. It parses consecutive groups of
up to four candidates using the Linx 2/4/6/8-byte low-bit length rule and keeps
a byte cursor until every instruction starting in the resident cacheline has
been accepted. If the last such instruction crosses the line boundary, I-F3
retains the first line and requests the next aligned line only to complete that
instruction. It does not emit instructions that start in the second line.
Only a second-line response with matching PE, transaction ID, STID, fetch
packet UID, fetch sequence, checkpoint, epoch, and expected next-line VA is
accepted.

The second-line request goes through the same I-F1 parallel ITLB/L1I launch,
I-F2 classification, and miss/refill table as a normal line. There is no
translation/cache bypass for cross-line assembly.

The completed candidate is zero-extended to 64 bits and carries a
`crossesLine` diagnostic. Downstream I-F4 and D1 never reconstruct raw bytes
across cache lines. Boundary recognition remains exclusively in I-F4:
`acceptedStop` terminates the resident line after the group containing BSTOP is
accepted. The terminal group supports consume-and-replace with the next line.

## I-F4 Boundary-Only Predecode

I-F4 evaluates only the generated opcode rules marked `isBlockBoundary` or
`isBlockStop`. It does not publish opcode, operands, immediates, dispatch class,
or execution semantics.

For each valid candidate it writes:

- fixed 64-bit instruction;
- original length;
- PC and complete fetch identity;
- `isBlockStart`/`isBlockStop`;
- the instruction-owned prediction record.

A BSTOP row remains valid and terminates the dense enqueue mask after that row.
I-F4 retains BSTART kind/target context across cachelines until BSTOP. A
terminal group atomically publishes both its `InstructionBufferEnqueueGroup`
and an exact-identity Decoupled boundary completion; a no-boundary terminal
still emits an explicit event with `bits.valid = false` and the exact
post-assembly fallthrough PC.

`IfuPredictionJoin` retains all groups for an allocated fetch transaction and
accepts B-SIDE updates in either arrival order. It releases groups only after
the B-F4 final response and any canonical correction have arrived, stamping the
same final prediction and canonical epoch into every valid lane.

## Production Composition

`LinxCoreIfu` owns the complete route:

```text
F0 -> F1 -> ITLB + L1I -> F2 -> F3 -> F4
                                      |      \
                                      |       -> B-F4 boundary completion
                                      -> final prediction join -> IB -> D1
```

The F1 input priority is miss retry, cross-line continuation, then a new F0
transaction. A new F0 request and prediction-join row allocate atomically.
Likewise, an L1I miss-table row and its external line-read request allocate
atomically. The first ITLB miss creates a retained PTW-pending row and a
canonical redirect proposal; F0 cannot repeat the same miss before refill.
Access faults leave through a retained fault port.

The accepted redirect broadcasts to all transient owners in one cycle.
Prediction correction preserves its producer and prunes younger work; ITLB
miss removes the trigger and younger work; backend restart removes all
thread-local transient state. Physical cache/TLB contents and learned predictor
tables remain resident.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only ISide
```

The focused leaf and composition suites cover:

- I-F0 sequential allocation with independent B-SIDE backpressure;
- backend restart priority;
- stale queued prediction-request removal after epoch change;
- exact L1I miss refill/retry and orphan refill without stale retry;
- same-cycle ITLB/L1I launch;
- translated physical-tag hit;
- exact I-F2 identity rejection when PE/packet/checkpoint/address metadata do
  not match;
- L1I miss, ITLB miss, and execute-fault classification;
- transient flush while retaining physical ITLB/L1I contents;
- four-candidate variable-length extraction;
- cross-line assembly and full-identity mismatched-response rejection;
- BSTART/BSTOP recognition and post-BSTOP truncation.
- L1I miss allocation, refill, retry, final B-F4 join, and four-wide D1 output;
- ITLB miss PTW request plus canonical epoch redirect;
- cross-line continuation through the normal lookup/miss path and exact
  post-prefix next PC;
- backend redirect priority and thread-local state clearing.

The R677 Instruction Buffer and D1 suites are rerun after the fetch identity
gains `fetchSeq`.

## Remaining I-SIDE Work

- Connect the exposed page-walk and line-read ports to the selected SoC memory
  hierarchy.
- Replace the direct-mapped L1I leaf with the selected production
  associativity/replacement policy without changing I-F1/I-F2 contracts.
- Replace the correctness-first one-unresolved-transaction admission gate with
  a prefix/carry context queue before enabling multiple sequential cachelines
  in flight.
- Add generated-RTL and sustained one-group-per-cycle performance gates.

`skill-evolve: no-update` — transaction identity, parallel ITLB/L1I, orphan
refill, cross-line ownership, and boundary-only I-F4 are already normative in
the IFU design and `linx-core` skill.
