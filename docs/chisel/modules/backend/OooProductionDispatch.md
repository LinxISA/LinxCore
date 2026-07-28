# OooProductionDispatch

## Purpose

`OooProductionDispatch` is the D3 owner of exact speculative physical-IQ
reservations. It compacts generated per-class demand in logical-uop order,
selects a class-local bank/write-port/entry tuple for every child, and retains
that tuple as a generation-qualified lease until common publication,
cancellation, recovery, or exact I2 release.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooProductionDispatch.scala`
- `chisel/src/main/scala/linxcore/ooo/OooHierarchicalFreeSlotSelect.scala`
- `chisel/src/main/scala/linxcore/ooo/OooParams.scala`
- `chisel/src/test/scala/linxcore/ooo/OooProductionDispatchSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooHierarchicalFreeSlotSelectSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooO3IexIntegrationSpec.scala`

The behavioral reference is
`tools/LinxCoreModel/model/iex/iex_dispatch.cpp::DispatchUnit::dispatch`. The
model chooses a compatible IQ using resident capacity, same-cycle insert
count, configured reservation thresholds, and insertion-port limits. It does
not prescribe a hardware free-entry encoder, so the hierarchical selector is
a Chisel physical implementation choice and may not change visible ordering
or admission.

## Reservation lifecycle

Each physical slot is exactly one of `Free`, `Provisional`, or `Published`.
The owner records class, bank, write port, entry, reservation epoch, PE, STID,
frontend epoch, transaction, and final `RobMemberKey`. A complete split bundle
reserves and publishes atomically. Cancellation applies only to provisional
leases; normal release applies only to the identical published owner.

Recovery is prepared from the grouped-ROB residency plan and remains
side-effect free until the common O3 apply. It cancels the target STID's
provisional lease and the exact published suffix while preserving unrelated
STIDs.

## Hierarchical free-entry selection

`iqFreeSelectLeafEntries` is the maximum leaf width. The effective leaf width
is `min(iqFreeSelectLeafEntries, iqEntriesPerBank)`, so production's default
32-entry bank forms eight 4-entry leaves while small 2-entry unit-test banks
form one 2-entry leaf without configuration churn. Both sizes must be powers
of two, the configured maximum cannot exceed eight, the effective leaf must
divide the bank, and a production configuration cannot exceed eight leaves.
Every legal `OooParams` selector is therefore at most 8-by-8; parameter
overrides cannot recreate a bank-wide encoder accidentally.

For each candidate bank and dispatch lane:

1. Remove slots already selected by older lanes in the same bundle.
2. Form one `hasFree` bit and one bounded local first-free index per leaf.
3. Select the lowest nonempty leaf.
4. Concatenate leaf and local indices to reproduce the prior lowest-index
   first-free result.
5. Admit the candidate only if its bank still has a write port for this
   bundle.

The selector is a separate reusable hardware module. At default geometry its
generated RTL has an eight-way leaf selector and eight four-way local
selectors; no priority chain spans all 32 entries. The existing deterministic
bank rotation, older-prefix rule, write-port accounting, lease payload, and
generation update are unchanged.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooHierarchicalFreeSlotSelect
bash tools/chisel/run_chisel_tests.sh --only OooProductionDispatch
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The selector UT covers empty, first/last, cross-leaf, one-entry-leaf, and
one-leaf cases. Dispatch UT fills slots `0/1`, then `2/3`, then crosses into
`4/5`, while retaining reserve/publish/release, stale-generation, port-budget,
recovery, and 2/4/6 elaboration coverage. The focused 2-bank x 4-entry main
module changes from 30,753 to 30,859 SystemVerilog lines (+0.34%) because the
physical hierarchy is explicit. The default 32-entry selector is 44 lines of
generated SystemVerilog and exposes only 8-entry and 4-entry priority levels.

The legacy four-STID randomized O3 test remains a separate O8.3 blocker: its
software reference uses cumulative issue count as live ROB occupancy after
suffix recovery, and a causal rerun with the old full-width selector fails at
the identical `1 != 2` assertion. Its absolute wrap-qualified ROB tail model
must be repaired together with ROB recovery/banking; O8.2 does not weaken or
mask that failure.

## Remaining production gaps

- occupancy plus retained-inflight bank cost and destination-PTag-aware
  steering;
- one-cycle-ahead steering and configurable safe-mode thresholds;
- O8.3 ROB/MapQ/PC banking and absolute recovery-tail closure;
- P1/I1/I2 and execution-side release integration.
