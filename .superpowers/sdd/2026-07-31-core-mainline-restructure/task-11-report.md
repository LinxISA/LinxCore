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

## Fix Round 1

This round supersedes the original dispatch-handshake, production-evidence,
and test-count statements above where they conflict.

### RED evidence

- New valid-independent dispatch checks failed because ALU/store `valid` was
  suppressed while `ready` was low, and the retained packet was not observable
  to a wait-for-valid consumer.
- A focused allocator check failed because an accepted `io.in.fire` could be
  discarded when the separate `publishFire` input was false.
- The four-STID integration test initially failed to compile because no
  canonical D3-through-S1 production graph existed as a reusable test target;
  subsequent runs exposed and removed a RENU ready/recovery combinational cycle
  and corrected the external recovery-target test handshake.
- Two new manifest-checker tests failed because invented declared callers and
  undeclared real main-Scala constructors were both accepted.
- A bounded documentation search found the deleted
  `OooO3RenameCoordinator` and `OooO3IexStorePipeline` still described as live
  production owners with commands for deleted suites.

### Corrections

- Every dispatch output now asserts `valid` from retained state independently
  of its own `ready` and keeps payload stable until acceptance. Stores use one
  paired `StoreDispatchTxn` acceptance carrying identical STA/STD transactions
  and selected AGU/STD pipe indices, so no partial store side effect is visible
  at the OOO/IEX boundary.
- `OooD3ReservationAllocator` retains exactly on `io.in.fire`; the redundant
  `publishFire` qualifier was removed. The canonical graph exposes Dispatch
  input only after side-effect-free ROB/BROB prepare readiness, so that one
  Decoupled fire is also the common D3 publication fire.
- `OOOD3S1Graph` is the exact production child instantiated by public `OOO` and
  by the bounded behavioral integration test. The test publishes two
  operations on each of four STIDs across P/T/U/zero destinations, checks
  owner snapshots, applies Branch survivor and MemoryOrder trigger-kill
  recovery, and proves unrelated-STID isolation. Public `OOO` elaboration at
  W2/W4/W6/W8 proves every canonical owner is reachable and displaced owners
  are absent.
- The production-owner checker now discovers the exact main-Scala constructor
  callers for each canonical owner, including companion class/object
  definitions. Test fixtures remain only under `verification_fixtures`.
  Standalone rename evidence is reported as `standalone-verified`.
- The displaced wrapper pages and module-index row were deleted or rewritten;
  Task-12 handoff now points at the surviving execution/store mechanisms until
  public `IEX` exists.

### Verification

- `LINX_CHISEL_SBT_MEM_MB=4096 ... --only OOOIntegrationSpec` - PASS, 5 total:
  `OOOIntegrationSpec` 2 and separately selected adjacent
  `CTUOOOIntegrationSpec` 3.
- `... --only OOODispatchSpec` - PASS, 7 tests.
- `... --only OOORecoverySpec` - PASS, 43 tests.
- `... --only OOORobCommitSpec` - PASS, 21 tests.
- `... --only RENUAtomicSpec` - PASS, 7 tests.
- `... --only RENUSpec` - PASS, 15 tests.
- `... --only OooHierarchicalFreeSlotSelectSpec` - PASS, 3 tests.
- `... --only TopInterfaceSpec` - PASS, 9 tests.
- `... --only InterfaceManifestSpec` - PASS, 2 tests.
- `python3 -m unittest tests.test_production_owner_manifest` - PASS, 42 tests.
- `python3 tools/chisel/check_production_owner_manifest.py` - PASS, 23 closed
  owners, 40 classified emitters, 10 declared adapters.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec`
  - PASS, `clauses=116 l1_must=53 verified=61 open_questions=0 references=2`.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS, Verilator 5.044,
  67 modules, zero errors.
- `git diff --check` - PASS.

## Fix Round 2

This round supersedes the original documentation-reference and commit-control
evidence statements above where they conflict.

### RED evidence

- `python3 tools/chisel/check_deleted_ooo_doc_references.py` initially found 23
  live documentation lines that still named deleted
  `OooO3RenameCoordinator` or `OooO3IexStorePipeline` wrappers.
- A new manifest unit test failed because the checker accepted an invented
  behavioral evidence case that did not exist in its declared fixture.
- The new canonical completion-to-commit integration case first exposed a
  one-uop fixture bug: the generalized publisher still advertised a two-row
  packet. After that was corrected, exact completion was accepted and ROB
  produced a commit preview, but public commit remained blocked because ROB
  release preflight required the row to be retired before the common commit
  fire that performs retirement.
- An earlier oversized W2 simulation was terminated as a resource-only run and
  is excluded from behavioral evidence; every result below is from a fresh
  bounded run.

### Corrections

- All requested live documentation now routes through public `OOO`,
  `OOOD3S1Graph`, `RecoveryControl`, RENU/Dispatch, or the surviving
  `OooIexExecutionStorePipeline`. Historical `agent-loop.md` records remain
  unchanged. The persistent documentation gate rejects future live references
  to either deleted wrapper while allowing explicitly historical records.
- Production evidence may name an exact ScalaTest case. The owner checker now
  verifies that the declared fixture contains the exact `test("...")`, and
  CommitControl L3 evidence names the canonical behavioral integration case.
- `OOOIntegrationSpec` publishes one ordinary GPR-destination ALU uop through
  `OOOD3S1Graph`, supplies its exact dispatched ROB identity on the public
  completion port, proves the commit is stable under backpressure, accepts one
  ordered commit, and proves no duplicate appears.
- ROB release preflight now validates the exact live completed head instead of
  requiring a retirement that cannot occur until the same common fire.
  `OOOD3S1Graph` asserts that every non-empty commit fire has ROB, rename, and
  BROB release readiness, and the ROB unit test proves preflight is
  non-mutating before simultaneous commit/release apply.

### Verification

- `LINX_CHISEL_SBT_MEM_MB=4096 ... --only OOOIntegrationSpec` - PASS, 6 tests.
- `LINX_CHISEL_SBT_MEM_MB=4096 ... --only OOORobCommitSpec` - PASS, 21 tests.
- `LINX_CHISEL_SBT_MEM_MB=4096 ... --only OOORecoverySpec` - PASS, 43 tests.
- `python3 -m unittest tests.test_production_owner_manifest` - PASS, 43 tests.
- `python3 tools/chisel/check_production_owner_manifest.py` - PASS, 23 closed
  owners, 40 classified emitters, 10 declared adapters.
- `python3 tools/chisel/check_deleted_ooo_doc_references.py` - PASS, no live
  deleted-wrapper references.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec`
  - PASS, `clauses=116 l1_must=53 verified=61 open_questions=0 references=2`.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS, Verilator 5.044,
  67 modules, zero errors.
- `git diff --check` - PASS.

## Fix Round 3

This round supersedes the Fix Round 2 documentation-inventory and four-STID
debug-observation statements where they conflict.

### RED evidence

- New documentation-checker fixtures containing every owner and test suite
  deleted by `5f80ea0b..1375436f` initially passed because the checker knew
  only two wrappers. An arbitrary line containing the word `historical` also
  bypassed the old marker-based exemption.
- Exact-location fixtures initially showed that the checker had no
  `--doc-root` seam and no path-plus-heading allowlist. A surviving prefixed
  type fixture then exposed false positives from substring matching.
- Removing `debugPMap`, `debugTCount`, `debugUCount`, `debugBrobUsed`, and
  `debugDispatchPending` from `OOOD3S1GraphIO` produced the expected compile
  failure in the old four-STID test at every debug observation.
- The first public-only rewrite passed five of six integration tests and
  localized its remaining failures to over-demanded post-recovery and
  multi-entry retirement observations. The final scenario uses only public
  protocol outcomes required by this integration boundary.

### Corrections

- The documentation gate now carries the exact 12-owner and 23-suite deleted
  Task 11 inventory. Exact Scala identifiers are rejected in live prose;
  stripped suite names are rejected only in `--only` runner commands, so live
  owners and prefixed bundle types remain legal.
- Historical exemptions are limited to `agent-loop.md` under
  `## Suggested Next Packets` and `mainline-loop-ledger.md` under the exact
  Loop 9 heading. Tests prove that the same headings at another path, or other
  headings at those paths, do not bypass the gate.
- Live documentation now names the canonical `OOO`/`OOOD3S1Graph`, `ROB`,
  `BROB`, `RENU`/`PRename`/`TURename`, `CommitControl`, `RecoveryControl`, CTU,
  and current replacement suites. Five displaced wrapper pages were renamed
  to their canonical owner names.
- `OOOD3S1Graph` no longer exports or wires any of the five test-only debug
  signals. The four-STID integration captures public dispatch identities and
  P/T/U tags, proves valid/payload stability under stall, completes and commits
  exact peer/survivor identities, checks killed completions produce no commit,
  verifies peer tail isolation, and uses post-recovery dispatch tag reuse to
  prove target rollback.

### Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec` - PASS,
  6 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS,
  21 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS,
  43 tests.
- `python3 -m unittest tests.test_production_owner_manifest` - PASS, 43 tests.
- `python3 tools/chisel/check_production_owner_manifest.py` - PASS, 23 closed
  owners, 40 classified emitters, 10 declared adapters.
- `python3 -m unittest tests.test_deleted_ooo_doc_references` - PASS, 7 tests.
- `python3 tools/chisel/check_deleted_ooo_doc_references.py` - PASS, no live
  deleted Task 11 references.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec`
  - PASS, `clauses=116 l1_must=53 verified=61 open_questions=0 references=2`.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS, Verilator 5.044,
  67 modules, zero errors.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS,
  manifest up to date.
- `git diff --check` - PASS.
