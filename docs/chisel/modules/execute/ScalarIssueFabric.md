# ScalarIssueFabric

## Source Mapping

- Fabric: `chisel/src/main/scala/linxcore/execute/ScalarIssueFabric.scala`
- Candidate policy: `chisel/src/main/scala/linxcore/execute/ScalarIssueCandidateArbiter.scala`
- Resident bank: `chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
- P1/I1/I2 child: `chisel/src/main/scala/linxcore/execute/ReducedScalarIssuePick.scala`
- Retained redirect frontier: `chisel/src/main/scala/linxcore/execute/ScalarIssueExternalControlFence.scala`
- Parameters: `chisel/src/main/scala/linxcore/common/CoreParams.scala`
- Unit tests: `chisel/src/test/scala/linxcore/execute/ScalarIssueFabricSpec.scala`
- Generated proof: `tools/chisel/run_chisel_scalar_issue_fabric_probe.sh`
- Golden contract: `docs/architecture/microarchitecture.md`

## Purpose

`ScalarIssueFabric` is the live scalar ALU residency and shared-issue owner. It
replaces the former one-queue composition in both RF/ALU tops with multiple
independent resident banks while preserving the existing aggregate top
interface for commit, recovery, and trace tooling.

The packet intentionally implements the scalar ALU slice, not the complete
eight-class physical IQ layout. The default two banks represent the primary
ALU queue and its shared spill path. BRU, AGU, STD, and CMD class routing and
two-write-port S1/S2 dispatch remain separate integration work.

## Parameters

| Parameter | Constraint | Meaning |
|---|---|---|
| `depth` | Power of two; divisible by `bankCount` | Total resident scalar rows across all banks. |
| `bankCount` | Power of two greater than one | Number of independently resident scalar banks. |
| `depth / bankCount` | At least two | Rows owned by each child bank. |
| `ScalarBackendParams.scalarIssueBanks` | Same as `bankCount` | Live-top bank configuration. |
| `ScalarBackendParams.gprReadPorts` | At least three | Physical scalar RF read ports; one granted uop receives three source lanes atomically. |

## Ownership

- Each `ReducedScalarIssueQueue` owns its rows, source readiness, and
  `inflight` bits.
- The fabric owns enqueue-bank choice and the shared I1 and I2 arbitration
  pointers.
- `ScalarGPRFile` remains the only scalar P data and non-speculative readiness
  owner.
- Release and committed P wakeup are broadcast to every bank because their
  exact resident owner is not known at the top boundary.
- Resident banks publish control and store frontier rows; the fabric owns only
  shared admission policy and never mutates those rows.

## Enqueue

One live rename row is routed to the non-full bank with the smallest current
occupancy. Equal occupancy selects the lower physical bank index. The row is
written only when the selected child reports `inReady`; aggregate `inReady`
does not claim capacity from a different bank after selection.

This policy matches the C++ model's least-occupied lane placement for the
current one-uop dispatch surface. It does not claim the golden two-write-port
same-cycle dispatch contract.

## Candidate Policy

Every bank forms at most one candidate for each represented STID by retaining
the oldest selectable queue slot for that STID. It then round-robins across
those per-STID candidates. The fabric applies the same policy across bank
candidates:

1. candidates with the same STID may be compared by wrap-aware RID age;
2. candidates with different STIDs are never age-compared;
3. round-robin state chooses among surviving STID candidates;
4. fairness advances only when the selected transaction advances.

An invalid candidate RID is a protocol error. It must not alias RID zero or
silently receive a grant.

## Control And Store Frontiers

I1 admission is additionally constrained by two per-STID ordering frontiers:

- a younger row cannot pass an older resident BRU or Linx `FRET.STK` control
  row;
- a younger store cannot pass an older resident store, while loads and
  non-memory work remain eligible under their normal dependency rules.

Both comparisons use wrap-aware RID age only after STID equality. Resident
control ownership lasts through exact `(STID, BID, RID)` release. A redirecting
`FRET.STK` transfers that exact identity to `ScalarIssueExternalControlFence`
at W2, and the full live top retains it until central recovery accepts cleanup.
This closes the release-to-recovery gap without globally flushing older issue
or split-store ownership.

The bank/arbitration mechanism is ISA-neutral. Classifying `FRET.STK` and
retaining its Linx block redirect identity are explicit Linx integration; no
ARM return state, flags, barriers, or exception-level behavior is imported.

## I1 Read Admission

Each bank may hold one P1-selected uop in I1. When multiple banks attempt I1 in
the same cycle, `ScalarIssueCandidateArbiter` grants one complete three-lane
read group. The winner captures all returned operand data and advances to I2.
Every loser emits `cancelFire`, clears only its bank-local `inflight` bit, and
keeps the row resident for a later pick.

If I2 cannot accept a bank's I1 row, that bank does not present a new read
attempt and therefore cannot be falsely cancelled by arbitration. Partial
source grants are forbidden.

The C++ model currently services all scalar read requests and has no scalar RF
denial. Finite Chisel ports are an ISA-neutral physical enhancement; generated
commit comparison proves cancellation and retry remain architecturally
invisible.

## I2 Issue Arbitration

Every bank has independent I2 residency. If execute backpressure allows more
than one bank to retain an I2 row, the second shared arbiter chooses one using
the same same-STID age and cross-STID fairness rules. Only the selected bank
observes `issueReady`. Unselected I2 rows remain stable.

I2 acceptance does not deallocate the resident bank row. Exact later release
by `(STID, BID, RID)` removes it.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarIssueCandidateArbiter
bash tools/chisel/run_chisel_tests.sh --only ScalarIssueFabric
bash tools/chisel/run_chisel_scalar_issue_fabric_probe.sh
```

The generated Verilator probe proves:

- same-STID oldest-RID selection;
- cross-STID RR service without cross-lane RID comparison;
- least-occupied bank spill;
- wakeup at N and simultaneous bank picks at N+1;
- one atomic I1 winner and one cancellation under contention;
- retained-row retry and later read grant;
- two independently resident I2 rows under execute backpressure;
- oldest-first I2 issue and preserved operand data.
- younger same-STID control and store blocking through exact release;
- unrelated-STID progress while another STID owns a control frontier.

CoreMark and live top comparisons are required as architectural no-regression
evidence. Nonzero fabric counters are workload-activation evidence only when
the full RF/ALU top, rather than the reduced commit-only top, generated them.
The R667 full-path CoreMark probe activates control and store frontiers and
passes the original banked-issue redirect divergence. It later stops at the
pre-existing reduced-store condition where a full STQ blocks ready STD updates
behind STA insertion; that STA/STD arbitration repair is a separate LSU packet.
