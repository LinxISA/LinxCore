# Task 6 Report - B-SIDE Prediction and IFU Recovery

## Outcome

Task 6 is complete on branch `codex/chisel-gap-superpowers`.

Prediction now has a public `linxcore.ifu.BSide` boundary that reuses the existing retained predictor/history implementation, so BTB/TAGE/BIM/RAS/loop/history state still has one owner. Recovery now has public `linxcore.ifu.IFURecovery` and `linxcore.ifu.IFUBackendFeedback` boundaries over the canonical redirect and OOO-authored backend feedback mechanisms. `LinxCoreIfu` instantiates the public boundaries directly. Public `IFU` W2/W4/W6/W8 elaboration keeps the fixed-width IFU-to-CTU payload unchanged and does not instantiate a tied-off backend feedback queue without a live TOP/OOO producer.

## Files Changed

- `chisel/src/main/scala/linxcore/ifu/BSide.scala` - added the public B-SIDE prediction owner boundary over `BSidePredictionPipeline`.
- `chisel/src/main/scala/linxcore/ifu/Prediction.scala` - added side-effect-free provider rank helper documenting speculative provider order; backend recovery is intentionally absent.
- `chisel/src/main/scala/linxcore/ifu/IFURecovery.scala` - added public recovery and backend feedback boundaries over the existing redirect arbiter and feedback bridge.
- `chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala` - wired production IFU composition through `linxcore.ifu.BSide` and `linxcore.ifu.IFURecovery`.
- `chisel/src/main/scala/linxcore/ifu/ISide.scala` - factored canonical `InterfaceParams` construction into `ISide.interfaceParams`.
- `chisel/src/main/scala/linxcore/frontend/ISideF0PcSelect.scala` - replaced Bundle-wide `Mux1H` over prediction contexts with field-safe selected record wiring to remove retained enum-cast warnings.
- `chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala` - added B-SIDE provider, stale training, and public-boundary elaboration coverage.
- `chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala` - added backend priority, redirect hold, feedback-port, and atomic feedback/recovery coverage.
- `chisel/src/test/scala/linxcore/ifu/IFUCTUIntegrationSpec.scala` - added public IFU integration coverage for CTU backpressure, scoped recovery, and W2/W4/W6/W8 elaboration.
- `docs/spec/20-behavior/ifu.md` - added `IFU-008` and `MEC-IFU-006`.
- `docs/spec/50-verification/ifu-conformance.md` - added `VER-IFU-005`.
- `docs/chisel/modules/frontend/LinxCoreIfu.md` - documented public B-SIDE/recovery ownership and Loop 6 verification.
- `docs/chisel/mainline-loop-ledger.md` - recorded Loop 6 scope, identity baselines, evidence, remaining gap, and skill-evolve decision.

## RED Evidence

- `bash tools/chisel/run_chisel_tests.sh --only IFUPredictionSpec` failed before implementation with missing public boundary symbols: `BSide`, `IFURecovery`, and `IFUBackendFeedback`.
- `bash tools/chisel/run_chisel_tests.sh --only IFURecoverySpec` failed before implementation with the same missing boundary symbols.

## GREEN Evidence

- `bash tools/chisel/run_chisel_tests.sh --only IFUPredictionSpec` -> exit 0; 3 tests passed:
  - B-SIDE provider order prefers B-F4 final prediction over lower speculative providers.
  - Stale training is rejected without mutating provider history.
  - Public `linxcore.ifu.BSide` elaborates as `module BSide`.
- `bash tools/chisel/run_chisel_tests.sh --only IFURecoverySpec` -> exit 0; 4 tests passed:
  - Backend typed recovery overrides held prediction correction.
  - Redirect priority and retained hold preserve backpressure.
  - Backend feedback accepts OOO-authored validation and emits no IEX control port.
  - Mispredict training and backend recovery remain atomic at the feedback boundary.
- `bash tools/chisel/run_chisel_tests.sh --only IFUCTUIntegrationSpec` -> exit 0; 2 tests passed:
  - Public IFU retains IFU-to-CTU backpressure while exposing one B-SIDE and one recovery authority.
  - W2/W4/W6/W8 IFU elaboration exposes `module IFU`, `module BSide`, and `module IFURecovery`.

## Adjacent Verification

- `bash tools/chisel/run_chisel_tests.sh --only IFUISideSpec` -> exit 0; 4 tests passed after the PC-select enum-cast warning fix.
- `bash tools/chisel/run_chisel_tests.sh --only CTUSpec` -> exit 0; 11 tests passed.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles` -> exit 0; 9 tests passed.
- `bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec` -> exit 0; 8 tests passed.
- `bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec` -> exit 0; 2 tests passed.
- `python3 tools/chisel/render_top_interface_manifest.py --check` -> exit 0; `top-interface-manifest: up to date`.
- `python3 -m unittest tests.test_ndf_profile -v` -> exit 0; 10 tests passed.
- `python3 tools/spec/check_ndf_profile.py docs/spec` -> exit 0; `clauses=89 l1_must=38 verified=41 open_questions=0 references=2`.
- `bash tools/chisel/build_chisel.sh` -> exit 0; Chisel build completed successfully.
- `bash tools/chisel/run_chisel_verilator_lint.sh` -> exit 0; Verilator 5.044 lint completed successfully.
- `git diff --check` -> exit 0; no whitespace errors.

## Self-Review

- The public B-SIDE class is a wrapper, not a new predictor body. This preserves the single owner for predictor and history state.
- Recovery authority remains singular through the canonical redirect arbiter. Backend recovery is not modeled as a prediction provider and takes priority through the backend redirect path.
- Public IFU still exposes typed recovery but does not allocate an unused backend feedback queue until TOP/OOO supplies a real validation producer.
- The IFU-to-CTU interface manifest remains up to date; Bundle shape did not change.
- The retained enum-cast warnings observed in the first post-change `IFUISideSpec` run were removed by replacing Bundle-wide enum muxing in `ISideF0PcSelect`.

## Remaining Risks / Deferred Work

- Live TOP/OOO backend-validation wiring is deferred to the later TOP/OOO integration packet; `IFUBackendFeedback` is available as the public boundary but is not tied off below public IFU.
- Natural ELF/commit evidence remains in Tasks 18-19.
- `IFUCTUIntegrationSpec` and `IFUISideSpec` are slow wrappers but completed green; no lingering SBT/Java/Verilator processes remained after verification.

## Skill-Evolve Decision

No skill update is required. The reusable invariants exercised here are already covered by the LinxCore workflow: exact identity matching, single state owner, retained ready/valid behavior, and OOO-owned recovery authority.

## Review Fixes

Round 1 Important findings are fixed in the follow-up coverage commit.

- `IFUPredictionSpec` now keeps the original provider-order requirement but proves more than the prior B-F4-only body:
  - Public `Prediction.providerRank` is asserted for Sequential=0, B-F0=1, B-F1=2, B-F2=3, B-F3=4, and B-F4=5.
  - Sequential behavior is driven with an explicit no-boundary B-F4 completion.
  - B-F0, B-F1, and B-F2 are isolated with trained-table and table-conflict cases.
  - B-F4 final LongTAGE-over-static behavior remains covered.
- `IFUPredictionSpec` now adds public B-SIDE checkpoint/state coverage:
  - Exact keyed canonical prune restores older GHR state and kills younger history.
  - RAS state remains unchanged until canonical prune, then feeds a later B-F4 return prediction.
  - BTB/TAGE/BIM/loop state is trained only through exact public B-SIDE resolution, and an inexact follow-up resolve is rejected as stale without changing the later loop prediction.
- `IFUCTUIntegrationSpec` now runs the traffic, retained backpressure, scoped recovery fence/apply, and fixed 64-bit instruction payload checks across W2, W4, W6, and W8.
- Minor no-IEX issue: `IFURecoverySpec` now checks the public `IFUBackendFeedback` IO element names structurally (`validation`, `resolve`, `backendRecovery`, and no IO name containing `iex`) before keeping the emitted-SystemVerilog substring sanity check as secondary evidence.

Review-fix RED evidence:

- `bash tools/chisel/run_chisel_tests.sh --only IFUPredictionSpec` -> exit 1 on the first review-fix run. The new loop/BTB/TAGE exact-training test failed at `IFUPredictionSpec.scala:475` because stale training was injected while a final correction was still pending; observed `staleTraining=0`, expected `1`. The test was corrected to apply the canonical prune before injecting the inexact resolve.

Review-fix GREEN evidence:

- `bash tools/chisel/run_chisel_tests.sh --only IFUPredictionSpec` -> exit 0; 6 tests passed:
  - Provider rank/order, stale training, checkpoint-owned GHR recovery, canonical RAS recovery, exact public B-SIDE training, and public B-SIDE elaboration.
- `bash tools/chisel/run_chisel_tests.sh --only IFURecoverySpec` -> exit 0; 4 tests passed:
  - Includes the structural public-IO no-IEX assertion.
- `bash tools/chisel/run_chisel_tests.sh --only IFUCTUIntegrationSpec` -> exit 0; 2 tests passed in 4 minutes 57 seconds:
  - W2/W4/W6/W8 traffic/backpressure/scoped-recovery behavior and W2/W4/W6/W8 elaboration both passed.
- `bash tools/chisel/run_chisel_tests.sh --only IFUISideSpec` -> exit 0; 4 tests passed.
- `bash tools/chisel/run_chisel_tests.sh --only CTUSpec` -> exit 0; 11 tests passed.
- `bash tools/chisel/build_chisel.sh` -> exit 0; Chisel build completed successfully.
- `git diff --check` -> exit 0; no whitespace errors.

No production code changed in the review-fix round. The remaining TOP/OOO backend-validation wiring gap is unchanged and remains deferred to the later integration packet.
