# Task 9 Report: Canonical ROB/BROB Commit and Recovery Owners

## Result

Task 9 added canonical `CoreParams`-based ROB, BROB, commit-control, and recovery-control owners without instantiating the legacy `Ooo*`, `rob`, `bctrl`, or recovery wrappers. Public OOORob/recovery interfaces now expose the compact exact-suffix recovery contract, commit/release payloads, and probe helpers used by downstream owners and tests.

Final commit SHA: reported by `git rev-parse HEAD` after commit creation; the hash cannot be embedded self-referentially inside the same commit object.

## Changed Files

- `chisel/src/main/scala/linxcore/top/interface/OOORob.scala` - public ROB/BROB/commit/recovery payloads and `RecoveryPlanContract`.
- `chisel/src/main/scala/linxcore/ooo/ROB.scala` - canonical ROB owner with D3 prepare, semantic completion reporting, commit preview, release, and exact recovery apply.
- `chisel/src/main/scala/linxcore/ooo/BROB.scala` - canonical per-STID branch ROB owner with generation-checked release.
- `chisel/src/main/scala/linxcore/ooo/CommitControl.scala` - commit-control owner retaining releases until ROB, rename, and BROB acknowledgements complete.
- `chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala` - recovery-control owner with three-target identical-prepare barrier and abort handling.
- `chisel/src/main/scala/linxcore/top/interface/Identity.scala` - made `InterfaceWidth` available to public interface and OOO modules.
- `chisel/src/main/scala/linxcore/top/interface/Recovery.scala` - added compact exact-suffix fields to `RecoveryPlan`.
- `chisel/src/main/scala/linxcore/ooo/RENU.scala` - switched recovery equality to the shared contract helper so added fields cannot be ignored.
- `chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala` - RED-first commit/BROB/commit-control coverage.
- `chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala` - RED-first ROB recovery/recovery-control contract coverage.
- `docs/chisel/generated/top-interface-manifest.json` and `.md` - regenerated public interface manifest.
- `docs/spec/20-behavior/ooo.md`, `docs/spec/30-interfaces/commit.md`, `docs/spec/30-interfaces/recovery.md`, `docs/spec/50-verification/contract-spine.md` - Task 9 clauses and verification notes.
- `docs/superpowers/plans/2026-07-31-core-mainline-restructure.md` - Task 9 progress updated through implementation and verification.

## RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` failed at compile because canonical `ROB`, `BROB`, `CommitControl`, and `RecoveryPlanContractProbe` did not exist.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` failed at compile because canonical `ROB`, `RecoveryControl`, and `RecoveryPlanContractProbe` did not exist.

## Design Decisions

- Completion handshakes are always consumed at ROB input; semantic acceptance or rejection is reported independently with `completionAccepted` and `completionRejected`.
- Branch recovery preserves the trigger and kills only younger members. Memory-order recovery kills the trigger and younger suffix.
- `RecoveryPlanContract.suffixMember` compares exact STID, slot, generation, and member ordinal; phase is the only ignored field in same-transaction recovery comparison.
- CommitControl holds one release transaction until ROB release, rename release, and BROB release acknowledgements all complete; trap release takes priority over interrupt release.
- The public interface manifest is generated from the new canonical public OOORob interfaces instead of hand-edited.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 5 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - PASS, 2 tests after regenerating the manifest.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, manifest up to date.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.
- `git diff --check` - PASS.

## Notes

- Work-order wrappers `tools/chisel/check_interface_manifest.sh --update` and `tools/ndf/check_ndf.sh` were absent in this checkout. The equivalent available gates were `python3 tools/chisel/render_top_interface_manifest.py --check` and `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec`.
- A generated side-effect diff in `src/common/opcode_meta_gen.py` was restored before staging because it was unrelated to Task 9.

## Fix Round 1

Review verdict `SPEC FAIL / CHANGES_REQUESTED` identified six HIGH findings plus controller blockers. This round added RED tests for the missing protocol surfaces and repaired the canonical Task-9 owners without wrapping legacy owners.

### Fix-Round RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` failed at compile after RED tests because `D3RenameLane.blockStart/blockStop`, `CommitControlIO.robReleaseReady/renameReleaseReady/brobReleaseReady/interruptBoundaryValid`, `ROBIO.brobPrepared/releaseReady/releaseApply/ridHeadSlot`, and `BROBIO.releaseReady/recoveryPrepare.ready` did not exist.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` failed at compile after RED tests because `ROBIO.releaseApply/brobPrepared`, `RecoveryControlIO.robPrepared/interruptBoundaryValid/interruptBoundary`, and `RecoveryControl.robPrepare.valid` request semantics did not exist.

### Fix-Round Repairs

- Commit fire is now gated by side-effect-free ROB, RENU, and BROB release readiness; `CommitControl` retains the candidate and suppresses repeated fire while a `Valid` ROB preview remains asserted after the common fire.
- RENU exposes exact release readiness and mutates only when the common `releaseApply` input is asserted. Existing standalone RENU specs now drive `releaseApply=true`.
- ROB prepare validates continuous D3 shape, ROB-owned RID tail/generation, same-STID lanes, group/member order, free resident rows, and BROB prepared bindings. ROB release computes side-effect-free readiness for the whole prefix and mutates only on `releaseApply`.
- BROB uses typed `blockStart` and `blockStop`, records closed-block final ROB member identity, rejects release until the final member is present, and makes recovery prepare non-mutating with matching apply.
- RecoveryControl now retains producer events, arbitrates source 0/source 1/interrupt with trap priority, drives an explicit ROB prepare request, consumes the ROB-prepared plan, tracks per-target prepare-sent and matching-ack masks, ignores mismatched acknowledgements, and emits one common apply or non-mutating abort.
- Public decoded/D3 payloads now carry typed block start/stop facts. DEC maps legacy boundary sidecar start/stop and template `VFORM`/`FINAL` facts into those fields.
- Interface docs and NDF clauses were updated for side-effect-free readiness, typed block facts, exact release prefixes, and retained recovery request/barrier behavior.

### Fix-Round Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 11 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 8 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`, 3 ROBID tests.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - initially FAIL due stale generated manifest after new fields; PASS, 2 tests after `python3 tools/chisel/render_top_interface_manifest.py`.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, `top-interface-manifest: up to date`.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS after the fix-round doc update, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- Affected decode/D1-D3/config checks: `OOODecodeSpec` PASS 8 tests; `OooD1DecodeSpec` PASS 12 tests; `OooD2GroupPlannerSpec` PASS 6 tests; `OooD2StageSpec` PASS 3 tests; `OooD3ReservationAllocatorSpec` PASS 9 tests; `OooD3S1GroupedRobIntegrationSpec` PASS 1 test; `OooParamsSpec` PASS 4 tests; `OooIexPhysicalProfileSpec` PASS 3 tests.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.

### Fix-Round Notes

- `bash tools/chisel/run_chisel_tests.sh --only OooD3S1BrobIntegrationSpec` was run as an exploratory legacy D3/S1/BROB check and failed twice at `commitValid.expect(true.B)`. That spec instantiates legacy `OooBrob` and `OooS1GroupedRob`, not the canonical Task-9 owners. It is recorded here as a legacy non-gate observation and was not repaired in this Task-9 fix scope.
- `src/common/opcode_meta_gen.py` again acquired an unrelated generated side-effect diff during gates; it was restored before staging.

## Fix Round 2

Review round 2 identified six HIGH blockers in the canonical owners. This
round added behavior RED tests against commit
`95126c0e290b04a36dec013dcf3128ff7ad514d6` and repaired only the Task-9
canonical owner path. The legacy `OooD3S1BrobIntegration` path remains outside
this fix scope.

### Fix-Round-2 RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` failed
  behaviorally after the new tests: zero-lane trap/interrupt transactions did
  not assert `out.valid` without ordinary release readiness, and observing a
  ROB preview made `releaseReady` true before any common commit-apply boundary.
- The initial nontrivial-bank RED test used an illegal W4/2 parameter profile
  and failed during `CoreParams` validation; it was corrected to legal W2/2
  and W4/4 profiles while preserving unsupported-geometry coverage.

### Fix-Round-2 Repairs

- `CommitControl` now bypasses ordinary release readiness only for zero-lane
  trap/interrupt transactions, so head traps and interrupt boundaries can fire
  exactly once without fabricated owner releases.
- `ROB` exposes `commitApply` and mutates `Completed -> Retired`,
  `orderCommitHead`, and `orderCommitCount` only on that common apply pulse.
  A preview handshake remains side-effect-free.
- `ROB` storage is physically banked by `robBankCount` with bank/row helpers
  and a divisible geometry guard. The tests cover legal 8/2 and 8/4 profiles
  and an unsupported geometry.
- `ROB` recovery prepare derives killed members and killed groups from the
  ordered live-member suffix, preserves the exact survivor tail, and recovery
  apply invalidates the matching order suffix before repairing tail/count
  state.
- `BROB` recovery apply uses an exact suffix kill mask, subtracts only the
  killed blocks from `used`, preserves older blocks and unrelated STIDs, and
  clears the current block only when that current block is killed.
- `RecoveryControl` no longer lets the `Exception` enum value outrank an older
  event unless the exception event carries a precise trap. Its age key now
  includes STID.

### Fix-Round-2 Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 14 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 12 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`, 3 ROBID tests.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - PASS, 2 tests.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, `top-interface-manifest: up to date`.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- Affected decode/D1-D3/config checks: `OOODecodeSpec` PASS 8 tests; `OooD1DecodeSpec` PASS 12 tests; `OooD2GroupPlannerSpec` PASS 6 tests; `OooD2StageSpec` PASS 3 tests; `OooD3ReservationAllocatorSpec` PASS 9 tests; `OooD3S1GroupedRobIntegrationSpec` PASS 1 test; `OooParamsSpec` PASS 4 tests; `OooIexPhysicalProfileSpec` PASS 3 tests.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.
- `git diff --check` - PASS.

### Fix-Round-2 Notes

- `src/common/opcode_meta_gen.py` acquired the known unrelated generated
  side-effect diff during gates; it was restored before staging.
- No `.ninja_lock` files were left in the worktree.

## Fix Round 3

Review round 3 identified five HIGH blockers and one MEDIUM bank-coverage
blocker in the Task-9 canonical path. This round added behavior RED tests
against `01e8dc4b7c4da8a22df5fc0909c98ee69a2a5356`, then repaired the
canonical owners without touching legacy owners or Task 10.

### Fix-Round-3 RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` failed
  behaviorally after the new tests: CommitControl suppressed a distinct second
  preview while `rob.valid` stayed high, and ROB kept a retained two-entry
  commit preview after recovery killed its suffix.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` failed
  behaviorally after the new tests: BROB allowed release between recovery
  prepare and apply, and RecoveryControl remained stuck on stale source 0
  instead of progressing with an eligible source 1.

### Fix-Round-3 Repairs

- Added public `RecoveryCandidateLookup` and `RecoveryCandidateStatus` Bundles
  under `linxcore.top.interface`.
- ROB now answers candidate status with exact live/non-retired residency,
  rejected state, synchronous head-trap proof, and a monotonically assigned
  global allocation-age token.
- RecoveryControl selects only among matching eligible ROB statuses, discards
  matching rejected statuses, and no longer derives global age from STID/RID.
- ROB recovery apply conservatively clears retained commit previews and repairs
  commit-view count/head state for killed suffix entries.
- BROB recovery prepare retains an exact local action: full-block kill mask,
  killed count, repaired used/tail/current state, and partial-current rewind
  state. Prepare/release are blocked while the retained action is pending, and
  apply uses only the retained action.
- CommitControl duplicate suppression is keyed to the accepted ROB/trap
  identity, allowing a distinct next prefix while `rob.valid` remains asserted.
- Bank coverage now exercises 8/2 and 8/4 same-bank/different-row behavior,
  stale completion rejection, occupied-slot rejection, wrap, and
  resident-generation reuse.

### Fix-Round-3 Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 16 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - PASS, 2 tests.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, `top-interface-manifest: up to date`.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`, 3 ROBID tests.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- Affected decode/D1-D3/config checks: `OOODecodeSpec` PASS 8 tests; `OooD1DecodeSpec` PASS 12 tests; `OooD2GroupPlannerSpec` PASS 6 tests; `OooD2StageSpec` PASS 3 tests; `OooD3ReservationAllocatorSpec` PASS 9 tests; `OooD3S1GroupedRobIntegrationSpec` PASS 1 test; `OooParamsSpec` PASS 4 tests; `OooIexPhysicalProfileSpec` PASS 3 tests.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.
- `git diff --check` - PASS.

### Fix-Round-3 Notes

- The known unrelated `src/common/opcode_meta_gen.py` generated side effect
  was restored before staging.
- No `.ninja_lock` files were left in the worktree.

## Fix Round 4

Review round 4 identified four HIGH blockers and one MEDIUM age-token blocker
in the canonical Task-9 path. This round added RED-first owner tests against
`a0fb4ea9254609fdd1a2364097e54597bd8b716e`, then repaired only canonical
Task-9 owners, public interfaces, tests, and docs.

### Fix-Round-4 RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` failed at
  compile after the RED tests because `ROBIO.recoveryAbort` and
  `BROBIO.recoveryAbort` did not exist. This covered the required ROB/BROB
  abort-terminal contract before production IO was added.
- The same RED test patch added behavior cases for delayed and retained
  RecoveryControl ROB status arbitration, interrupt holdoff behind unresolved
  producers, stale/wrong-phase ROB apply rejection, duplicate apply
  non-mutation, full CommitControl transaction-signature suppression, and an
  unsafe recovery age-token width guard. The abort compile failure was the
  first observed failing gate and masked later behavior tests on the unmodified
  base.
- After the first repair exposed full-signature comparison, the added inactive
  lane regression failed behaviorally in
  `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec`: changing
  only inactive lane payloads with the same active `count=1` produced
  `out.valid=1` at `OOORobCommitSpec.scala:534`, proving inactive lanes were
  not canonicalized.
- The same rerun proved the standalone age guard gap: unsafe
  `RecoveryControl` elaboration did not throw at `OOORobCommitSpec.scala:791`
  when only ROB enforced the token-width invariant.

### Fix-Round-4 Repairs

- Added explicit matching `recoveryAbort: Valid[RecoveryPlan]` inputs to
  canonical ROB and BROB owner IOs. Matching abort clears only the retained
  transaction/action and does not mutate resident tables, previews, counts,
  head/tail, or generation state.
- ROB recovery apply now requires a retained pending transaction, `Apply`
  phase, and exact transaction match; stale, duplicate, mismatched, or
  wrong-phase apply is non-mutating.
- BROB retains the same exact apply gating and now has a matching abort
  terminal for retained local recovery actions.
- RecoveryControl now snapshots the complete producer contender set for an
  arbitration boundary, retains per-source matching eligible/rejected status,
  waits for every active producer to resolve, retains pulsed early statuses,
  and admits interrupts only after unresolved producers are resolved. Fully
  resolved same-cycle contender sets still take the fast path.
- CommitControl duplicate suppression now compares the complete accepted
  canonical `CommitControlTxn` structural signature rather than a single ROB
  identity. Inactive lanes are zeroed before signature comparison, while
  expanded prefixes, changed trap cause/kind, and ordinary/trap transitions
  are accepted exactly once.
- Added public `RecoveryAge` helper/invariant beside the candidate-status
  contract. ROB and standalone RecoveryControl reject configurations whose age
  token space is not more than twice the global live ROB-member window
  (`stidCount * robCapacityPerStid`), and RecoveryControl uses the wrap-safe
  modular older relation.

### Fix-Round-4 Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 21 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 21 tests.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - PASS, 2 tests.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, `top-interface-manifest: up to date`.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`, 3 ROBID tests.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- Affected decode/D1-D3/config checks: `OOODecodeSpec` PASS 8 tests; `OooD1DecodeSpec` PASS 12 tests; `OooD2GroupPlannerSpec` PASS 6 tests; `OooD2StageSpec` PASS 3 tests; `OooD3ReservationAllocatorSpec` PASS 9 tests; `OooD3S1GroupedRobIntegrationSpec` PASS 1 test; `OooParamsSpec` PASS 4 tests; `OooIexPhysicalProfileSpec` PASS 3 tests.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.
- `git diff --check` - PASS.

### Fix-Round-4 Notes

- Work-order wrappers `tools/chisel/check_interface_manifest.sh` and
  `tools/ndf/check_ndf.sh` are absent in this checkout; the equivalent
  available gates were `python3 tools/chisel/render_top_interface_manifest.py
  --check` and `python3 tools/spec/check_ndf_profile.py
  --verify-local-references docs/spec`.
- The known unrelated `src/common/opcode_meta_gen.py` generated side effect
  was restored before staging.
- No `.ninja_lock` files were left in the worktree.
- `skill-evolve: no-update` - round 4 did not add a reusable LinxCore workflow
  invariant beyond the Task-9 spec/docs changes already recorded.

## Fix Round 5

Review round 5 identified two remaining HIGH blockers in the canonical
Task-9 recovery terminal protocol. This round added RED-first tests against
`0ccbd2d5a29444870998ca4754361f634c4c2d74`, then repaired only canonical
Task-9 recovery source, tests, and docs.

### Fix-Round-5 RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` failed at
  compile after the RED tests because `RecoveryControlIO.robAbort` did not
  exist. This proved the missing exact ROB request-abort terminal before
  production IO was added.
- The same RED patch added behavior cases for abort before `robPrepare.fire`,
  abort after `robPrepare.fire` while waiting for `robPrepared`, final target
  acknowledgement with same-cycle abort, abort during visible Apply, and
  simultaneous matching Apply/Abort on ROB and BROB.
- After adding the IO and first production repair, the owner spec exposed
  test/setup and behavior timing gaps: the old retained-abort test had not
  actually reached target-prepare state, the new event helper mismatched ROB
  status `peId`, and the round-5 regressions then passed after the fixtures
  targeted the intended protocol phases.

### Fix-Round-5 Repairs

- `RecoveryControlIO` now exposes `robAbort: Valid[RecoveryPlan]` for the ROB
  request side of the recovery protocol.
- Abort in `RequestRob` before `robPrepare.fire` cancels locally with no ROB
  or target terminal. Abort after `robPrepare.fire` but before
  `robPrepared.fire` moves to a retained wait state, accepts only the exact
  ROB response, and emits the matching ROB abort without broadcasting a
  default or stale target plan.
- Abort after the ROB-authored plan is retained emits the exact retained plan
  to target abort ports and the ROB abort port exactly once. Abort coincident
  with the final target acknowledgement has non-mutating priority over Apply,
  while abort during an already visible Apply is ignored for that committed
  plan.
- ROB and BROB standalone terminal arbitration now gate suffix mutation with
  `!recoveryAbortHit`; simultaneous matching Apply and Abort clears the
  retained transaction through Abort without mutating resident state.
- Recovery interface and OOO behavior docs now describe the request-side ROB
  abort terminal, abort priority at target-prepare completion, visible-Apply
  irrevocability, and fail-closed simultaneous terminal behavior.

### Fix-Round-5 Verification

- `bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec` - PASS, 27 tests.
- `bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec` - PASS, 21 tests.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` - PASS, 9 tests.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` - PASS, 2 tests.
- `python3 tools/chisel/render_top_interface_manifest.py --check` - PASS, `top-interface-manifest: up to date`.
- `python3 tools/spec/check_ndf_profile.py --verify-local-references docs/spec` - PASS, `clauses=113 l1_must=52 verified=59 open_questions=0 references=2`.
- `bash tools/chisel/run_chisel_tests.sh --only RENUSpec` - PASS, 15 tests.
- `bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec` - PASS, 7 tests.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` - PASS, `ROBID semantic check: ok`, 3 ROBID tests.
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh` - PASS, `brob-order-state-probe: PASS`.
- `bash tests/test_rob_bookkeeping.sh` - PASS, `rob bookkeeping test: ok`.
- Affected decode/D1-D3/config checks: `OOODecodeSpec` PASS 8 tests; `OooD1DecodeSpec` PASS 12 tests; `OooD2GroupPlannerSpec` PASS 6 tests; `OooD2StageSpec` PASS 3 tests; `OooD3ReservationAllocatorSpec` PASS 9 tests; `OooD3S1GroupedRobIntegrationSpec` PASS 1 test; `OooParamsSpec` PASS 4 tests; `OooIexPhysicalProfileSpec` PASS 3 tests.
- `bash tools/chisel/build_chisel.sh` - PASS.
- `bash tools/chisel/run_chisel_verilator_lint.sh` - PASS.

### Fix-Round-5 Notes

- `python3 tools/chisel/render_top_interface_manifest.py` was run after adding
  `robAbort`; the emitted top-interface manifest had no diff because
  `RecoveryControlIO` is not part of that manifest surface.
- Work-order wrappers `tools/chisel/check_interface_manifest.sh` and
  `tools/ndf/check_ndf.sh` remain absent in this checkout; equivalent gates
  were run as listed above.
- `skill-evolve: no-update` - round 5 did not add a reusable LinxCore workflow
  invariant beyond the Task-9 recovery contract/docs changes.
