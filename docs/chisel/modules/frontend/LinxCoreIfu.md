# LinxCoreIfu

## Purpose

`LinxCoreIfu` is the production composition baseline of the decoupled I-SIDE
and B-SIDE engines. It is the only IFU owner that connects F0 through D1,
allocates canonical redirect epochs, and routes PTW and L1I memory traffic.
This baseline deliberately permits only one unresolved sequential fetch
transaction; it does not claim the future multi-transaction performance
surface is complete.

## Pipeline Composition

```text
I-F0 -> I-F1 -> ITLB + L1I -> I-F2 -> I-F3 -> I-F4
  |                                               |
  +-> B-F0 -> B-F1 -> B-F2 -> B-F3 -> B-F4 <----+
                                                  |
I-F4 groups + B-F4 final -> prediction join -> IB -> D1
```

I-F0 request acceptance and prediction-join allocation are atomic. The
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

## Cross-Line Correctness

I-F3 is the sole instruction-byte carry owner. A continuation request traverses
the normal I-F1/I-F2 and miss/refill path; it does not bypass translation or
cache ownership. I-F4's no-boundary completion includes the actual
post-assembly fallthrough PC. B-F4 returns that PC to I-F0 before another
sequential transaction is admitted, so bytes consumed by a crossing
instruction are not decoded again from the next line.

The current admission discipline permits one unresolved sequential fetch
transaction. A future multi-line optimization must add an equivalent
prefix/carry context queue before relaxing this gate.

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

## External Interface

- `start`: activate or replace one STID fetch context;
- `backendRedirect`: architectural BRU/recovery restart proposal;
- `branchResolve`: resolved prediction training;
- `ptwRequest` / `ptwRefill`: instruction translation miss transport;
- `lineRead` / `lineRefill`: physical I-cache line miss transport;
- `fetchFault`: execute-permission/access fault;
- `invalidateItlb` / `invalidateL1I`: explicit physical-state invalidation;
- `d1ThreadId` / `d1`: selected STID and four-wide fixed-64-bit D1 group.

## Verification

`LinxCoreIfuSpec` proves:

1. L1I miss allocation, refill, retry, final B-F4 join, and D1 delivery;
2. ITLB miss PTW transport and one canonical epoch redirect;
3. retained PTW ownership cancellation by a new start;
4. back-to-back cross-line continuation through the normal lookup path, with
   each next instruction starting after consumed prefix bytes;
5. backend redirect priority and removal of younger frontend state;
6. simultaneous start and backend redirect ordering.

The R682 identity/rank packet additionally proves that I-F2 rejects a
checkpoint collision, I-F3 rejects a continuation collision despite matching
transaction/STID/epoch, B-F4 keeps long-TAGE above static fallback, and
non-conditional resolutions do not train BIM/TAGE.

Run:

```bash
SBT_OPTS='-Xms512m -Xmx4g -XX:+UseG1GC' \
  bash tools/chisel/run_chisel_tests.sh --only frontend
```
