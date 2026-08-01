# Task 12 Report: Private IEX Mechanism Preparation

## Status

COMPLETE — private IEX mechanisms are prepared and all Task 12 Step 4 gates
pass. The public-box cutover remains exclusively assigned to Task 13.

## Baseline

- LinxCore: `f89c38fb9c2eb299681c447b8e00cfe4db82824d`
- Superproject: `54635e8cb1119e5f199228cb7db330b168bf7dc0`
- LinxCoreModel: `31555f49dbb020c8eb9f26f7df98310a7415b69d`
- QEMU: `c9f9570aa70da7e193ff8857bd9bde2cf052e546`
- Scope source: `.superpowers/sdd/2026-07-31-core-mainline-restructure/task-12-brief.md`

## RED Evidence

1. Baseline selector:
   `bash tools/chisel/run_chisel_tests.sh --only IEXProductionMechanismSpec`
   failed with `No tests match the patterns: *IEXProductionMechanismSpec*`.
2. After introducing the first topology fixture, the suite failed to compile
   because `OooIexProductionPhysicalProfile` did not exist.
3. After adding the value-only profile, real-child elaboration failed with
   `execution cluster requires the formal Linx scalar/control profile`.
   This exposed the real canonical/profile mismatch rather than a test-only
   absence: `IEXParams` specifies 2 ALU, 1 BRU, 2 AGU, 2 STD, 1 shared
   system/multicycle queue, and 1 CMD queue, while the private execution child
   was fixed to 6 ALU, 2 BRU, 3 AGU, and 14 picker lanes.
4. The first canonical-topology check then failed because no ALU-class
   residency owner exposed `MultiCycleAlu`. The opcode recipe table classifies
   DIV/REM as ALU-class work requiring that capability. The final profile
   therefore dedicates one ALU IQ bank to the system/multicycle owner while
   retaining two simple-ALU execution pipes and disjoint full ALU coverage.

The unchanged legacy/default structural baseline
`bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionPipeline`
passed 1/1 after the implementation in 2 minutes 25 seconds.

## Owner and File Decisions

- `OooIexIssue` remains the sole IQ/residency owner. No public `IEX` module was
  created and no canonical payload adapter was added.
- `OooIexOperandFiles` remains the sole P/T/U data/readiness owner.
- `OooIexExecutionPipeline` and `OooIexTerminalFabric` remain the execution
  and terminal owners; their private dimensions now derive from an
  `OooIexPhysicalProfile` instead of scattered 6/2/3 constants.
- `OooIexProductionPhysicalProfile` is a value-only conversion from canonical
  `linxcore.params.CoreParams`. It owns no hardware state. It preserves the
  requested 2/1/2/2 topology for W2/W4/W6/W8, gives system/multicycle and CMD
  distinct residency owners, and leaves the old formal profile as the default
  until Task 13 performs the one-time public cutover.
- The load-allocation and load-terminal helpers now accept any positive AGU
  count covered by canonical LSU return-pipe identities. This removes the
  private three-AGU constant without changing load lifecycle ownership.
- Terminal lanes may have no source from one execution family (for example,
  one BRU across two terminal lanes). Such absent family/lane projections are
  explicitly tied off; existing sources retain stable modulo ownership.
- The manifest adds the new aggregate L3 fixture to the three IEX mechanism
  rows while retaining `public_box_status=pending`, `cutover_task=13`, and
  `mechanism-verified-cutover-pending`.

## GREEN Evidence

- `IEXProductionMechanismSpec`: 6/6 passed. It proves W2/W4/W6/W8 profile
  construction, W4 real-child 2 ALU/1 BRU/2 AGU elaboration, per-class
  residency, same-STID age, cross-STID fairness, scoped recovery, complete
  P/T/U read admission, exact retry, and atomic terminal retention.
- Existing-profile regressions recorded so far:
  - `OooIexPhysicalProfileSpec`: 3/3 passed.
  - `OooIexTerminalFabricSpec`: 3/3 passed.
  - `OooIexExecutionClusterSpec`: 4/4 passed.
  - `OooIexLoadLiqAllocAdapterSpec`: 5/5 passed.
  - `OooIexLoadTerminalMetadataSpec`: 3/3 passed.
  - `OooIexCanonicalLoadOwnershipSpec`: 7/7 passed.
- `OooOpcodeRecipeTableSpec`: 4/4 passed, including generated classification
  and hardware decode agreement for the ALU/multicycle recipe surface.
- Required Step 4 gates:
  - `bash tools/chisel/run_chisel_tests.sh --only IEXProductionMechanismSpec`:
    6/6 passed.
  - `bash tools/chisel/run_chisel_tests.sh --only OooIexPhysicalProfileSpec`:
    3/3 passed.
  - `bash tools/chisel/build_chisel.sh`: passed.
  - `bash tools/chisel/run_chisel_verilator_lint.sh`: passed with Verilator
    5.044 over 67 generated modules.
  - `python3 tools/chisel/check_production_owner_manifest.py`: passed with
    23 closed owners, 40 classified emitters, 10 declared adapters, and all
    NDF L1/L2/L3 roles mapped.
  - `git diff --check`: passed.

## Self-review

- No public `IEX` owner or emitter exists in this change.
- No `Reduced*` source is deleted or rewired; that remains Task 13.
- No parent plan is modified.
- The new expected topology is derived from canonical parameter literals, not
  from the legacy profile under test.
- Existing formal/default profile behavior remains under its original focused
  suites, preventing Task 12 from silently changing the live boundary.

## Untested Risks

- Task 12 does not prove OOO public `DispatchTxn` traffic through a public IEX
  box; Task 13 owns that atomic boundary cutover and its adapter deletion.
- This task does not provide workload, timing, physical-memory, or synthesis
  evidence. Those are later integration/promotion gates.
- The real W4 child topology is elaborated at canonical capacity for the
  execution cluster. Whole-pipeline composition is checked with the same
  topology and compact storage capacities because a full-capacity monolithic
  CHIRRTL attempt exhausted the local approximately 4 GiB JVM heap. This is
  not claimed as full-capacity whole-core elaboration evidence.
- The value-only profile currently rejects pipe-count combinations other than
  the approved 2/1/2/2/1/1 mechanism topology; W2/W4/W6/W8 refer to width, not
  to independently variable physical pipe counts.

## Skill Evolution

`skill-evolve: no-update` — the installed `linx-core` skill already requires
explicit physical topology, sole state ownership, generation-qualified load
identity, retained backpressure, common recovery, and honest separation of
standalone mechanism evidence from public-box promotion.

## Fix Round 1/5

### Status

COMPLETE — all four independent-review findings are addressed without a
public `IEX` owner, public-boundary cutover, `Reduced*` deletion, parent-plan
edit, or push.

### Finding 1 — canonical IEX conversion

RED:

- Literal W2/W4/W6/W8 assertions observed the inherited legacy geometry
  `iqBankCount=8` instead of canonical `scalarIssueBanks=2`; the aggregate
  suite failed 5/6 at the exact bank-count assertion.
- Restoring multicycle ALU coverage after the 2-bank mapping initially failed
  profile construction because the old validator rejected even
  capability-disjoint logical owner projections on the same class/bank.

GREEN:

- `canonicalIexParams` is the dedicated value-only canonical IEX projection.
  `issueWidth` must equal `WidthParams.issueWidth`, `WidthParams.dispatchWidth`,
  and `OOOParams.dispatchWidth`; it defines S1/dispatch admission rather than
  physical picker count.
- `scalarIssueEntries` is total capacity, must divide evenly by
  `scalarIssueBanks`, and maps to
  `iqBankCount=scalarIssueBanks` plus
  `iqEntriesPerBank=scalarIssueEntries/scalarIssueBanks`.
- `integerReadPorts` and `integerWritePorts` map exactly to `iexPReadPorts`
  and `iexPWritePorts`.
- Every remaining `IEXParams` field is explicit: 2 ALU, 1 BRU, 2 AGU, 2 STD,
  1 system/multicycle, and 1 CMD are required and used to construct distinct
  owners/pickers. No canonical IEX field is silently ignored.
- With only two physical IQ banks, simple-ALU and multicycle owners may project
  the same class/bank only when their capability masks are disjoint. The
  profile still rejects every class/bank/capability overlap. This preserves
  ALU-class DIV/REM routing while keeping system/multicycle ownership separate.
- W2/W4/W6/W8 assert exact `2 x 32 = 64` IQ geometry, 6 P-read ports, 5
  P-write ports, 10 picker domains, and 2/1/2/2/1/1 topology.

### Finding 2 — real owner behavior and honest L3 evidence

RED:

- The original aggregate suite instantiated `OooIexOldestReadyPicker`, not
  the real `OooIexIssue`; it also contained no two-AGU canonical load-owner
  lifecycle.
- A full 10-domain `OooIexIssue` behavioral model remained in Verilator
  generation for more than 15 minutes at about 3.5 GiB RSS. A 5-domain retry
  still took 15m35s and exposed a test bug: AGU required-capability bit 0 was
  `SimpleAlu`, so the real LDA picker correctly remained invalid.
- The first two-AGU test assumed lane 0 won reset arbitration; `RRArbiter`
  correctly selected lane 1 first. A later return was also rejected because
  the fixture reused lane-2 destination tags for lane 1.

GREEN:

- Evidence is deliberately split. Exact 10-domain structural assertions prove
  the canonical LDA/STA/STD/system/CMD owner and capability projections.
  A sustainable real `OooIexIssue` fixture uses their exact capability union
  and proves AGU load/store-address rows, STD, system, and CMD allocate into
  disjoint real rows, retain under backpressure, claim atomically, and accept
  one exact retry. It does not claim cross-domain arbitration.
- `OooIexCanonicalLoadOwnership(..., laneCount=2)` now receives both AGUs
  simultaneously, accepts the real RR order, and proves return-pipe 1/0
  separation, full LSID, PE/STID, native BID, BROB generation, RID slot and
  generation, member index, resident generation, and attempt generation.
  Rebind backpressure holds both leases, stale completion is rejected, the
  peer lane returns without identity confusion, and scoped recovery removes
  only the remaining lease.
- The manifest retains aggregate L3 only for real issue behavior. The previous
  aggregate claims for operand-file and execution ownership were removed;
  execution L3 now cites the real terminal and canonical load-owner fixtures.

### Finding 3 — multi-cycle terminal retention

RED:

- The original aggregate test drove `OooIexTerminalPublish` combinationally
  and released backpressure in the same cycle; it did not cross
  `OooIexTerminalFabric` or retain a W-stage owner for multiple clocks.

GREEN:

- `OooIexTerminalFabricSpec` drives a real ALU W-stage transaction through the
  fabric for three backpressured cycles. Every cycle preserves full PE/STID,
  RID slot/generation, BID/BROB generation, member/resident generation and
  destination identity; terminal fire is zero and no P-write, trace, or BCTRL
  endpoint partially publishes.
- Releasing completion backpressure produces exactly one atomic terminal fire,
  P-write, wake/trace/completion publication; removing producer valid yields
  no duplicate fire over the following cycles.

### Finding 4 — width warnings

RED:

- The initial aggregate elaboration reported 100 W002/W004 warnings from
  singleton `stidCount=1` and `iqEntriesPerBank=1` fixture geometry.

GREEN:

- The aggregate fixture now uses `stidCount=2`, `iqBankCount=2`, and
  `iqEntriesPerBank=4`. Final aggregate and focused-owner runs report zero
  W002/W004 hardware warnings. The only remaining warning is sbt's existing
  `multiple main classes detected` message.

### Verification

- `IEXProductionMechanismSpec`: 7/7 passed in 4m36s after the final
  class/bank/capability fix.
- `OooIexCanonicalLoadOwnershipSpec`: 8/8 passed.
- `OooIexTerminalFabricSpec`: 4/4 passed.
- `OooIexPhysicalProfileSpec`: 4/4 passed.
- `OooIexExecutionClusterSpec`: 4/4 passed.
- `OooIexLoadLiqAllocAdapterSpec`: 5/5 passed.
- `OooIexLoadTerminalMetadataSpec`: 3/3 passed.
- `OooOpcodeRecipeTableSpec`: 4/4 passed after the final profile fix.
- `bash tools/chisel/build_chisel.sh`: passed.
- `bash tools/chisel/run_chisel_verilator_lint.sh`: passed with Verilator
  5.044 over 67 generated modules.
- `python3 tools/chisel/check_production_owner_manifest.py`: passed with 23
  closed owners, 40 classified emitters, 10 declared adapters, and all NDF
  L1/L2/L3 roles mapped.
- `git diff --check` and `git diff --cached --check`: passed.

### Self-review

- The canonical profile no longer self-proves against legacy IQ defaults.
- Logical owner overlap is permitted only when capability-disjoint; hardware
  residency remains solely in `OooIexIssue`.
- Test expected identities are lane-specific literals, not values derived by
  the implementation under test.
- No public `IEX`, public emitter, live-boundary rewiring, `Reduced*` deletion,
  parent-plan edit, or push is present.

### Skill Evolution

`skill-evolve: no-update` — the existing `linx-core` workflow already requires
explicit parameter domains, sole state ownership, full generation-qualified
identity, honest evidence levels, retained backpressure, and common recovery.

## Fix Round 2/5

### Status

COMPLETE — the three rereview findings are closed with test-only evidence.
No production RTL, manifest path, parent plan, public boundary, or live owner
was changed.

### Finding 1 — complete canonical capability behavior

RED:

- The real one-domain `OooIexIssue` fixture advertised only load address,
  store address, store data, system, and engine-command capabilities. It did
  not behaviorally exercise simple ALU, multicycle ALU, pointer-auth, branch,
  or floating/vector residency.
- The first expanded fixture still claimed and retried only the first selected
  row. Final diff review rejected that partial proof before commit.

GREEN:

- The sustainable one-domain fixture derives its capability mask from the OR
  of every canonical picker and independently checks it against the OR of
  every canonical residency owner and the full ten-bit valid mask.
- Literal representative rows cover simple ALU, multicycle ALU, BRU, AGU LDA,
  AGU STA, STD, FSU, system, pointer-auth, and CMD. Each row independently
  allocates into real `OooIexIssue` state, remains stable for three blocked
  cycles, claims atomically, accepts an exact retry, reclaims, and releases by
  exact identity. The separate ten-domain structural proof remains unchanged.

### Finding 2 — literal two-AGU identity proof

RED:

- The prior two-AGU fixture checked only selected RID/LSID fields and derived
  attempt generations by peeking the DUT. It therefore could not prove full
  identity stability during rebind or retained return.
- Literal-first assertions exposed the actual global attempt-generation order:
  lane 1 allocates generation 1, lane 0 allocates generation 2, and lane 0
  rebinds to generation 3.

GREEN:

- A hand-authored expected-lease helper checks PE/STID, native BID validity and
  value, BROB generation, RID slot and generation, member index, resident
  generation, full LSID, load/attempt generation, return pipe, and destination
  tags without constructing expected values from DUT outputs.
- Both rebind-stall cycles verify current and next canonical identities. The
  accepted cancel, stale-return rejection, and every peer-return backpressure
  cycle preserve the corresponding full literal identity.

### Finding 3 — real ALU pipeline to terminal retention

RED:

- The prior terminal testbench held `OooIexTerminalFabric.alu` directly. It did
  not prove that a real `OooIexAluPipeline` W1/W2 owner retained the row.
- The first real-pipeline run rejected the old direct-fixture data constant
  `0x103`; the real `ADDI 41, 1` transaction correctly produced `42`.

GREEN:

- A test-only harness connects real `OooIexAluPipeline.w2` directly to
  `OooIexTerminalFabric.alu(0)` and ties off the unused BRU/load families.
- Completion backpressure is held for three cycles. Every cycle checks the
  full ROB-member identity, retained destination atag/PTAG/generation/local
  identity, writeback data, W2 ownership, zero terminal fire, and zero partial
  P/T/U write, wakeup, trace, or BCTRL publication.
- Releasing completion produces exactly one atomic fire. W2 then withdraws,
  and later cycles show no duplicate completion or writeback.

### Verification

- `IEXProductionMechanismSpec`: 7/7 passed in 4m47s after the final per-row
  claim/retry/release tightening.
- `OooIexCanonicalLoadOwnershipSpec`: 8/8 passed in 44.1s.
- `OooIexTerminalFabricSpec`: 4/4 passed in 32.1s.
- `OooIexPhysicalProfileSpec`: 4/4 passed.
- `python3 tools/chisel/check_production_owner_manifest.py`: passed with 23
  closed owners, 40 classified emitters, 10 declared adapters, and all NDF
  L1/L2/L3 roles mapped.
- `bash tools/chisel/build_chisel.sh`: passed.
- `bash tools/chisel/run_chisel_verilator_lint.sh`: passed with Verilator
  5.044 over 67 generated modules.
- `git diff --check` and `git diff --cached --check`: passed.

### Warnings and self-review

- The only tool warning is sbt's existing `multiple main classes detected`.
- No production source, manifest, parent plan, public `IEX`, live boundary,
  `Reduced*` source, or generated artifact is modified.
- All three expected-value surfaces are literal or independently derived from
  canonical topology; none reconstructs expected identity from DUT output.

### Skill Evolution

`skill-evolve: no-update` — this round strengthens local tests but introduces
no new reusable LinxCore ownership, identity, recovery, or gate invariant.

## Fix Round 3/5

### Status

COMPLETE — the remaining terminal-retention evidence gap is closed in the
test-only harness. No production RTL, manifest, parent plan, or public boundary
is changed.

### RED

- Round 2 checked the completion member directly but did not independently
  inspect the retained W2 member on every blocked cycle.
- Completion omitted explicit `group.valid` and `bid.valid` checks. The fire
  cycle and two post-fire cycles did not enumerate every applicable endpoint,
  leaving room for a partial or duplicate publication to escape the fixture.

### GREEN

- Each of three blocked cycles now checks the complete retained W2 and
  completion identity: group validity, PE/STID, RID slot/generation, BID
  validity/value, BROB generation, member index, and resident generation.
- The retained W2 writeback checks destination valid/kind, atag, PTAG,
  PTAG generation, local tag, local-sequence valid/index/generation, and data
  `42` every blocked cycle.
- Blocked cycles require zero terminal fire and zero P/T/U write, wakeup,
  trace, or BCTRL publication on every exposed port. Only the blocked
  completion advertises valid, with ready low; peer completion lanes are
  invalid.
- The release cycle explicitly proves completion, P-write, wakeup, and trace
  valid together with the full completion identity, retained W2 identity,
  destination, and data. All peer P-write/wakeup/trace lanes and every T/U and
  BCTRL endpoint remain invalid.
- Both following cycles require W2 empty, retained W2 invalid, terminal fire
  zero, and every completion/write/wakeup/trace/BCTRL endpoint invalid. This
  is the direct exactly-once/no-duplicate proof.

### Verification

- `OooIexTerminalFabricSpec`: 4/4 passed in 29.96s.
- `bash tools/chisel/build_chisel.sh`: passed.
- `bash tools/chisel/run_chisel_verilator_lint.sh`: passed with Verilator
  5.044 over 67 generated modules.
- `python3 tools/chisel/check_production_owner_manifest.py`: passed with 23
  closed owners, 40 classified emitters, 10 declared adapters, and all NDF
  L1/L2/L3 roles mapped.
- `git diff --check` and `git diff --cached --check`: passed.

### Warnings and self-review

- The only tool warning is sbt's existing `multiple main classes detected`.
- The diff is limited to `OooIexTerminalFabricSpec.scala` and this report.
- No expected identity or data field is read back from the DUT to construct an
  expected value.

### Skill Evolution

`skill-evolve: no-update` — this is a local evidence-completeness correction;
the existing LinxCore retained-owner and atomic-publication invariants already
cover the reusable rule.
