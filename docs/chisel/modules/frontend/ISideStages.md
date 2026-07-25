# I-SIDE I-F0–I-F4

## Status

R678 introduces independent production-shaped Chisel owners for every I-SIDE
stage. Only the owners listed below define production I-SIDE stage behavior.

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

These modules and tests establish the stage behavior but are not yet composed
into the production top. Therefore R678 is not CoreMark/Dhrystone or end-to-end
IFU closure.

## I-F0 PC and Identity

I-F0 is the only owner that changes the fetch PC. Control priority is:

```text
backend restart > prediction correction > start > sequential allocation
```

Each request carries transaction ID, STID, fetch sequence, checkpoint, and
epoch. Accepted requests are copied into a small B-SIDE request queue, so
B-F0 backpressure does not directly pull down I-F1. A queued request whose
epoch no longer matches its STID is discarded and counted after restart.

Sequential allocation advances to the next aligned cache line. Later B-SIDE
integration will feed accepted prediction/correction records into the same PC
owner; no predictor may write the PC directly.

## I-F1 Parallel Lookup

`ISideF1Lookup` asserts both lookup valids only when both target owners are
ready. Consequently either both ITLB and L1I handshakes happen in one cycle or
neither happens. A hardware assertion enforces this invariant.

`ISideITLB` stores VPN-to-PPN mappings and execute permission.
`ISideL1I` reads a virtually indexed candidate containing its physical line
tag and data. Both produce registered, backpressured responses tagged with the
original request.

An inner flush may cancel a matching transient response. It does not invalidate
TLB or cache contents.

## I-F2 Join and Miss Handling

I-F2 retains ITLB and L1I responses independently, then requires exact
transaction ID, STID, fetch sequence, and epoch equality before classifying
them. The translated PPN plus page offset supplies the physical line tag used
to validate the L1I candidate.

An ITLB miss emits a retained typed IFU inner flush with PE, STID, transaction,
fetch sequence, old epoch, checkpoint, original restart PC, and `epoch + 1`.
It does not emit backend recovery. Access faults and L1I misses remain distinct
results.

The miss table distinguishes speculative request lifetime from physical refill
lifetime:

- a live exact refill updates L1I and generates a retry;
- an inner flush marks an outstanding miss orphaned;
- an exact orphan refill still updates L1I but cannot retry stale work;
- a mismatched transaction is rejected and reported.

## I-F3 Assembly

I-F3 is the only cross-line instruction owner. It parses up to four candidates
using the Linx 2/4/6/8-byte low-bit length rule. If an instruction crosses the
line boundary, I-F3 retains the first line and requests the next aligned line.
Only a second-line response with matching transaction ID, STID, and epoch is
accepted.

The completed candidate is zero-extended to 64 bits and carries a
`crossesLine` diagnostic. Downstream I-F4 and D1 never reconstruct raw bytes
across cache lines.

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
The result is an `InstructionBufferEnqueueGroup`.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only ISide
```

R678 passes 11 real Chisel simulation tests:

- I-F0 sequential allocation with independent B-SIDE backpressure;
- backend restart priority;
- stale queued prediction-request removal after epoch change;
- exact L1I miss refill/retry and orphan refill without stale retry;
- same-cycle ITLB/L1I launch;
- translated physical-tag hit;
- L1I miss, ITLB miss, and execute-fault classification;
- transient flush while retaining physical ITLB/L1I contents;
- four-candidate variable-length extraction;
- cross-line assembly and mismatched-response rejection;
- BSTART/BSTOP recognition and post-BSTOP truncation.

The R677 Instruction Buffer and D1 suites are rerun after the fetch identity
gains `fetchSeq`.

## Remaining I-SIDE Integration Work

- Compose all five owners, miss retry, second-line request, I-F4, Instruction
  Buffer, and D1 into one production top.
- Add a real page-walk request/response transport for ITLB misses.
- Replace the direct-mapped L1I leaf with the selected production
  associativity/replacement policy without changing I-F1/I-F2 contracts.
- Join the final B-F4 prediction record before an I-F4 group enters the
  Instruction Buffer.
- Add generated-RTL and sustained one-group-per-cycle performance gates.

`skill-evolve: no-update` — transaction identity, parallel ITLB/L1I, orphan
refill, cross-line ownership, and boundary-only I-F4 are already normative in
the IFU design and `linx-core` skill.
