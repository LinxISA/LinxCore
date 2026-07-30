# Scalar Load Structural-Block Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert STQ forwarding structural hard blocks into a retained, exact-identity retry/unsupported lifecycle owned atomically by OOO metadata and the canonical LIQ.

**Architecture:** A focused LSU policy module consumes the private raw forwarding response and retains only compact identity/reason state. Retryable outcomes arbitrate into the existing canonical OOO rebind owner and use a new exact LIQ structural-retry mutation; unsupported outcomes remain fail-closed diagnostics. Typed recovery can clear a resident policy record only when both OOO and LSU projections kill the same owning LIQ row.

**Tech Stack:** Scala 2.13, Chisel 6, ScalaTest/ChiselSim, SBT repository wrappers, generated SystemVerilog structure checks.

## Global Constraints

- Do not add dependencies.
- Do not create a second LIQ, STQ, load-terminal metadata table, or attempt owner.
- Do not reinterpret structural uncertainty as a cache miss or architectural trap.
- Preserve full canonical load ID, attempt producer identity, generation, return pipe, and full LSID wait-store identity.
- Keep recovery prepare side-effect free; mutate state only on the common recovery fire.
- Use `bash tools/chisel/run_chisel_tests.sh --only <selector>` for SBT tests and never run SBT processes in parallel.
- Use Lore-format commit messages and keep the LinxISA superproject gitlink update separate from the LinxCore feature commit.

---

## File Structure

- `chisel/src/main/scala/linxcore/lsu/LoadStructuralBlockPolicy.scala`: compact resident policy record, reason encoding, classification, retry generation, backpressure, hard-flush, and recovery-clear behavior.
- `chisel/src/test/scala/linxcore/lsu/LoadStructuralBlockPolicySpec.scala`: direct behavioral tests of classification and retention.
- `chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`: exact structural-retry request type, validation, conflict arbitration, and LIQ mutation.
- `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`: thread the structural-retry port to the canonical LIQ without changing the private forwarding port.
- `chisel/src/test/scala/linxcore/lsu/LoadStructuralRetrySpec.scala`: canonical LIQ mutation and rejection tests.
- `chisel/src/main/scala/linxcore/ooo/OooIexScalarLoadStorePath.scala`: install the policy, arbitrate internal/external rebind, join metadata and LIQ acceptance, expose diagnostics, and integrate exact recovery.
- `chisel/src/test/scala/linxcore/ooo/OooIexScalarLoadStorePathSpec.scala`: production-path integration and generated-RTL boundary tests.
- `docs/chisel/modules/execute/OooIexScalarLoadStorePath.md`: update the ownership contract and remaining gap.
- `docs/chisel/integrated-development-flow.md`, `docs/chisel/development-loop.md`, `docs/chisel/agent-loop.md`: record the feature evidence and next handoff.

---

### Task 1: Retained structural-block classifier

**Files:**

- Create: `chisel/src/test/scala/linxcore/lsu/LoadStructuralBlockPolicySpec.scala`
- Create: `chisel/src/main/scala/linxcore/lsu/LoadStructuralBlockPolicy.scala`

**Interfaces:**

- Consumes: `Flipped(Decoupled(new STQLoadForwardResponse(...)))`, `hardFlush: Bool`, `recoveryKill: Bool`, and `recoveryFire: Bool`.
- Produces: `retry: Decoupled[LoadStructuralBlockRetry]`, `pending`, `unsupported`, `disposition`, `reason`, `loadId`, `attempt`, `recoveryReady`, `empty`, and `protocolError`.
- `LoadStructuralBlockRetry` contains `loadId: LoadCanonicalRowIdentity`, `current: LoadAttemptIdentity`, `next: LoadAttemptIdentity`, `returnPipeIndex`, `waitStore`, and `waitStoreInfo`.

- [ ] **Step 1: Write the failing policy tests**

  Instantiate the real policy and drive literal response fields.  Test these mutations:

  ```scala
  test("unknown older store retains an exact wait retry under backpressure") {
    // Drive load slot 3, generation 9, pipe 2, exact store slot 5/full LSID 0x123.
    // Hold retry.ready low for three cycles and expect every output bit stable.
    // Then accept and expect next.generation == 10 and pending == false.
  }

  test("stale snapshot outranks unknown wait-store") {
    // Drive both masks and expect RetrySnapshot with waitStore == false.
  }

  test("missing ordering authority remains fail closed until hard flush") {
    // Drive fullLsIdMissingMask != 0; expect unsupported and protocolError,
    // no retry.valid, stable residency, then hardFlush clears it.
  }

  test("only an exact typed recovery kill can clear a resident record") {
    // recoveryFire without recoveryKill retains; recoveryKill + recoveryFire clears.
  }
  ```

- [ ] **Step 2: Run the focused selector and observe RED**

  Run: `bash tools/chisel/run_chisel_tests.sh --only LoadStructuralBlockPolicy`

  Expected: compilation fails because `LoadStructuralBlockPolicy` and its retry/disposition types do not exist.

- [ ] **Step 3: Implement the minimal retained policy**

  Define explicit priority and a single resident register:

  ```scala
  object LoadStructuralBlockDisposition extends ChiselEnum {
    val WaitStore, RetrySnapshot, Unsupported = Value
  }

  val unsupportedNow = missingOrAmbiguous || invalidIdentity ||
    crossLine || malformedUnknownWait
  val dispositionNow = Mux(unsupportedNow,
    LoadStructuralBlockDisposition.Unsupported,
    Mux(staleSnapshot,
      LoadStructuralBlockDisposition.RetrySnapshot,
      LoadStructuralBlockDisposition.WaitStore))

  io.in.ready := !residentValid && !io.hardFlush
  io.retry.valid := residentValid && disposition =/=
    LoadStructuralBlockDisposition.Unsupported && !io.recoveryFire
  when(io.in.fire) { /* capture compact exact state */ }
  when(io.retry.fire || io.hardFlush || (io.recoveryFire && io.recoveryKill)) {
    residentValid := false.B
  }
  ```

  Add assertions that a retry is well formed, increments generation by one,
  and a resident unsupported record cannot dequeue through `retry`.

- [ ] **Step 4: Run the policy selector and observe GREEN**

  Run: `bash tools/chisel/run_chisel_tests.sh --only LoadStructuralBlockPolicy`

  Expected: all policy tests pass, including three-cycle stability and survivor recovery retention.

- [ ] **Step 5: Commit the classifier**

  Stage only the two policy files and commit with a Lore message recording the classification priority, generated tests, and unsupported cross-line gap.

---

### Task 2: Exact canonical LIQ structural retry

**Files:**

- Create: `chisel/src/test/scala/linxcore/lsu/LoadStructuralRetrySpec.scala`
- Modify: `chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
- Modify: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Modify tied-off direct LIQ users found by: `rg -l "attemptRebindValid := false" chisel/src/main chisel/src/test`

**Interfaces:**

- Consumes: `structuralRetryValid` plus the `LoadStructuralBlockRetry` defined in Task 1.
- Produces: `structuralRetryReady`, `structuralRetryAccepted`, and rejection diagnostics for load ID, attempt, pipe, lifecycle/wait-store shape, and mutation conflict.
- Leaves ordinary `LoadAttemptRebind` semantics unchanged for `Wait`, `L1DcMiss`, and `L2Wait` rows.

- [ ] **Step 1: Write failing LIQ mutation tests**

  Use a real `LoadInflightQueue`: allocate, launch into `Repick + forwardPending`, then drive structural retry.  Literal expectations:

  ```scala
  dut.io.rows(0).status.expect(LoadInflightStatus.Wait)
  dut.io.rows(0).forwardPending.expect(false.B)
  dut.io.rows(0).attempt.generation.expect(6.U)
  dut.io.rows(0).waitStore.expect(true.B)
  dut.io.rows(0).waitStoreInfo.storeIndex.expect(3.U)
  dut.io.rows(0).waitStoreInfo.storeLsIdFull.expect("h123".U)
  ```

  Add negative cases for stale current attempt, wrong return pipe, missing full
  LSID on `WaitStore`, and a same-row row-mutation conflict; each must leave the
  row bit-for-bit unchanged.

- [ ] **Step 2: Run the focused selector and observe RED**

  Run: `bash tools/chisel/run_chisel_tests.sh --only LoadStructuralRetry`

  Expected: compilation fails because the LIQ structural-retry IO is absent.

- [ ] **Step 3: Add exact validation and atomic mutation**

  Accept only when all exact checks pass:

  ```scala
  val lifecycleExact = row.valid &&
    row.status === LoadInflightStatus.Repick && row.forwardPending
  val identityExact = LoadCanonicalRowIdentity.equal(req.loadId,
    LoadCanonicalRowIdentity.fromRobId(row.loadId)) &&
    LoadAttemptIdentity.equal(req.current, row.attempt)
  val nextExact = req.next.valid &&
    req.next.producer.asUInt === req.current.producer.asUInt &&
    req.next.generation === req.current.generation +% 1.U
  val waitShapeExact = !req.waitStore ||
    (req.waitStoreInfo.valid && req.waitStoreInfo.storeLsIdFullValid)
  ```

  On the accepted edge, write `next` attempt, set status to `Wait`, clear
  `forwardPending`, clear partial line/return/miss state, and install or clear
  `waitStoreInfo` according to the request.  Include structural retry in the
  same-row mutation-conflict mask.  Thread the port through `ScalarLSULoadPath`
  and tie it off in direct users.

- [ ] **Step 4: Run LIQ and load-path regression selectors**

  Run sequentially:

  ```bash
  bash tools/chisel/run_chisel_tests.sh --only LoadStructuralRetry
  bash tools/chisel/run_chisel_tests.sh --only LoadAttemptBinding
  bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadForwardIntegration
  ```

  Expected: all pass; ordinary rebind behavior remains unchanged.

- [ ] **Step 5: Commit the canonical LIQ mutation**

  Stage the LIQ, load-path threading, tie-offs, and focused tests.  Commit with
  a Lore message warning that structural retry is legal only from exact
  `Repick + forwardPending` state.

---

### Task 3: Install policy behind the OOO/IEX production boundary

**Files:**

- Modify: `chisel/src/test/scala/linxcore/ooo/OooIexScalarLoadStorePathSpec.scala`
- Modify: `chisel/src/main/scala/linxcore/ooo/OooIexScalarLoadStorePath.scala`

**Interfaces:**

- Consumes: raw private `forwarding.hardBlock`, the existing external rebind candidate, canonical owner `liqRebind`, and typed recovery projections.
- Produces: observation-only structural diagnostics on `OooIexScalarLoadExternalIO`; removes the external raw `hardBlock: Decoupled[STQLoadForwardResponse]` seam.

- [ ] **Step 1: Write failing installed-path tests**

  Change the harness to expose diagnostics instead of `.hardBlock.ready`.
  Drive a real STQ response with an unknown older exact store and hold the
  canonical metadata/LIQ join blocked.  Expect pending identity and generation
  to remain stable.  Release the join and expect exactly one old-generation
  cancel plus LIQ transition to `Wait + waitStore`.  Add an unsupported
  cross-line case that raises protocol error and cannot issue a retry.  Add
  recovery cases where an unrelated/surviving projection blocks prepare and an
  exact dual-projection kill permits prepare then clears only on recovery fire.

- [ ] **Step 2: Run the installed-path selector and observe RED**

  Run: `bash tools/chisel/run_chisel_tests.sh --only OooIexScalarLoadStorePath`

  Expected: compilation or assertion failure because raw `hardBlock` is still external and no policy/rebind join exists.

- [ ] **Step 3: Install one policy and one atomic rebind arbiter**

  Connect `forwarding.hardBlock` only to the policy.  Give a resident internal
  retry priority over external rebind without allowing either producer to fire
  early:

  ```scala
  val useStructural = policy.io.retry.valid
  io.owner.rebind.valid := Mux(useStructural,
    policy.io.retry.valid, io.external.rebind.valid)
  io.owner.rebind.bits := Mux(useStructural,
    structuralMetadataRebind, io.external.rebind.bits)
  io.external.rebind.ready := !useStructural && io.owner.rebind.ready
  policy.io.retry.ready := useStructural && io.owner.rebind.ready &&
    loadPath.io.structuralRetryReady
  ```

  Route canonical `liqRebind` to ordinary LIQ rebind for an external request or
  to structural LIQ retry for an internal request.  Assert that policy dequeue,
  OOO metadata rebind, and LIQ structural mutation fire together.

  Compute the resident policy record's `RobMemberKey`, compare OOO and LSU kill
  projections, and require both before `policy.recoveryReady`.  Exclude the
  policy's compact resident state from generic non-LIQ-empty only through this
  explicit exact-kill exception; continue requiring the raw forwarding fabric
  empty.  Export diagnostics and fold unsupported state into `protocolError`
  and overall `empty`.

- [ ] **Step 4: Run integration and structure gates**

  Run sequentially:

  ```bash
  bash tools/chisel/run_chisel_tests.sh --only OooIexScalarLoadStorePath
  bash tools/chisel/run_chisel_tests.sh --only OooIexCanonicalLoadOwnership
  bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
  bash tools/chisel/run_chisel_tests.sh --only OooO3IexStorePipeline
  bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
  ```

  Inspect generated SystemVerilog through the existing spec and require no
  `external_hardBlock_ready` port while retaining the private forwarding
  hard-block signal and one policy instance.

- [ ] **Step 5: Commit the production installation**

  Stage the OOO/IEX source and integration spec.  Commit with a Lore message
  recording the atomic join, exact recovery rule, and unsupported cross-line
  limitation.

---

### Task 4: Documentation, full gates, push, and superproject pin

**Files:**

- Modify: `docs/chisel/modules/execute/OooIexScalarLoadStorePath.md`
- Modify: `docs/chisel/integrated-development-flow.md`
- Modify: `docs/chisel/development-loop.md`
- Modify: `docs/chisel/agent-loop.md`
- Modify in superproject: `rtl/LinxCore` gitlink only

**Interfaces:**

- Consumes: passing feature commits and gate output from Tasks 1-3.
- Produces: current architecture evidence, explicit remaining gaps, clean pushed LinxCore branch, and updated pushed LinxISA `main` gitlink.

- [ ] **Step 1: Update current documentation evidence**

  Document that structural hard blocks are now retained internally, unknown
  older stores enter exact wait/replay, stale snapshots retry under a new
  generation, unsupported authority/cross-line conditions remain fail-closed,
  and typed recovery clears only exact killed ownership.  List remaining gaps:
  common full BID/BROB authority still replaces migration projections;
  cross-line forwarding/execution remains unsupported; LSU recovery projection
  equality must extend to every queue.

- [ ] **Step 2: Run the complete relevant gate set**

  Run sequentially:

  ```bash
  bash tools/chisel/run_chisel_tests.sh --only LoadStructuralBlockPolicy
  bash tools/chisel/run_chisel_tests.sh --only LoadStructuralRetry
  bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
  bash tools/chisel/run_chisel_tests.sh --only STQLoadForwardResultPipeline
  bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadForwardIntegration
  bash tools/chisel/run_chisel_tests.sh --only OooIexScalarLoadStorePath
  bash tools/chisel/run_chisel_tests.sh --only OooO3IexStorePipeline
  bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline
  bash tools/chisel/build_chisel.sh
  ```

  Expected: every selector and the Chisel build pass with no known errors.

- [ ] **Step 3: Review the diff and repository state**

  Run:

  ```bash
  git diff --check
  git status --short --branch
  git diff HEAD~3 --stat
  ```

  Confirm only intended files changed, generated artifacts are absent, and the
  worktree is clean after commits.

- [ ] **Step 4: Commit documentation and push LinxCore**

  Commit the documentation/ledger update with a Lore message containing the
  exact gates from Step 2, then push `codex/chisel-gap-superpowers` to `origin`.

- [ ] **Step 5: Bump and push the LinxISA superproject**

  In `/Users/zhoubot/linx-isa`, stage only `rtl/LinxCore`, preserving the
  pre-existing dirty `workloads/SuperNPUBench`.  Commit the gitlink with a Lore
  message that records the new LinxCore SHA and tests, push `main`, and verify
  both repositories' final status.
