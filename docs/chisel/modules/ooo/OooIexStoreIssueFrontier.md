# OooIexStoreIssueFrontier

## Purpose

`OooIexStoreIssueFrontier` enforces same-STID logical-store completion order
across the physical AGU and STD issue-queue classes. It adopts the useful
sliding-window behavior from `Documents/a.txt` without treating a full store
ID as a physical STQ index or importing ARM barrier semantics.

Source and tests:

- `chisel/src/main/scala/linxcore/ooo/OooIexStoreIssueFrontier.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexStoreIssueFrontierSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`

## Inputs and ownership

The module owns no queue, pointer, or mutable frontier state. Every candidate
is a compact projection of canonical `OooIexScheduleRow` residency:

- resident bit, independent typed-store classification bit, PE, and STID;
- exact logical `RobMemberKey` shared by STA and STD;
- first full LSID and first full store ID;
- one/two-beat request count.

`OooIexIssue` scans only the AGU and STD classes. A typed store routed to any
other class is rejected at S1. Restricting the reduction domain preserves the
rule while avoiding an all-eight-class network.

## Eligibility rule

For every STID, a balanced reduction selects the oldest resident full store
ID using modular serial comparison. A row is store-eligible only when its
complete logical-store key equals that frontier key. Consequently:

- STA child0 and STD child1 of the same oldest store may both advance;
- releasing only one child leaves the other child as the frontier and blocks
  every younger same-STID store across both classes;
- exact release or common recovery changes canonical residency and therefore
  recomputes eligibility without synchronizing another state owner;
- loads, non-memory rows, and unrelated STIDs bypass this filter.

The full LSID ordering must agree with the full store-ID ordering. Duplicate
serial ownership, a resident store with a missing order key, incomplete
logical identity, illegal request count, or SID/LSID order drift blocks all
target-STID stores and raises malformed observability. Peers remain live.

## Remaining gaps

- Add the canonical STQ's actual accepted issue-window/credit input.
- Define full-serial wrap quiescence before half-range ambiguity is possible.
- Add oldest-stuck safe-mode and bounded-liveness observability.
- Connect the store issue frontier, retained STA/STD lanes, lease owner, exact
  recovery, commit frontier, and simple LSU in the canonical static top.
- Close default geometry timing and run the benchmark gates.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreIssueFrontierSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueSpec
bash tools/chisel/build_chisel.sh
```

Focused tests cover same-store dual children, younger same-STID blocking,
peer/non-store bypass, exact release-driven advance, 40-bit wrap, duplicate
owner failure, missing-order-key failure, and cross-class AGU/STD integration.
