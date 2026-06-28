# CommitTraceMonitor

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/commit/CommitTraceMonitorSpec.scala`
- Previous pyCircuit owner: `rtl/LinxCore/src/probes/commit_probe.py`, `rtl/LinxCore/src/bcc/backend/modules/commit_trace_stage.py`
- LinxCoreModel evidence: `model/LinxCoreModel/model/interface/CommitInfo.h`, `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
- Contract IDs: `LC-IF-CHISEL-XCHK-002`, `LC-IF-CHISEL-CMT-001`

## Purpose

`CommitTraceMonitor` is a combinational contract checker for fixed-width Chisel
commit windows. It does not own retirement. It validates that a producer such as
`ReducedCommitROB`, future integrated CMT, or a top-level trace bridge presents
rows in the shape required by the neutral QEMU/DUT adapter.

Phase 1 wires the monitor into `ReducedCommitROB` so the generated-RTL reduced
ROB xcheck asserts the same contract that standalone monitor tests exercise.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `in` | `CommitTracePort` | row `valid` | Fixed-width commit rows from a retire owner. |
| output | `validMask` | `UInt(commitWidth.W)` | combinational | One bit per valid row. |
| output | `validCount` | `UInt` | combinational | Number of valid rows in the window. |
| output | `skippedSlot` | `Bool` | combinational | A younger row is valid after an older invalid row. |
| output | `duplicateIdentity` | `Bool` | combinational | Two valid rows share the same `identity.(bid,gid,rid)`. |
| output | `slotMismatch` | `Bool` | combinational | A valid row's `slot` field does not match its vector position. |
| output | `invalidSideEffect` | `Bool` | combinational | An invalid row still carries an active operand, memory, or trap envelope. |
| output | `contractError` | `Bool` | combinational | OR of all monitor error flags. |

## State

The monitor is stateless. It is intended to sit beside a real state owner and
can be used in top-level lint/simulation harnesses without changing retire
behavior.

## Logic Design

The monitor computes the same `validMask` and `validCount` shape used by
`CommitTraceWindow`, then checks four structural errors:

- commit windows must be a prefix of valid slots starting at slot 0,
- valid rows in one window must not duplicate LinxCoreModel `CommitInfo`
  identity,
- valid rows must label their own fixed vector position in `slot`,
- invalid fixed-width rows must not expose active writeback/source/destination,
  memory, or trap side-effect envelopes.

The duplicate check deliberately uses `identity.(bid,gid,rid)` only. The
64-bit hardware `blockBid` remains a separate sideband and is not part of model
commit identity.

## Timing

All outputs are combinational from the current input window. Future top-level
assertion or trace-export logic may register `contractError`, but this monitor
does not add a pipeline stage.

## Flush/Recovery

The monitor has no recovery state. Flush and pointer rebasing remain owned by
the ROB/CMT and recovery modules. The monitor can still flag an illegal
post-flush trace window if a killed row is emitted as valid or if an invalid row
leaks side-effect envelopes.

## Trace/Observability

`contractError` is intended to become a simulation-time assertion input for the
Chisel top and Verilator harnesses. During Phase 1 it provides a reusable
structural check before full frontend/decode/execute rows exist. The reduced
ROB harness currently asserts it for live generated RTL.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor`
- `bash tools/chisel/run_chisel_tests.sh --only CommitTrace`
- `bash tools/chisel/run_chisel_reduced_rob_xcheck.sh`

Current tests cover valid contiguous windows, skipped slots, duplicate
`CommitInfo` identities, invalid-slot side effects, Chisel elaboration, and
monitor-clean generated-RTL reduced ROB retire windows.
