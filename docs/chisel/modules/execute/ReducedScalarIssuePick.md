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
the selected-row P1 pick lock, I1 RF read request, I1 read-cancel, I2 execute
handoff, and issue-block diagnostics.

This is one bank's P1/I1/I2 timing slice. `ScalarIssueFabric` arbitrates shared
RF reads and I2 output across pickers. Bypass selection, load-miss suppression,
and speculative replay remain outside this child.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `selectableMask` | `UInt(depth.W)` | combinational | Queue-owned resident rows that are valid, not issued, and source-ready. |
| input | `entries` | `Vec(depth, RenamedUop)` | with `selectableMask` | Resident queue payloads. The picker reads only the selected row. |
| input | `headValid`, `headIssued` | `Bool` | diagnostic | Queue head state used for block-cause reporting. |
| input | `notIssuedCount` | `UInt(log2Ceil(depth + 1).W)` | diagnostic | Count of resident rows that have not yet issued. |
| input | `flushValid` | `Bool` | pulse | Clears P1/I1/I2 owner state. Parent queue separately clears residency. |
| output | `readValid` | `Vec(3, Bool)` | I1 valid | I1 row's valid source lanes. No RF read is requested in P1. |
| output | `readTags` | `Vec(3, UInt(physRegWidth.W))` | with `readValid` | I1 row's physical source tags. |
| output | `readOperandClass` | `Vec(3, OperandClass)` | with `readValid` | I1 row's source operand classes for scalar RF versus local T/U read selection in the parent top. |
| output | `readRelTag` | `Vec(3, UInt(archRegWidth.W))` | with `readValid` | I1 row's frontend relative tags used for local T/U queue lookup. |
| input | `readData` | `Vec(3, UInt(immWidth.W))` | combinational | RF read data captured when I1 advances to I2. |
| input | `readGrant` | `Bool` | I1 grant | Parent fabric admitted this bank's complete read group. |
| output | `readAttemptValid`, `readFire` | `Bool` | attempt/fire | I1 can accept into I2, and the granted group advanced. |
| output | `readUop` | `RenamedUop` | with `readAttemptValid` | Identity supplied to shared same-STID-age/cross-STID-RR arbitration. |
| output | `issueValid` | `Bool` | valid | I2 row is present and can be offered to execute. |
| input | `issueReady` | `Bool` | ready | Downstream execute can accept the I2 row. |
| output | `issueFire` | `Bool` | pulse | I2 row is accepted by execute. Queue residency is still released later by ALU release. |
| output | `issueUop` | `RenamedUop` | with `issueValid` | I2 row payload for execute. |
| output | `issueSrcData` | `Vec(3, UInt(immWidth.W))` | with `issueValid` | I2-captured RF data to pass into execute. |
| output | `pickFire` | `Bool` | pulse | P1 selected a resident row and parent queue must lock it as in-flight. |
| output | `cancelFire` | `Bool` | pulse | I1 read readiness failed; parent queue must clear the in-flight lock for `cancelIndex`. |
| output | `cancelIndex` | `UInt(log2Ceil(depth).W)` | with `cancelFire` | Queue slot whose in-flight lock is cancelled. |
| output | `i1Valid`, `i2Valid`, `stageBusy` | `Bool` | diagnostic | Reduced issue-owner stage occupancy. |
| output | `selectedValid`, `selectedIndex` | mixed | diagnostic | Oldest selectable row by queue slot. |
| output | `selectedReadReady` | `Bool` | diagnostic | I1 read readiness when I1 is occupied; otherwise the current P1 candidate's read readiness. |
| output | `blockedBySource` | `Bool` | diagnostic | Queue contains non-issued work but no selectable source-ready row. |
| output | `blockedByRead` | `Bool` | diagnostic | I1 row requested RF reads, but readiness was not confirmed and the pick will cancel. |
| output | `blockedByOutput` | `Bool` | diagnostic | I2 row is valid, but execute is backpressuring. |
| output | `blockedByIssued` | `Bool` | diagnostic | Head is issued and no younger row is selectable. |

## State

The module owns the reduced issue pipe registers:

- `i1Valid`, `i1Index`, `i1Uop`: selected P1 row after the queue lock is set.
- `i2Valid`, `i2Uop`, `i2SrcData`: granted RF-read row waiting for execute
  acceptance.
- `rrBase`: rotating bank-local start slot used after reducing selectable rows
  to one oldest candidate per STID.

Queue residency and source-ready state remain in `ReducedScalarIssueQueue`;
execute pipeline state remains in `ReducedScalarAluExecute`.

## Logic Design

The picker first suppresses every selectable row that has an earlier selectable
peer with the same STID. It then scans the surviving per-STID oldest candidates
from `rrBase`. Pick advances the pointer past the chosen slot. This preserves
oldest-ready order within an STID without comparing unrelated STID ages.

P1 fires when a candidate exists and I1 can accept a new row. `pickFire`
locks the parent queue slot as in-flight, then the selected payload is captured
into I1 on the clock edge. I1 drives the read tags, operand classes, and
relative tags for the captured row. The parent top may use those sidebands to
route scalar P reads to the RF and local T/U reads to a local-value owner. If
I1 presents `readAttemptValid` only when I2 can accept. A parent `readGrant`
captures all `readData` lanes into I2 atomically. Denial emits
`cancelFire/cancelIndex`; the parent bank clears `inflight` but preserves the
resident row and source readiness. I2 backpressure suppresses a new attempt
instead of creating a false denial.

I2 owns the valid/ready boundary into `ReducedScalarAluExecute`. `issueValid`
is I2 occupancy, and `issueFire` means execute accepted the I2 row. Issue fire
does not remove the queue entry; later release still comes from the execute
owner by `(bid, rid, stid)`.

## Model Alignment

`IssueState::Select` picks in the issue stage and marks only the selected row
issued. The pipe model then separates stages: `P1` is the issue-stage
interface, `I1` generates RF read requests, and `I2` receives RF return and
bypass data before execution proceeds. `IEX::releaseIQEntry` later releases
issued rows from the queue according to the configured release point.

R88 mirrors the reduced P1/I1/I2 slice of that split:

1. queue storage exports a selectable mask;
2. this module chooses one oldest selectable row per STID, round-robins among
   those candidates in P1, and asks the parent
   queue to lock it in-flight;
3. I1 drives RF read tags for the captured row;
4. failed I1 arbitration cancels the in-flight lock without deallocating the
   queue row;
5. I2 presents the captured row/data to execute;
6. execute acceptance and later ALU release stay separate.

Load-miss suppression, bypass selection, wider issue, and speculative replay
remain future issue-owner packets.

## Timing

This reduced owner has one P1 pick cycle, one I1 RF-read cycle, and one I2
execute-accept cycle. It still does not make wakeup visible same-cycle:
`selectableMask` is already based on the parent queue's registered source-ready
state. A row picked in P1 can issue to execute only after the I1 RF read
confirms and the row reaches I2.

## Flush/Recovery

`flushValid` clears I1 and I2 state. The parent queue separately clears
resident rows and in-flight locks. I1 read-cancel is intentionally not a flush:
it clears only the locked queue row and leaves residency/source-ready state
intact for a later pick attempt.

## Trace/Observability

The parent top exposes P1 pick, I1/I2 stage-valid state, I1 cancel, selected
valid/index, selected-read-ready, and block causes. `blockedByRead` identifies
the I1 cancel condition.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssuePick`
- `bash tools/chisel/run_chisel_tests.sh --only ReducedScalarIssueQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendRfAluTraceTop`
- `bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
