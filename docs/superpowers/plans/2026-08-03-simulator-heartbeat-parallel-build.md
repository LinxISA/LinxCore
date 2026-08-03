# Simulator Heartbeat and Parallel Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Chisel tests observable and bounded, run independent suites with memory-safe parallelism, and shrink directed-test elaboration by using legal test-only capacity profiles.

**Architecture:** A Python process supervisor wraps the single SBT invocation and reports build liveness without confusing a CPU-active compile with deadlock. Pure Scala test helpers own simulated-cycle progress checks. Test-only parameter profiles preserve the selected W2/W4/W6/W8 lane topology while reducing capacity-derived structures; unchanged main profiles remain the only source of interface, generated-RTL, activation, and closure evidence.

**Tech Stack:** Bash, Python 3 standard library and `unittest`, SBT 2, Scala 2.13, ScalaTest, Chisel 7.3, ChiselSim, Verilator.

## Global Constraints

- Keep main-source `ParamProfiles.W2`, `W4`, `W6`, and `W8` unchanged.
- Preserve fetch, CTU, decode, rename, D3, dispatch, issue, and retire widths at 2/4/6/8 in the corresponding simulation profile.
- Preserve W4 topology: 2 ALU, 1 BRU, 2 AGU, 2 STD, one system/multicycle queue, one CMD queue, 2 load pipes, and 2 store pipes.
- Preserve fixed PC, instruction, transaction, generation, full-LSID, memory-attempt, epoch, and recovery widths.
- Treat capacity-derived RID slot, BID, PC Buffer index, P/T/U tag, and MapQ index widths as local to the selected simulation profile; never use that profile as interface-width or full-identity-space evidence.
- Add no hardware watchdog, debug-only ready signal, TOP port, queue owner, recovery policy, compatibility path, or third-party dependency.
- Keep IEX as owner of memory transaction and initial load-attempt allocation; keep LSU as owner of replay, repick, and rebind.
- Do not start another Chisel, SBT, or Verilator process until the pre-existing W8 process has exited and its generated directory has been released for cleanup.
- Preserve the child command exit status. Supervisor-detected idle stall and wall-limit expiration use exit status 124.
- Use one SBT server per runner invocation. Default suite concurrency is 2.
- Heartbeat default is 30 seconds; idle-stall default is 600 seconds; wall limit is disabled by default; low-activity threshold is aggregate descendant CPU below 1 percent.

---

## File Structure

- `tools/chisel/chisel_test_supervisor.py`: process-group lifecycle, output/artifact/CPU sampling, heartbeat records, stall classification, signal forwarding, and final summary.
- `tools/chisel/run_chisel_tests.sh`: public runner CLI, environment validation, selector construction, and one supervised SBT command.
- `chisel/build.sbt`: environment-derived Test task concurrency limit.
- `tests/test_chisel_test_supervisor.py`: deterministic supervisor and CLI regression tests using synthetic child commands.
- `tests/test_chisel_test_runner.py`: fake-SBT runner contract tests; no Scala compilation.
- `chisel/src/test/scala/linxcore/params/SimulationParamProfiles.scala`: test-scope W2/W4/W6/W8 capacity profiles.
- `chisel/src/test/scala/linxcore/params/SimulationParamProfilesSpec.scala`: profile legality, topology, fixed-width, and main-profile isolation tests.
- `chisel/src/test/scala/linxcore/testutil/SimulationProgress.scala`: simulated-cycle progress accounting and diagnostic failure.
- `chisel/src/test/scala/linxcore/testutil/SimulationProgressSpec.scala`: pure Scala tests for progress reset and exact threshold failure.
- `chisel/src/test/scala/linxcore/ooo/OOOD3ContinuousPrefixSpec.scala`: use the W8 simulation profile while preserving the eight-lane three-three-two behavior.
- `docs/chisel/generated/simulation-artifact-baseline.json`: checked baseline and reduced-run metrics for the W8 case.
- `docs/chisel/integrated-development-flow.md`: runner options, profile evidence boundary, and measured artifact reduction.

### Task 1: Add legal test-only capacity profiles

**Files:**
- Create: `chisel/src/test/scala/linxcore/params/SimulationParamProfiles.scala`
- Create: `chisel/src/test/scala/linxcore/params/SimulationParamProfilesSpec.scala`

**Interfaces:**
- Consumes: `linxcore.params.ParamProfiles`, `CoreParams`, `OOOParams`, `IEXParams`, and `LSUParams`.
- Produces: `SimulationParamProfiles.W2`, `W4`, `W6`, `W8`, and `forWidth(width: Int): CoreParams` for behavior-only simulations.

- [ ] **Step 1: Write the profile invariants before the profile object exists**

Create `SimulationParamProfilesSpec` with these assertions:

```scala
package linxcore.params

import org.scalatest.funsuite.AnyFunSuite

class SimulationParamProfilesSpec extends AnyFunSuite {
  private val widths = Seq(2, 4, 6, 8)

  test("simulation profiles preserve every principal width and fixed identity domain") {
    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val sim = SimulationParamProfiles.forWidth(width)
      ParamChecks.validate(sim)
      assert(sim.widths == WidthParams.uniform(width))
      assert(sim.pcWidth == main.pcWidth)
      assert(sim.instructionWidth == main.instructionWidth)
      assert(sim.instructionIdWidth == main.instructionIdWidth)
      assert(sim.transactionIdWidth == main.transactionIdWidth)
      assert(sim.lsidWidth == main.lsidWidth)
      assert(sim.memoryTransactionIdWidth == main.memoryTransactionIdWidth)
      assert(sim.memoryTransactionGenerationWidth == main.memoryTransactionGenerationWidth)
      assert(sim.memoryAttemptGenerationWidth == main.memoryAttemptGenerationWidth)
      assert(sim.epochWidth == main.epochWidth)
      assert(sim.ridGenerationWidth == main.ridGenerationWidth)
      assert(sim.brobGenerationWidth == main.brobGenerationWidth)
    }
  }

  test("W4 simulation topology remains two one two two and two load two store") {
    val p = SimulationParamProfiles.W4
    assert((p.iex.aluPipes, p.iex.bruPipes, p.iex.aguPipes, p.iex.stdPipes) ==
      (2, 1, 2, 2))
    assert(p.iex.systemMulticycleQueues == 1)
    assert(p.iex.cmdIssueQueues == 1)
    assert((p.lsu.loadPipes, p.lsu.storePipes) == (2, 2))
    assert((p.ooo.pcWritePorts, p.ooo.pcReadPorts) == (3, 6))
  }

  test("W8 directed profile uses the minimum legal retained capacities") {
    val p = SimulationParamProfiles.W8
    assert(p.ooo.robGroupsPerStid == 8)
    assert(p.ooo.maxInstructionsPerRobGroup == 1)
    assert(p.ooo.robBankCount == 8)
    assert(p.ooo.brobEntriesPerStid == 8)
    assert(p.ooo.pcBufferEntries == 8)
    assert(p.ooo.pcBankCount == 8)
    assert(p.ooo.gprPhysRegs == 64)
    assert(p.ooo.gprMapQDepthPerStid == 16)
    assert(p.ooo.tPhysRegs == 16)
    assert(p.ooo.uPhysRegs == 16)
    assert(p.ooo.tuMapQDepthPerStid == 16)
    assert(p.iex.scalarIssueEntries == 16)
    assert((p.lsu.loadQueueEntries, p.lsu.storeQueueEntries) == (4, 4))
    assert(p.lsu.scbEntries == 4)
  }

  test("simulation capacity changes never mutate main profiles") {
    assert(ParamProfiles.W8.ooo.robGroupsPerStid == 64)
    assert(ParamProfiles.W8.ooo.brobEntriesPerStid == 256)
    assert(ParamProfiles.W8.ooo.gprMapQDepthPerStid == 256)
    assert(ParamProfiles.W8.iex.scalarIssueEntries == 64)
    assert(ParamProfiles.W8.lsu.loadQueueEntries == 16)
  }
}
```

- [ ] **Step 2: Run the test and verify the missing object is the first failure**

Run after the pre-existing W8 process exits:

```bash
bash tools/chisel/run_chisel_tests.sh --only SimulationParamProfilesSpec
```

Expected: compile failure naming `SimulationParamProfiles` as missing.

- [ ] **Step 3: Implement one uniform test-scope profile constructor**

Create `SimulationParamProfiles.scala` with a private constructor that starts from the unchanged main profile and copies only capacities:

```scala
package linxcore.params

object SimulationParamProfiles {
  private def reduced(width: Int): CoreParams = {
    val main = ParamProfiles.forWidth(width)
    val bankCount = if (width <= 4) 4 else 8
    main.copy(
      ifu = main.ifu.copy(
        fetchBufferEntries = math.max(8, width),
        predictionCheckpointEntries = 8),
      ctu = main.ctu.copy(
        instructionBufferEntries = math.max(8, width),
        maxTemplateUops = math.max(8, width)),
      ooo = main.ooo.copy(
        robGroupsPerStid = 8,
        maxInstructionsPerRobGroup = 1,
        robBankCount = 8,
        brobEntriesPerStid = 8,
        pcBufferEntries = 8,
        pcBankCount = bankCount,
        pcRecoveryScanGroupsPerCycle = 4,
        gprPhysRegs = 64,
        gprMapQDepthPerStid = 16,
        tPhysRegs = 16,
        uPhysRegs = 16,
        tuMapQDepthPerStid = 16),
      iex = main.iex.copy(scalarIssueEntries = 16),
      lsu = main.lsu.copy(
        loadQueueEntries = 4,
        storeQueueEntries = 4,
        loadReturnQueueEntries = 2,
        storeCommitQueueEntries = 4,
        scbEntries = 4))
  }

  val W2: CoreParams = reduced(2)
  val W4: CoreParams = reduced(4)
  val W6: CoreParams = reduced(6)
  val W8: CoreParams = reduced(8)

  def forWidth(width: Int): CoreParams = width match {
    case 2 => W2
    case 4 => W4
    case 6 => W6
    case 8 => W8
    case _ => throw new IllegalArgumentException(
      s"unsupported simulation width $width; supported widths are 2, 4, 6, and 8")
  }
}
```

- [ ] **Step 4: Run the focused profile test**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only SimulationParamProfilesSpec
```

Expected: all profile tests pass and `ParamChecks.validate` accepts W2/W4/W6/W8.

- [ ] **Step 5: Commit only the profile files**

```bash
git commit --only -m "Add capacity-bounded Chisel simulation profiles" -- \
  chisel/src/test/scala/linxcore/params/SimulationParamProfiles.scala \
  chisel/src/test/scala/linxcore/params/SimulationParamProfilesSpec.scala
git push origin codex/chisel-gap-superpowers
```

### Task 2: Supervise one command with observable build liveness

**Files:**
- Create: `tools/chisel/chisel_test_supervisor.py`
- Create: `tests/test_chisel_test_supervisor.py`

**Interfaces:**
- Consumes: a child command, artifact root, optional log path, heartbeat/stall/wall seconds, low CPU threshold, and optional artifact budget.
- Produces: heartbeat lines prefixed `linx-chisel-heartbeat `, one final JSON summary prefixed `linx-chisel-summary `, the child exit status, or 124 for supervisor timeout.

- [ ] **Step 1: Write supervisor unit tests against stable Python interfaces**

The test imports these names:

```python
from tools.chisel.chisel_test_supervisor import (
    ArtifactSnapshot,
    SupervisorConfig,
    parse_non_negative_int,
    parse_positive_int,
    snapshot_artifacts,
    supervise,
)
```

Cover these exact cases with temporary directories and `sys.executable -c ...` child commands:

```python
class ChiselTestSupervisorTest(unittest.TestCase):
    def test_positive_and_non_negative_values_reject_invalid_text(self): ...
    def test_output_progress_prevents_idle_stall(self): ...
    def test_artifact_progress_prevents_idle_stall(self): ...
    def test_cpu_active_silent_child_is_not_called_stalled(self): ...
    def test_silent_idle_child_exits_124_with_diagnostics(self): ...
    def test_child_failure_status_is_preserved(self): ...
    def test_artifact_budget_names_largest_file(self): ...
    def test_interrupt_reaches_the_child_process_group(self): ...
```

Unit tests use 1-second public intervals and bounded children. The CPU-active child performs a Python integer loop; the idle child sleeps. Artifact progress appends one byte every 200 ms. The interrupt test starts a child that writes its received `SIGTERM` to a temporary file.

- [ ] **Step 2: Run the tests and verify the module is missing**

Run:

```bash
python3 -m unittest tests.test_chisel_test_supervisor -v
```

Expected: import failure for `tools.chisel.chisel_test_supervisor`.

- [ ] **Step 3: Implement the supervisor with standard-library-only types**

Define these public records and functions:

```python
@dataclasses.dataclass(frozen=True)
class ArtifactSnapshot:
    bytes: int
    latest_mtime_ns: int
    file_count: int
    largest: tuple[tuple[str, int], ...]

@dataclasses.dataclass(frozen=True)
class SupervisorConfig:
    heartbeat_seconds: int = 30
    stall_seconds: int = 600
    wall_seconds: int = 0
    low_cpu_percent: float = 1.0
    artifact_root: pathlib.Path | None = None
    artifact_budget_bytes: int = 0
    log_path: pathlib.Path | None = None
    selector: str = "all"

def parse_positive_int(name: str, text: str) -> int: ...
def parse_non_negative_int(name: str, text: str) -> int: ...
def snapshot_artifacts(root: pathlib.Path | None) -> ArtifactSnapshot: ...
def supervise(config: SupervisorConfig, command: Sequence[str]) -> int: ...
def main(argv: Sequence[str] | None = None) -> int: ...
```

`supervise` must:

1. start the child with `start_new_session=True`, merged stdout/stderr, and line-buffered binary forwarding;
2. continuously tee child output to the terminal and optional log;
3. sample descendant PID/PPID/CPU/RSS/command from `ps -axo pid=,ppid=,%cpu=,rss=,command=` and compute the descendant closure from the child PID;
4. classify `sbt`, `scala-compile`, `elaboration`, `firrtl`, `verilation`, `cxx-build`, `simulation`, or `result` from the most active descendant command and recent output;
5. update independent timestamps for output and artifact progress;
6. declare idle stall only when output and artifacts are unchanged for `stall_seconds` and aggregate descendant CPU is below `low_cpu_percent`;
7. emit heartbeat and final summary records with selector, phase, elapsed seconds, quiet seconds, artifact bytes, process count, aggregate CPU, RSS, peak RSS, and most-active command;
8. forward `SIGINT`/`SIGTERM` to the child process group, wait 5 seconds, then send `SIGKILL` only to surviving members;
9. fail an artifact budget after the child exits successfully, naming total bytes and the five largest files;
10. preserve every nonzero child status unchanged.

- [ ] **Step 4: Run supervisor tests and static syntax validation**

Run:

```bash
python3 -m py_compile tools/chisel/chisel_test_supervisor.py
python3 -m unittest tests.test_chisel_test_supervisor -v
```

Expected: both commands pass; the idle-stall test returns 124 and contains a final diagnostic summary.

- [ ] **Step 5: Commit and push the supervisor**

```bash
git commit --only -m "Supervise Chisel tests with build heartbeats" -- \
  tools/chisel/chisel_test_supervisor.py \
  tests/test_chisel_test_supervisor.py
git push origin codex/chisel-gap-superpowers
```

### Task 3: Route the Chisel runner through one bounded SBT invocation

**Files:**
- Modify: `tools/chisel/run_chisel_tests.sh`
- Modify: `chisel/build.sbt`
- Create: `tests/test_chisel_test_runner.py`

**Interfaces:**
- Consumes: repeated `--only SUITE`, `--all`, `--heartbeat-seconds`, `--stall-seconds`, `--wall-seconds`, `--jobs`, `--artifact-budget-bytes`, and matching `LINX_CHISEL_*` environment values.
- Produces: one supervisor invocation, one SBT server command, and an SBT Test concurrency ceiling equal to `LINX_CHISEL_TEST_JOBS`.

- [ ] **Step 1: Write fake-SBT runner contract tests**

Create a temporary executable named `sbt` at the front of `PATH`. It records arguments and `LINX_CHISEL_TEST_JOBS`, then exits with a requested status. Tests assert:

```python
class ChiselTestRunnerTest(unittest.TestCase):
    def test_repeated_only_selectors_form_one_test_only_command(self): ...
    def test_all_and_only_are_mutually_exclusive(self): ...
    def test_default_jobs_is_two(self): ...
    def test_cli_jobs_overrides_environment(self): ...
    def test_zero_stall_disables_idle_termination(self): ...
    def test_invalid_numeric_options_fail_before_sbt(self): ...
    def test_fake_sbt_failure_status_is_preserved(self): ...
```

For selectors `FooSpec` and `BarSpec`, the recorded final SBT command must be exactly:

```text
testOnly *FooSpec* *BarSpec*
```

- [ ] **Step 2: Run the tests and observe the first unsupported option**

Run:

```bash
python3 -m unittest tests.test_chisel_test_runner -v
```

Expected: failure because the runner currently accepts only one `--only` and has no supervisor options.

- [ ] **Step 3: Implement CLI validation and one supervised command**

Update `run_chisel_tests.sh` to collect selectors in a Bash array and validate public integers before invoking Python. Defaults are:

```bash
LINX_CHISEL_HEARTBEAT_SECONDS="${LINX_CHISEL_HEARTBEAT_SECONDS:-30}"
LINX_CHISEL_STALL_SECONDS="${LINX_CHISEL_STALL_SECONDS:-600}"
LINX_CHISEL_WALL_SECONDS="${LINX_CHISEL_WALL_SECONDS:-0}"
LINX_CHISEL_TEST_JOBS="${LINX_CHISEL_TEST_JOBS:-2}"
LINX_CHISEL_ARTIFACT_BUDGET_BYTES="${LINX_CHISEL_ARTIFACT_BUDGET_BYTES:-0}"
```

The script constructs an argument array, never an `eval` string, then executes:

```bash
exec python3 "${ROOT_DIR}/tools/chisel/chisel_test_supervisor.py" \
  --selector "${selector_summary}" \
  --heartbeat-seconds "${LINX_CHISEL_HEARTBEAT_SECONDS}" \
  --stall-seconds "${LINX_CHISEL_STALL_SECONDS}" \
  --wall-seconds "${LINX_CHISEL_WALL_SECONDS}" \
  --artifact-root "${CHISEL_DIR}/build" \
  --artifact-budget-bytes "${LINX_CHISEL_ARTIFACT_BUDGET_BYTES}" \
  -- "${sbt_command[@]}"
```

- [ ] **Step 4: Apply the SBT concurrency ceiling**

In `chisel/build.sbt`, parse `LINX_CHISEL_TEST_JOBS` once, reject non-positive values with a clear message, retain `Test / parallelExecution := true`, and add:

```scala
Global / concurrentRestrictions += Tags.limit(Tags.Test, chiselTestJobs)
```

The default is 2. This setting limits independent Test tasks within the one SBT server; it does not spawn another SBT client.

- [ ] **Step 5: Run runner tests and shell syntax validation**

Run:

```bash
bash -n tools/chisel/run_chisel_tests.sh
python3 -m unittest tests.test_chisel_test_runner -v
```

Expected: all fake-SBT cases pass without creating `chisel/build`.

- [ ] **Step 6: Compile the SBT configuration after the existing W8 run is cleaned**

Run:

```bash
LINX_CHISEL_TEST_JOBS=2 \
  bash tools/chisel/run_chisel_tests.sh --only CoreConfigurationSpec
```

Expected: the first heartbeat names the selected suite and job count; the test passes.

- [ ] **Step 7: Commit and push runner integration**

```bash
git commit --only -m "Bound Chisel suite parallelism in one SBT server" -- \
  tools/chisel/run_chisel_tests.sh \
  chisel/build.sbt \
  tests/test_chisel_test_runner.py
git push origin codex/chisel-gap-superpowers
```

### Task 4: Detect architectural deadlock from simulated-cycle progress

**Files:**
- Create: `chisel/src/test/scala/linxcore/testutil/SimulationProgress.scala`
- Create: `chisel/src/test/scala/linxcore/testutil/SimulationProgressSpec.scala`

**Interfaces:**
- Consumes: current simulated cycle, zero or more named progress observations, and a harness-specific diagnostic callback.
- Produces: `SimulationProgress.observe(cycle, events)` and `SimulationProgress.requireAlive(cycle)`; no hardware IO.

- [ ] **Step 1: Write pure Scala progress tests**

Cover exact threshold behavior:

```scala
package linxcore.testutil

import org.scalatest.funsuite.AnyFunSuite

class SimulationProgressSpec extends AnyFunSuite {
  test("a typed progress event resets the idle-cycle window") {
    val progress = new SimulationProgress(8, () => "rob=1,liq=0")
    progress.observe(7, Seq(SimulationProgressEvent("dispatch", "rid=3")))
    progress.requireAlive(15)
  }

  test("the first cycle beyond the limit reports the last identity and occupancy") {
    val progress = new SimulationProgress(8, () => "rob=1,liq=1")
    progress.observe(4, Seq(SimulationProgressEvent("memory-rebind", "attempt=2")))
    val error = intercept[IllegalStateException] {
      progress.requireAlive(13)
    }
    assert(error.getMessage.contains("lastCycle=4"))
    assert(error.getMessage.contains("memory-rebind"))
    assert(error.getMessage.contains("attempt=2"))
    assert(error.getMessage.contains("rob=1,liq=1"))
  }

  test("empty observations do not create synthetic progress") {
    val progress = new SimulationProgress(2, () => "iq=1")
    progress.observe(0, Seq.empty)
    assertThrows[IllegalStateException](progress.requireAlive(3))
  }
}
```

- [ ] **Step 2: Run the test and verify the missing type failure**

Run after the current build exits:

```bash
bash tools/chisel/run_chisel_tests.sh --only SimulationProgressSpec
```

Expected: compile failure naming `SimulationProgress` and `SimulationProgressEvent`.

- [ ] **Step 3: Implement the cycle-domain helper**

Use these signatures:

```scala
final case class SimulationProgressEvent(kind: String, identity: String)

final class SimulationProgress(
    maxIdleCycles: Long,
    diagnostics: () => String) {
  require(maxIdleCycles > 0, "maxIdleCycles must be positive")

  def observe(cycle: Long, events: Iterable[SimulationProgressEvent]): Unit
  def requireAlive(cycle: Long): Unit
}
```

`observe` records only non-empty events, rejects decreasing cycles, and retains the most recent event list. `requireAlive` fails when `cycle - lastProgressCycle > maxIdleCycles`, including current cycle, last cycle, event kinds and identities, and the callback text. The helper imports no Chisel hardware packages.

- [ ] **Step 4: Run the focused helper test**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only SimulationProgressSpec
```

Expected: all three tests pass.

- [ ] **Step 5: Commit and push the test helper**

```bash
git commit --only -m "Detect simulation deadlock from typed progress" -- \
  chisel/src/test/scala/linxcore/testutil/SimulationProgress.scala \
  chisel/src/test/scala/linxcore/testutil/SimulationProgressSpec.scala
git push origin codex/chisel-gap-superpowers
```

### Task 5: Migrate the W8 continuous-prefix proof and measure artifact reduction

**Files:**
- Modify: `chisel/src/test/scala/linxcore/ooo/OOOD3ContinuousPrefixSpec.scala`
- Create: `docs/chisel/generated/simulation-artifact-baseline.json`

**Interfaces:**
- Consumes: `SimulationParamProfiles.W8`, the canonical D3 graph, three PC Buffer writes, six reads, and the existing eight-lane retained transaction.
- Produces: the unchanged three-three-two publication proof plus measured before/after artifact evidence.

- [ ] **Step 1: Preserve the pre-change result and clean only after exit**

Wait for the already-running full-profile W8 process. Record exit status, elapsed time, peak RSS, total generated bytes, generated line count, and five largest files. After every descendant exits, remove only its ignored `chisel/build/chiselsim/OOOD3ContinuousPrefixSpec` directory and report that the generated directory is safe to clean.

- [ ] **Step 2: Change only the selected test profile**

Replace:

```scala
import linxcore.params.ParamProfiles
simulate(new OOOD3S1Graph(ParamProfiles.W8))
```

with:

```scala
import linxcore.params.SimulationParamProfiles
simulate(new OOOD3S1Graph(SimulationParamProfiles.W8))
```

Do not change the eight-lane input, the 96-cycle bound, the expected tail transitions `Seq(3, 6, 8)`, or transaction/RID/LSID/PTag/PC Buffer continuity assertions.

- [ ] **Step 3: Run the reduced W8 case with a checked artifact budget**

Run:

```bash
LINX_CHISEL_ARTIFACT_BUDGET_BYTES=268435456 \
  bash tools/chisel/run_chisel_tests.sh \
    --only OOOD3ContinuousPrefixSpec \
    --heartbeat-seconds 30 \
    --stall-seconds 600 \
    --jobs 1
```

Expected: PASS, tail transitions `3,6,8`, total artifact bytes below 256 MiB, and no idle-stall classification while Verilator is CPU-active.

- [ ] **Step 4: Write the machine-readable before/after report**

Create a JSON object with schema
`linxcore.chisel.simulation_artifact_baseline.v1`, suite
`OOOD3ContinuousPrefixSpec`, and case
`W8 publishes one retained D3 transaction as three three two`. Both
`main_profile` and `simulation_profile` contain the captured non-empty Git SHA,
positive elapsed seconds, positive peak RSS bytes, positive generated bytes,
positive generated line count, and a non-empty `largest_files` array of
repository-relative path/byte pairs. Add:

```json
"result_parity": {
  "passed": true,
  "tail_transitions": [3, 6, 8],
  "published_lanes": 8
}
```

Before commit, load the JSON with Python and assert both measurements are
positive, the reduced generated-byte count is below 268435456, and every
result-parity field equals the values above.

- [ ] **Step 5: Re-run the full-profile non-simulation contract gates**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only CoreConfigurationSpec
bash tools/chisel/run_chisel_tests.sh --only TopInterfaceSpec
bash tools/chisel/run_chisel_tests.sh --only InterfaceManifestSpec
python3 tools/chisel/render_top_interface_manifest.py --check
```

Expected: W2/W4/W6/W8 main profiles and their interface widths remain unchanged.

- [ ] **Step 6: Leave the migrated test in the Task-13 atomic cutover commit**

Because `OOOD3ContinuousPrefixSpec.scala` is part of the uncommitted Task-13 graph, do not create a separate commit for it. Stage it only with Task 13 after the private OOO/IEX graph, owner manifest, NDF, docs, deletion set, and all Task-13 replacement evidence are green.

### Task 6: Document, validate, and close the infrastructure loop

**Files:**
- Modify: `docs/chisel/integrated-development-flow.md`
- Modify: `docs/superpowers/specs/2026-08-03-simulator-heartbeat-parallel-build-design.md`
- Modify: this plan's checkboxes as each task completes.

**Interfaces:**
- Consumes: supervisor CLI, SBT job limit, progress helper, profile contract, and measured W8 report.
- Produces: reproducible operator guidance and reviewable completion evidence.

- [ ] **Step 1: Document exact runner controls and evidence boundaries**

Add one concise section that lists:

```text
--heartbeat-seconds / LINX_CHISEL_HEARTBEAT_SECONDS (default 30)
--stall-seconds / LINX_CHISEL_STALL_SECONDS (default 600; 0 disables)
--wall-seconds / LINX_CHISEL_WALL_SECONDS (default 0; disabled)
--jobs / LINX_CHISEL_TEST_JOBS (default 2)
--artifact-budget-bytes / LINX_CHISEL_ARTIFACT_BUDGET_BYTES (default 0; disabled)
```

State that build stall requires simultaneous output, artifact, and low-CPU inactivity; architectural deadlock uses simulated cycles; simulation profiles cannot supply interface or full-capacity evidence.

- [ ] **Step 2: Run non-Chisel static and Python verification**

Run:

```bash
bash -n tools/chisel/run_chisel_tests.sh
python3 -m py_compile tools/chisel/chisel_test_supervisor.py
python3 -m unittest \
  tests.test_chisel_test_supervisor \
  tests.test_chisel_test_runner -v
git diff --check
```

Expected: all pass.

- [ ] **Step 3: Run the focused Scala verification in one bounded SBT invocation**

Run:

```bash
bash tools/chisel/run_chisel_tests.sh \
  --only SimulationParamProfilesSpec \
  --only SimulationProgressSpec \
  --only OOOD3ContinuousPrefixSpec \
  --jobs 2
```

Expected: all selected suites pass, heartbeats remain visible, and no artifact budget is exceeded.

- [ ] **Step 4: Verify NDF, interface, and owner evidence remain valid**

Run:

```bash
python3 tools/spec/check_ndf_profile.py docs/spec
python3 tools/chisel/render_top_interface_manifest.py --check
python3 tools/chisel/check_production_owner_manifest.py
```

Expected: all pass; no simulator helper is listed as a hardware owner or public interface.

- [ ] **Step 5: Clean ignored generated artifacts after every process exits**

Remove only ignored Chisel/SBT/Verilator output created by these tests. Preserve source, tests, checked manifests, the artifact baseline JSON, and all Task-13 edits. Verify no simulator descendant remains before cleanup.

- [ ] **Step 6: Commit and push the documentation closure**

```bash
git commit --only -m "Document bounded Chisel simulation evidence" -- \
  docs/chisel/integrated-development-flow.md \
  docs/chisel/generated/simulation-artifact-baseline.json \
  docs/superpowers/specs/2026-08-03-simulator-heartbeat-parallel-build-design.md \
  docs/superpowers/plans/2026-08-03-simulator-heartbeat-parallel-build.md
git push origin codex/chisel-gap-superpowers
```

Expected: local `HEAD` equals `origin/codex/chisel-gap-superpowers`; unrelated Task-13 index and working-tree changes remain intact.

## Execution Order

Run Tasks 1-4 without starting Scala verification while the pre-existing W8 process is alive; Python-only tests may run concurrently with it. After that process exits, capture its baseline, clean its ignored directory, run the pending focused Scala tests, migrate the W8 profile, and complete Tasks 5-6. Because native subagent dispatch is disabled for this execution context, use the inline `superpowers:executing-plans` workflow and preserve the task-by-task verification gates above.
