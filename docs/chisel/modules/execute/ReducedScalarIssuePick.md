# ReducedScalarIssuePick

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssuePick.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/execute/ReducedScalarIssuePickSpec.scala`
- Parent owner: `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
  - `model/LinxCoreModel/model/iex/iex.cpp`
  - `model/LinxCoreModel/model/iex/pipe/iex_pipe.h`
  - `model/LinxCoreModel/model/iex/pipe/alu_pipe.cpp`
- Contract IDs: `LC-IF-CHISEL-IEX-PICK-001`, `LC-IF-CHISEL-XCHK-008`

## Purpose

`ReducedScalarIssuePick` is the reduced scalar issue-owner boundary between
queue residency and execute capture. `ReducedScalarIssueQueue` owns row
storage, registered source readiness, release, and compaction. This module owns
the selected-row pick, RF read request shape, conservative read-confirm gate,
issue fire, and issue-block diagnostics.

This is not the full P1/I1/I2 issue pipeline. It is a single-cycle reduced
owner inserted so later packets can grow real P1 pick, I1 RF read arbitration,
I2 RF/bypass return, cancel, and replay without leaving that behavior buried in
queue storage code.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `selectableMask` | `UInt(depth.W)` | combinational | Queue-owned resident rows that are valid, not issued, and source-ready. |
| input | `entries` | `Vec(depth, RenamedUop)` | with `selectableMask` | Resident queue payloads. The picker reads only the selected row. |
| input | `headValid`, `headIssued` | `Bool` | diagnostic | Queue head state used for block-cause reporting. |
| input | `notIssuedCount` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Count of resident rows that have not yet issued. |
| output | `readValid` | `Vec(3, Bool)` | combinational | Selected row's valid source lanes. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | with `readValid` | Selected row's physical source tags. |
| input | `readReady` | `Vec(3, Bool)` | combinational | RF read readiness for the selected source tags. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | RF read data for the selected source tags. |
| output | `issueValid` | `Bool` | valid | Selected row exists and every requested RF read lane is ready. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the selected row. |
| output | `issueFire` | `Bool` | pulse | Selected row is accepted by execute and should be marked issued. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | Selected row payload for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | RF data to capture into execute. |
| output | `selectedValid`, `selectedIndex` | mixed | diagnostic | Oldest selectable row by queue slot. |
| output | `selectedReadReady` | `Bool` | diagnostic | Every valid source lane for the selected row has an RF-ready read response. |
| output | `blockedBySource` | `Bool` | diagnostic | Queue contains non-issued work but no selectable source-ready row. |
| output | `blockedByRead` | `Bool` | diagnostic | A selectable row exists, but RF read readiness is not confirmed. |
| output | `blockedByOutput` | `Bool` | diagnostic | Selected row is read-ready, but execute is backpressuring. |
| output | `blockedByIssued` | `Bool` | diagnostic | Head is issued and no younger row is selectable. |

## State

The module is combinational and owns no registers. Queue residency and
source-ready state remain in `ReducedScalarIssueQueue`; execute pipeline state
remains in `ReducedScalarAluExecute`.

## Logic Design

The picker scans `selectableMask` from high to low while later lower-index
matches overwrite the candidate, producing the lowest queue slot with a set bit.
That is the reduced queue-age order. `selectedValid` reports whether any row is
selectable, and `selectedIndex` points at the row used for RF read and execute
payload selection.

For the selected row, each valid source lane requests an RF read by physical
tag. Invalid lanes are treated as read-ready. `issueValid` is asserted only
when the selected row exists and all requested RF read lanes are ready. This is
a conservative reduced read-confirm gate: the row stays resident and unissued
when RF read readiness is not confirmed.

`issueFire` is `issueValid && issueReady`. The parent queue uses that pulse and
`selectedIndex` to mark the selected resident row issued without removing it.
Later release still comes from the execute owner by `(bid, rid, stid)`.

## Model Alignment

`IssueState::Select` picks in the issue stage and marks only the selected row
issued. The pipe model then separates stages: `P1` is the issue-stage
interface, `I1` generates RF read requests, and `I2` receives RF return and
bypass data before execution proceeds. `IEX::releaseIQEntry` later releases
issued rows from the queue according to the configured release point.

R87 mirrors only the first reduced slice of that split:

1. queue storage exports a selectable mask;
2. this module chooses the oldest selectable row;
3. the selected row drives RF read tags;
4. issue is confirmed only after the selected RF read lanes are ready;
5. issue fire marks the row issued, not deallocated.

Full read-port arbitration, cancellation, load-miss suppression, bypass
selection, I2 release timing, and replay remain future issue-owner packets.

## Timing

This reduced owner is combinational. It does not make wakeup visible
same-cycle: `selectableMask` is already based on the parent queue's registered
source-ready state. A row selected in the current cycle can issue only when the
current RF read response is ready and execute is ready.

## Flush/Recovery

The picker has no flush state. Flush clears the parent issue queue. Future
cancel/replay packets should add explicit issue-owner state rather than putting
cancelled P1/I1/I2 state back into queue compaction logic.

## Trace/Observability

The parent top exposes selected-valid/index, selected-read-ready, and block
causes. `blockedByRead` is the new R87 diagnostic for a selected row whose RF
read readiness was not confirmed.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssuePick`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
