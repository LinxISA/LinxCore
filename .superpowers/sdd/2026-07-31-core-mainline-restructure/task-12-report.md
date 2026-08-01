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
