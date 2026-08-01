# Task 11 Report: Canonical OOO D3/S1 Production Cutover

## Result

Task 11 cuts the public `linxcore.ooo.OOO` module onto one canonical
D1-through-S1 owner graph. RENU, ROB, BROB, Dispatch, CommitControl, and
RecoveryControl now share the one D3 publication boundary; the production
graph does not instantiate `OooO3RenameCoordinator` or any displaced legacy
ROB/BROB/rename/recovery wrapper.

Baseline LinxCore commit: `5f80ea0ba0dc563f097082f2f0cf59d42b816e16`.
Baseline superproject commit: `54635e8cb1119e5f199228cb7db330b168bf7dc0`.
Baseline LinxCoreModel commit: `31555fbcb147db4bba20ab7031c40a4800635306`.
Baseline QEMU commit: `c9f9570aa70da7e193ff8857bd9bde2cf052e546`.

Final commit SHA is reported from `git rev-parse HEAD` after commit creation;
it cannot be embedded self-referentially in the same commit object.

## RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec` failed at
  compile with `not found: type Dispatch`, proving the canonical D3/S1 public
  boundary did not exist before implementation.

## Canonical Mapping

- `D3RenameGroup` is retained only after the caller-controlled common ROB,
  BROB, and dispatch publication fire. Exact ROB/BROB prepared counts and
  lane identities are required before dispatch reports ready.
- The ROB-prepared `RobIdentity` replaces the provisional identity in every
  active retained uop; all other renamed uop fields are preserved.
- `OooHierarchicalFreeSlotSelect` is in the live `OooDispatch` graph and
  selects the oldest active suffix lane. It now supports the canonical W6
  non-power-of-two width while retaining power-of-two group sizing.
- Dispatch drains one continuous oldest-first prefix. Backpressure retains
  only the undispatched suffix and preserves transaction IDs.
- Stores require atomic AGU and STD credit and send identical transactions to
  both channels. CMD remains independent from system/multicycle dispatch.
- Early-complete and boundary lanes consume no dispatch channel; completion
  remains owned by the Task-9 ROB publication path.
- Recovery apply suppresses coincident traffic and removes only a matching
  target-STID retained suffix. Abort is non-mutating.

## Displaced Entry-Point Audit

| Displaced production surface | Canonical owner / surviving coverage |
| --- | --- |
| `OooO3RenameCoordinator`, `OooO3IexStorePipeline` | public `OOO` + `Dispatch`; `OOODispatchSpec`, `OOOIntegrationSpec` |
| `OooD3ReservationAllocator` legacy reservation protocol, legacy `OooDispatch` protocol | mechanisms refactored in place onto canonical payloads; exact prepare reject, suffix retry, atomic credit, recovery, width, and selector tests migrated to `OOODispatchSpec` and `OooHierarchicalFreeSlotSelectSpec` |
| `OooS1GroupedRob`, `OooBrob`, `OooRobBrobPcCoordinator` | `ROB` + `BROB`; `OOORobCommitSpec`, `OOORecoverySpec`, `OOOIntegrationSpec` |
| `OooRobStoreCommitOwner` | `CommitControl` + `ROB`; `OOORobCommitSpec` |
| `OooPRename`, `OooTURename` | `RENU` / `PRename` / `TURename`; `RENUSpec`, `RENUAtomicSpec`, `TURenameSequenceSpec` |
| `OooCtuIngressBridge`, `OooIfuD1Ingress`, `OooIfuRawIngress` | `CTU` / `OOOD1D2Stage` / canonical public interfaces; `CTUOOOIntegrationSpec`, `OOODecodeSpec`, `OOOIntegrationSpec` |
| `OooFrontendRecoveryBridge` | `RecoveryControl` plus typed `RecoveryTargetIO`; `OOORecoverySpec` and public graph elaboration |

The manifest deletes the displaced public wrappers and their wrapper-specific
tests. Still-reused mechanism coverage is retained under the canonical tests
listed above; no legacy public owner is kept solely for test compatibility.

## Production Inventory

- The production-owner manifest now records public `OOO` as the active caller
  for canonical rename, ROB, BROB, commit, and recovery owners, and records
  `Dispatch` as the caller for the dispatch-reservation mechanisms.
- The exact managed-boundary inventory removes the deleted CTU ingress and
  frontend recovery adapters. Checker fixtures were updated to use a still
  planned-active deletion target rather than the completed dispatch cutover.
- `OooFrontendRecoveryContract.scala` retains only the stateless recovery-plan
  projection helper still used by the later composition graph; it is not an
  owner or payload adapter.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec` - PASS, 5 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OooHierarchicalFreeSlotSelectSpec` - PASS, 3 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec` - PASS, 4 tests including adjacent CTU integration and W2/W4/W6/W8 public graph elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 43 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 21 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `python3 tools/chisel/check_production_owner_manifest.py` - PASS, 23 closed owners, 40 classified emitters, 10 declared adapters, NDF roles mapped.
- `python3 -m unittest tests.test_production_owner_manifest` - PASS, 40 tests.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=116 l1_must=53 verified=61 open_questions=0 references=2`.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, manifest up to date.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS, Verilator 5.044, 67 modules, zero errors.
- `git diff --check` - PASS.

## Self-Review

- The public graph contains one instance of each canonical state owner and no
  O3 rename coordinator.
- The dispatch allocator owns only one retained published packet, suffix
  cursor, transaction sequence, and local recovery acknowledgement state; it
  does not duplicate architectural or residency ownership.
- The free-slot selector is reachable from canonical production dispatch and
  has explicit W6 coverage.
- Deleted wrapper tests were checked against canonical replacement suites;
  coverage tied only to displaced payloads or duplicate owners was removed.
- No generated interface drift, temporary adapter, debug output, new
  dependency, or unrelated source change remains.
- `skill-evolve: no-update` - the canonical ownership and verification rules
  are already captured by the LinxCore skill and Task-11 NDF clauses.
