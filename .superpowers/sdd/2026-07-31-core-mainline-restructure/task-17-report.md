# Task 17 report: distributed control and DTU observation

## Outcome

Task 17 adds one typed DTU observation boundary without moving commit,
trap, interrupt, debug-boundary, or recovery decisions out of OOO. DTU now
transports external halt/resume requests to the OOO owner, exports a
loss-tolerant trace stream, and publishes monotonic observation counters.

OOO retains halt requests until the requested STID reaches a precise commit
boundary. Synchronous faults take priority over debug and interrupts, and
interrupt selection excludes requests for another STID. Recovery continues to
use one common Prepare/Prepared/Apply barrier, while CTU fences only the target
STID so unrelated work can progress.

No displaced stateful debug, trace, or system-control wrapper was named by the
closed-owner inventory. `CommitTraceMonitor` remains a stateless checker used
by legacy ROB fixtures and is not a competing DTU or commit owner, so this task
deleted no wrapper.

## Implementation

- Added `DTU`, `DebugControl`, `TraceExport`, and `PerformanceCounters`.
  `DebugControl` is a one-entry typed transport queue; it does not interpret a
  command or select a boundary. `TraceExport` always accepts observations and
  retains at most one externally visible packet. Counters never qualify a
  control ready/valid path.
- Extended `DTUIO` with the owner-facing debug channel, trace export, and
  counter vector. Extended `OOOIO` and the D3/S1 graph with the matching typed
  debug channel.
- Added OOO-owned halt state and debug response generation to
  `CommitControl`. A pending halt survives a higher-priority synchronous fault
  and is applied at the next matching precise boundary. Resume clears the
  halted state.
- Qualified both commit and recovery interrupt arbitration by the boundary
  STID before priority reduction.
- Made CTU recovery admission, buffered output, and trace fences local to the
  plan target STID.
- Instantiated DTU in the canonical OOO/IEX/LSU activation graph and connected
  commit, trace, and debug control paths without adding an adapter owner.
- Updated the interface manifest, closed-owner manifest, checker tests, and
  L1/L2/L3 behavior contracts.

## TDD evidence

The initial `DTUSpec` and `RecoveryIntegrationSpec` failed to compile because
`linxcore.dtu.DTU` and its mechanisms did not exist. The first behavior run
then exposed two distributed-control failures: interrupt reduction selected a
higher-priority request for the wrong STID, and CTU recovery fencing blocked
unrelated-STID admission. A separate debug-boundary RED proved that
`CommitControl` had no typed halt/resume input or OOO-owned halted state.

The focused fixes closed those failures without changing public identity
widths or the memory attempt-ownership contract.

## Verification

- `DTUSpec` — PASS, 4/4. Artifact size 167,214,581 bytes under a
  300,000,000-byte cap.
- `RecoveryIntegrationSpec` — PASS, 5/5. Artifact size 382,485,994 bytes under
  the task's 900,000,000-byte cap.
- `CommitControlDebugSpec` — PASS, 1/1. One simulation covers normal commit,
  precise same-STID interrupt selection, synchronous-fault priority over a
  pending halt and interrupt, debug halt, and resume. Artifact size 87,755,548
  bytes under a 300,000,000-byte cap.
- `RecoveryControlBarrierSpec` — PASS, 1/1. One simulation covers single
  prepare fanout, missing-acknowledgement stall, and common apply. Artifact
  size 60,756,922 bytes under a 300,000,000-byte cap.
- `CTUSpec` — PASS, 11/11.
- `OOOCommitApplyPolicySpec` — PASS, 1/1.
- `bash tests/test_trace_schema_and_mem.sh` — PASS. Its negative-path fixture
  intentionally prints `error: missing trace output`.
- `python3 tools/chisel/trace_schema_adapter.py --self-test` — PASS.
- `python3 tools/chisel/render_top_interface_manifest.py --check` — PASS; the
  checked-in manifest is current.
- `python3 tools/chisel/check_production_owner_manifest.py` — PASS: 27 closed
  owners, 24 classified emitters, 6 declared adapters, and L1/L2/L3 roles
  mapped.
- `python3 -m unittest tests.test_production_owner_manifest -v` — PASS, 46/46.
- `git diff --check` — PASS.

`OOORobCommitSpec` was stopped as a resource failure rather than classified as
a test failure: status 130, child status -9, 1,832,601,201 artifact bytes, and
3,490,054,144 peak process-tree RSS. It was not rerun. Inspection found that
`chisel_test_supervisor.py` checks the artifact budget only after a successful
child exit, so the configured cap reports an excess but does not enforce it
during elaboration. The supervisor was not changed because no independent RED
for that tool is part of Task 17.

The NDF local-reference checker still reports the pre-existing unrelated gap
`docs/spec/40-constraints/parameters.md:72: missing verifies edge for L1 MUST
PRM-LSU-SIZING-001`. Task 17 did not modify that parameter contract.

## Source identities

- LinxCore baseline: `ff02aefaba8822b53f6fecf40e941017973ab961`
- LinxISA superproject: `54635e8cb111`
- LinxCoreModel reference: `3ca25e05d2a2`
- QEMU reference: `c9f9570aa70d`

## Assumptions and limits

- Trace export is deliberately loss-tolerant. A retained packet remains stable
  while stalled; a newer observation may be counted and dropped.
- The existing OOO/IEX/LSU activation graph is the smallest live caller before
  Task 18 assembles TOP. DTU ownership and wiring therefore remain explicit
  without prematurely creating the final top-level composition.
- Six pre-existing x86 firtool processes remain in kernel-uninterruptible
  state. They are unrelated to Task 17; reproducible Task 17 build artifacts
  are removed before handoff.
