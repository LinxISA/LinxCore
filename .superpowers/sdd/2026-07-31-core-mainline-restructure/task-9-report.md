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
