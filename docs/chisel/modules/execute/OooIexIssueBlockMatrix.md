# OooIexIssueBlockMatrix

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/ooo/OooIexIssueBlockMatrix.scala`
- Canonical IQ integration: `rtl/LinxCore/chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/ooo/OooIexIssueBlockMatrixSpec.scala`
- IQ integration tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`
- Contract ID: `LC-CHISEL-IEX-ISSUE-POLICY-001`

## Purpose

`OooIexIssueBlockMatrix` defines the physical early-block policy between
resident IQ readiness and oldest-ready selection. It keeps resource policy out
of opcode decode and prevents unrelated blockers from collapsing into one
unobservable `ready=0` bit.

The matrix is combinational and owns no IQ state. `OooIexIssue` applies the
same reason function to every candidate row and revalidates a retained picker
token before it may claim `inFlight`.

## Policy Inputs

| Input | Scope | Meaning |
|---|---|---|
| `globalQuiesce` | all domains/STIDs | Debug, reset preparation, or coordinated global stop. |
| `powerThrottle` | all domains/STIDs | Immediate execution-rate suppression from the power owner. |
| `classPressure` | class × STID | Shared occupancy/backpressure threshold for one uop class. |
| `loadQueuePressure` | STID | Blocks only `Agu && !isStore` candidates. |
| `storeWindowPressure` | STID | Blocks only typed stores in `Agu` or `Std`. |
| `domainStructural` | domain × STID | Pipe-local structural capacity is unavailable. |
| `latencyReservation` | domain × STID | A future fixed-latency result slot is reserved. |
| `reflowReservation` | domain × STID | Reflow owner reserves the physical pipe. |
| `sideDoorConflict` | domain × STID | LSU translation/reissue sidedoor owns the issue pipe. |
| `resultBusReservation` | domain × STID | Future writeback/result-bus collision would occur. |

## Outputs and Integration

The standalone module accepts one typed query per physical issue domain and
returns a stable ten-bit reason mask plus a domain blocked mask. The canonical
IQ additionally exports:

- `policyBlockedCount(domain)`: resident rows in enabled banks currently
  blocked by policy, regardless of operand readiness;
- `queryPolicyReasons(domain)`: reason mask for the addressed canonical row;
- `pickPolicyBlockedByDomain(domain)`: exact held token and reason mask when a
  policy change invalidates a previously retained picker choice.

Candidate blocking never clears row valid state or sets `inFlight`. If a
policy appears after token capture, the held token is consumed without an IQ
claim. Once the policy clears, normal oldest-ready selection sees the same
resident row again.

## Ordering Rules

- LDQ pressure is row-type qualified; it cannot block AGU store-address work.
- STQ-window pressure is store qualified; it cannot block load-address work.
- Class pressure follows the query class, while pipe/resource reasons follow
  the physical domain. Every reason is independently observable and may
  accumulate in one cycle.
- Recovery remains a separate authoritative kill/block mechanism. The policy
  matrix neither synthesizes a recovery plan nor mutates recovery state.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only OooIexIssueBlockMatrixSpec`
- exact integration case: `OooIexIssueSpec` test containing
  `policy blocks a resident row`
- `bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1FabricSpec`
- `bash tools/chisel/run_chisel_tests.sh --only OooIexIssueReadFabricSpec`
- `bash tools/chisel/run_chisel_tests.sh --only OooIexIssueE1IntegrationSpec`
- `bash tools/chisel/build_chisel.sh`

The UT covers load/store qualification, accumulated shared and private
reasons, global quiesce, and canonical wrapper elaboration. The IQ integration
case proves held-token invalidation leaves the row resident and permits a later
normal pick.

## Remaining Gaps

- exact I1/I2 stage-cancel and repick for conflicts discovered after P1;
- concrete LSU LDQ/STQ-window and sidedoor producers in the canonical static
  top;
- latency/reflow/result-bus reservation owners for every typed execution pipe;
- liveness counters and safe-mode peer suppression;
- measured static physical domain profile and final ALU/AGU/STD/BRU mapping.
