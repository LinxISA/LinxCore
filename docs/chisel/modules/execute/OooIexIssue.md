# OooIexIssue

## Purpose

`OooIexIssue` is the residency boundary between the OOO
coordinator's terminal S1 publication and later IEX pick/execute logic. It
owns physical IQ row installation and readiness, but it does not yet implement
execution pipes.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexRecoverySpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooO3IexIntegrationSpec.scala`

The first downstream read-stage packet is documented separately in
[`OooIexP1I2Lane`](OooIexP1I2Lane.md). It consumes a selected joined row but
does not take ownership of the physical IQ entry.
Oldest-ready selection and canonical speculative ownership are documented in
[`OooIexOldestReadyPicker`](OooIexOldestReadyPicker.md).
The exact selected-payload join and executable one-/multi-domain compositions are
documented in [`OooIexPickP1Bridge`](OooIexPickP1Bridge.md) and
[`OooIexIssueP1Lane`](OooIexIssueP1Lane.md), plus
[`OooIexIssueP1Fabric`](OooIexIssueP1Fabric.md).
The first class-specific terminal handoff is documented in
[`OooIexE1TransferSlot`](OooIexE1TransferSlot.md).
Its static multi-domain and parameterized release-port topology are
documented in [`OooIexE1TransferFabric`](OooIexE1TransferFabric.md).

## Stage ownership

| Stage | Retained owner | Terminal condition |
|---|---|---|
| OOO S1 | one row per STID plus exact target claims | selected by fair shared S2 writer |
| IEX S2 | exact physical IQ rows in `BoundS2` | one complete registered cycle |
| IEX S3 | exact rows in `ResidentS3`, including canonical `inFlight` | exact retry, non-cancellable terminal release, or O7 recovery |

S1 admission revalidates O3, P, T/U, and dispatch identities. Every child of a
split transaction is accepted or rejected together. Pending S1 claims exclude
same-target admission from other STIDs without pretending the physical row has
already been written.

S2 binds at most one STID transaction per cycle. It consumes the earlier
dispatch reservation rather than selecting a new entry. Newly written rows are
not pickable until the following S3 transition.

## Scheduling row and execution sidecar

Each physical slot has two storage domains joined as `OooIexIssueRow`:

- `OooIexScheduleRow` is resettable, frequently scanned state used by wakeup,
  pick, exact release, and recovery;
- `OooIexPayloadSidecar` is a memory-backed execution payload addressed by the
  stable class/bank/slot reservation and read only for the selected query.

A separate nine-bit `capabilityRows` array retains the generated requirement
for each physical child without widening the frequently copied public row.
S1 requires exactly one capability for every allocated class child. S2 writes
that requirement with the scheduling row, and S3 checks it against the static
physical-domain capability before candidate eligibility, retained-token claim,
retry acceptance, or query pickability. A capability-mismatched row remains
resident and not in flight; it is never silently redirected to an incompatible
pipe.

The scheduling row contains exact PE/STID/epoch/transaction, canonical
speculative `inFlight`, and
`RobMemberKey`, the class/bank/write-port/entry/reservation generation, and
generation-qualified P/T/U source/destination tags. The payload sidecar keeps
the canonical uop key, opcode, generated recipe, split-child index, primary
prediction, PC-buffer tokens, derived primary-parent index, and
immediate/boundary/template/trap/close controls. I0.9c also keeps the typed
`OooMemoryControl` here, so selected AGU rows retain D1-normalized address
mode, byte offset, access size, index transform, and child source masks without
adding those wide controls to the wakeup/recovery scan row. I0.9g adds the
logical uop's full memory-order allocation beside those controls. S1 accepts a
memory transaction only when its exact per-STID lease identity joined the O3
publication; S2 copies the logical range into every physical child sidecar.
STA and STD therefore share the same logical store range without treating an
IQ slot or future STQ row as the full SID/LSID authority.

I0.15c-a also retains each GPR destination's displaced physical mapping in the
wide sidecar. The compact scheduling row still carries only the current PTag;
the previous PTag is read only by the canonical load-allocation/return ABI and
does not become a wakeup, pick, or recovery comparison key. Focused residency
UT proves both mappings survive S1/S2/S3 publication.

The joined query view preserves the existing execution contract. Release or
recovery invalidates only the scheduling row; stale payload memory is
unreachable until a later exact S2 bind overwrites that slot. Rename-owner
structures are not copied into the IQ. SMAP, CMAP, P/T/U MapQ, and retirement
relations remain owned by RENU/commit. The wide sidecar therefore does not
participate in every wakeup/recovery comparison, and IEX does not become a
second rename or recovery authority.

## Split-store projection

I0.9f keeps one canonical decoded store and creates its two physical children
only at the reserved S1/S2 boundary. D1 records two disjoint masks in
`OooMemoryControl`: `addressSourceMask` and `dataSourceMask`. The masks must be
disjoint, their union must equal every valid decoded source, and the generated
`pSourceCount` must equal that union's population count.

The child contract is fixed and fail-closed:

- child 0 must use the AGU class and receives only address operands;
- child 1 must use the STD class and receives only store-data operands;
- S1 rejects swapped or otherwise mismatched child classes;
- S2 installs each projected source set in its own physical row while
  preserving the common parent/member/LSID and recipe identity;
- a store writeback destination is retained only by the AGU child; STD never
  becomes a second destination producer.

Immediate scalar stores therefore project `{data=source0, base=source1}`.
Indexed scalar stores project `{data=source0, base=source1, index=source2}`.
Immediate pair stores project `{data0,data1,base}`, and indexed pair stores
project `{data0,data1,base,index}`. PCR stores have no register address source;
their AGU child obtains the primary-parent PC through the generated PC-read
policy, while STD consumes only the data source.

## Wakeup and release

Wakeups compare full STID/epoch and physical generation. They update only
registered source-ready state, so wakeup N can affect pick eligibility no
earlier than N+1. A generation-qualified P scoreboard and per-STID T/U
sequence scoreboards retain completed-producer state for consumers that arrive
after the wakeup pulse. Installing a new physical destination clears the
matching ready entry. A `ResidentS3` row is pickable only when all valid
sources are registered ready.

Oldest-ready pick uses modular `{ridGeneration,ridSlot,memberIndex}` order
inside each STID and work-conserving round-robin across STIDs. The selected
token is retained under P1 backpressure. Its fire sets `inFlight` only in the
canonical scheduling row. An exact retry clears that bit; stale or malformed
claims/retries produce typed rejects.

`iexIssueDomainCount` vectorizes picker/query/retry functions; it is not the
number of physical IQ residency owners. Every picker projects a bank mask for
each class from the same `scheduleRows` and `payloadRows` owner. Overlapping
class/bank projections are legal only when the two pickers' elaboration-time
capability sets are disjoint. A hardware assertion rejects every other
overlap. Candidate eligibility applies the capability predicate before
oldest-ready selection, so AGU LDA and STA pickers can observe one residency
owner without ever claiming the same row. The canonical `inFlight` update is
still the final same-cycle collision guard. Retry is accepted only by the
picker whose projection and capability currently cover the reservation.
Domain-zero aliases preserve the focused one-lane interface.

Release is fail-closed. It requires an in-flight row, the exact member, and
complete dispatch reservation, including class-local entry, write port, and
reservation epoch. `iexReleaseWidth` provides independent Decoupled release
lanes, and each accepted lane removes its IQ row and returns its dispatch
allocation on the same fire. Independent physical rows may release together.
If two valid lanes name the same physical `{class,bank,entry}`, every
colliding lane is rejected and the row remains resident; the owner never
chooses a winner for an ambiguous double-free request.

## Recovery

The owner consumes a compact `OooResidencyRecoveryPlan` projected from the
complete grouped-ROB plan. Prepare captures the immutable request, validates
retained S1 immediately, then scans one parameterized entry slice from every
physical class/bank per cycle. The default
`iexRecoveryScanEntriesPerBankPerCycle=1` takes
`iqEntriesPerBank` scan cycles; larger power-of-two divisors trade read/CAM
width for latency. Capture plus scan exposes prepared-ready after
`iexRecoveryScanCycles + 1` cycles.

The scan is side-effect free. It overwrites every stored row-kill bit during a
complete pass and accumulates exact `BoundS2`/`ResidentS3` counts, pending-S3
lane membership, and generation-qualified P/T/U ready-scoreboard kill masks.
Changing the offered plan while it is retained or finding a malformed live
row rejects the request. Deasserting prepare before `Prepared` aborts only the
scan metadata. Neither case mutates S1/S2/S3 or readiness state.

P-ready is a global scoreboard, so a numerical PTag can be recycled and
rewritten by a peer while recovery is retained. Capture snapshots the complete
`{valid,generation,stid,epoch}` owner identity used by the scan. Common apply
clears a retained P-ready mask bit only when the live identity still equals
that snapshot; a new generation/STID/epoch survives unchanged.

Prepare freezes target-STID admission, transition, release, and wakeup while
unrelated STIDs continue. After every slice validates, the owner holds the
prepared result and its exact masks until the global common apply. Apply
prunes killed lanes from retained S1 and pending S3, frees exact killed
`BoundS2` and `ResidentS3` rows, and clears matching P/T/U readiness records.
A surviving partial pivot remains resident and pickable. Because wakeup is a
non-backpressured `Valid` input, target-STID wakeup during prepare is an
assertion failure rather than a silently dropped readiness event; global R0-R4
must quiesce those producers before prepare.

## Remaining gaps

- The formal 12-owner/14-picker residency/capability profile, runtime
  capability admission, and dual AGU LDA/STA pickers are implemented. Shared
  DIV/PAC/SYS arbitration remains open; broad dispatch class alone remains
  insufficient.
- Canonical-top wiring for the implemented P/T/U RF owners, shared read-port
  arbitration, speculative-ready, exact bypass, and load-cancel paths;
  physical result/wakeup/LSU-resolve producers remain open.
- Store AGU/STD execution owners, canonical STQ address/data join, physical
  STQ lease generation, ordered issue/visibility, and terminal publication
  remain open. Full logical LSID/SID allocation and exact recovery are closed;
  the STQ must consume those identities rather than allocate replacements.
- Instantiate and measure the formal class/bank/release-port topology in the
  canonical top. Static multi-class domain composition, parameterized simultaneous exact release,
  duplicate-target rejection, atomic transfer/release, and owner-side
  in-flight release guard are implemented.
- O8 bank/port occupancy plus retained-inflight cost steering, PTag coupling,
  safe-mode thresholds, and default-geometry timing/area closure. The
  unbounded dispatch slot encoder is closed by O8.2's bounded hierarchy.
- Per-class multi-pick liveness counters and coverage closure.

The legacy `ReducedScalarIssue*` modules remain compatibility evidence until a
later IEX packet replaces their top-level consumers. `OooIexP1I2Lane` now
replaces their read-stage semantics for the canonical path. They are not used
as the semantic authority for this module.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexIssue
bash tools/chisel/run_chisel_tests.sh --only OooIexOldestReadyPicker
bash tools/chisel/run_chisel_tests.sh --only OooIexPickP1Bridge
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1Lane
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1Fabric
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferFabric
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueE1Integration
bash tools/chisel/run_chisel_tests.sh --only OooIexRecovery
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec
```

The focused UT covers retained-stage timing, fair STID arbitration, target
claim collisions, split atomicity, compact row payload, registered wakeup,
same-edge wakeup plus S2 bind, oldest-ready claim, same-edge claim/retry,
later retry-to-repick, exact
in-flight release/backpressure, malformed requests,
and widths 2/4/6. The compact recovery UT covers exact scan latency,
side-effect-free partial-scan abort, partial-pivot S1/S2/S3 pruning, survivor
residency, complete cancellation, plan-drift/malformed-row rejection,
cross-STID progress while the target scans, configurable two-entry bank
slices, and PTag recycle/reuse across retained common apply. The IT connects
the real O3/RENU/dispatch
coordinator and proves publication, residency, recovery preparation, common
apply, and dispatch-slot return share the exact transactions.

I0.3 additionally covers every generated recipe form, D1 propagation, exact
primary-parent indexing, physical-child PC class selection, bridge
backpressure/rejection, and an end-to-end S1/S2/S3 → P1/I1 denial → exact
repick → I2 transaction.

I0.4 adds a two-picker integration case that publishes ALU and BRU rows in one
S1 transaction, claims both in parallel, grants ALU while denying BRU, proves
the denial cannot disturb the peer picker, repicks BRU, releases both exact IQ
rows, and observes aggregate quiescence. It then runs two ALU pickers on
disjoint banks. A negative case proves overlapping same-class/same-capability
bank projections trip the topology assertion.

The physical-capability packet adds a negative S3 case in which a multi-cycle
ALU row resides in a bank owned by a simple-only domain. The row remains
resident, no read attempt is emitted, and canonical `inFlight` remains clear.
The matching D3 and E1 tests prove legal-bank steering and fail-closed retained
transfer, respectively.

O8.1/O8.1b elaboration evidence uses the same first 2-bank x 4-entry stage
test. Splitting the wide payload into inferred memory first reduced the main
module from 477,275 to 426,274 SystemVerilog lines. Retaining and timing the
compact-row recovery scan reduced it again to 173,709 lines: 63.6% below the
pre-split baseline and 59.2% below O8.1. The directly comparable recovery
scenario falls from about three minutes to 47.046 seconds; the expanded
three-test recovery suite passes in 232.166 seconds. This closes the unbounded
recovery CAM. O8.2 separately closes the unbounded dispatch slot encoder; O8
remains open for cost steering, physical PC metadata/write macro realization,
and the grouped-ROB commit prefix path. PC recovery compare/read depth is
bounded by O8.3h; ROB, MapQ, and PC address banking remain established
boundaries rather than final timing claims.

I0.2 adds a separate 3,718-line picker and canonical in-flight bookkeeping.
The same 2-bank x 4-entry main IEX owner is now 176,457 lines, a bounded 1.6%
increase over the 173,709-line recovery-scan baseline. The picker has no
payload-memory reference.

I0.3 adds an explicit 913-line token-to-payload join. In the focused 2-bank ×
4-entry composition, `OooIexIssueP1Lane` is 8,244 lines and contains separate
158,376-line IQ, 913-line bridge, and 2,992-line P1/I1/I2 lane modules. The
bridge RTL has no `payloadRows` or `scheduleRows` storage reference; it only
checks the readyless joined row supplied by the IQ owner.
In the directly comparable first IEX stage test, the main owner is now 176,764
lines, 307 lines above I0.2 and 1.8% above the 173,709-line bounded-recovery
baseline.

I0.4's focused two-domain composition is 12,129 SystemVerilog lines around one
163,798-line `OooIexIssue`, two 913-line bridges, and two 2,992-line lanes.
The fabric contains exactly one `OooIexIssue` instance and no schedule/payload
storage reference. These are structural elaboration counts, not timing or area
claims.
