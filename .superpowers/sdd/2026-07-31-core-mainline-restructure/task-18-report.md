# Task 18 implementation report

## Baseline and audit

- Task baseline: `e0c90c2c75942b01b3a175fbdc916a04cc83b0c2`.
  Fix-round baseline: `8697fa1a`.
- Callable legacy tops: `LinxCoreFrontendTraceTop`,
  `LinxCoreFrontendFetchTraceTop`, and the older `LinxCoreComposition` graph.
- Current emitted build/lint top: `OOOIEXLSUActivationProbe` through
  `tools/chisel/emit_verilog.sh` and `run_chisel_verilator_lint.sh`.
- Reusable wiring source: `EmitOOOIEXLSUActivationProbe.scala` already connects
  OOO, IEX, LSU, recovery, bootstrap, DTU observation, and CMD backpressure.
- Existing natural runner: `run_chisel_benchmark_autonomous_top_natural.sh`
  with `benchmark_autonomous_top_natural_tb.cpp`; it is not the Task-18 TOP
  harness.
- `TOPIO` initially had one data-memory lane despite LSU exposing independent
  load/store lanes, no boot/configuration projection, and no projections for
  remaining LSU/system leaves.
- `MemoryRequestTxn.sizeBytes` was a four-bit literal. LSU assigned the
  64-byte line size to it, which truncates to zero. The external contract needs
  a typed size encoding and the NDF verification graph needs an explicit edge
  back to `PRM-LSU-SIZING-001`.

## Wished-for RED tests

- `TOPSpec`: exactly one IFU/CTU/OOO/IEX/LSU/DTU, no TOP-owned Reg/Queue/Mem,
  no IEX-to-IFU control path, real CMD backpressure, loss-tolerant trace intake.
- `TOPWidthProfilesSpec`: independent W2/W4/W6/W8 elaboration.
- `TopInterfaceSpec`: typed external memory size including 64 bytes, independent
  data-memory lanes, one typed clock/reset boundary, and no raw reset leaf.
- `InterfaceManifestSpec`: `TOPIO` endpoint plus every external leaf mapped to
  an `IFC-TOP-EXT-*` clause without payload duplication.
- `tests.test_top_natural`: executable self-test manifest, exact harness owner
  list, typed size mapping, and absence of instruction/commit replay inputs.

## RED evidence

- `TOPSpec` initially did not compile because `TOP`, `TOPIO` leaves, and
  `MemorySize` did not exist. After the first direct OOO/IEX connection,
  FIRRTL rejected a combinational cycle through OOO dispatch admission and
  IEX ready. The final repair removes the generic boundary queues: presented
  dispatch valid/payload is structural, every visible group uses one common
  ready, and typed retained owners remain at the semantic producer.
- `TOPWidthProfilesSpec` initially rejected W2 because its two-entry directed
  fetch buffer could not accept the fixed four-instruction I-side ingress.
  The directed profile now keeps a four-entry minimum without changing any
  public width.
- `InterfaceManifestSpec` initially rejected the checked-in JSON/Markdown
  because they lacked `top_io` and the typed memory-size leaves.
- `tests.test_top_natural` initially failed because the Task-18 runner and C++
  harness did not exist.
- Fix-round combinational-cycle audit found two exact ready-to-valid edges.
  `OooDispatch.prefixComplete(offset)` consumed the prior lane's IEX `ready`
  and drove the next lane's `valid`, while `OooIexIssue.admissionReady` consumed
  that lane's classification payload to drive `ready`. Separately,
  `OOOD3S1Graph.fastWriteback.valid` consumed `fastWakeup.ready` and
  `fastCompletion.ready`, while `fastWakeup.valid` consumed
  `fastWriteback.ready`. The repair makes dispatch `valid/bits` depend only on
  the retained D3 group plus static port geometry, gives every presented IEX
  admission one common ready, and replaces the split public fast-result fork
  with one typed `FastResultTxn`; the retained `OooD3FastResultQueue` remains
  the only fast-result owner and ROB completion shares the same fire.
- Fix-round simulation-profile RED was 7/9: W2/W4/W6/W8 inherited the semantic
  template graph but overwrote `maxTemplateUops` with two, below
  `TemplateD3Constants.MaxRows` (28). The profile now inherits main semantic
  capability and only bounds physical capacities; the selector is 9/9 green.

## Changes

- Added the state-free `TOP` and its sole `EmitTOP` entry point. It instantiates
  one IFU, CTU, OOO, IEX, LSU, and DTU and routes typed recovery, memory,
  bootstrap, debug, system/CMD, LSU control, commit/trap, and observation
  channels.
- TOP itself contains no `Reg`, `Mem`, or `Queue`. The fix round removes the
  generic OOO/IEX cycle-cut queues. `OooFastResultAtomicPublish` performs one
  stateless common-fire fork from the existing retained fast-result owner to
  IEX writeback/wakeup and ROB completion.
- Replaced external `sizeBytes: UInt(4.W)` with typed log2-byte `MemorySize`;
  `Bytes64` is exactly value six. Updated IFU/LSU producers, tests, manifests,
  parameter verification, and external-interface documentation.
- Expanded `TOPIO` to one data-memory channel per LSU lane and the remaining
  typed platform endpoints. Trace inputs are always accepted; CMD remains a
  real backpressured ready/valid output.
- Added a no-oracle natural runner, four-lane sparse-memory responder,
  UART/finisher handling, boot map initialization, activation accounting, run
  manifest, dual benchmark wrapper, and VPI port-validation mode. The natural
  loop has configurable heartbeat and architectural-progress deadlock windows;
  zero disables either watchdog, while the port-validation fast path remains
  outside the natural loop and writes a `port_validation_pass` manifest after
  bootstrap validation succeeds.
- Removed the callable `LinxCore*Top` sources/emitters, their top-only specs,
  old trace and autonomous harness entry points, and stale benchmark callers.
  The old activation graph is test-scope only and no longer has an emitter.
- Cut the emitter, lint, owner-manifest, and cross-check dependency gates to
  `EmitTOP`/`TOP`.
- Simulation W2/W4/W6/W8 profiles retain the main CTU template capability,
  all public identity/tag/LSID widths, and the 2/1/2/2 IEX plus 2-load/2-store
  topology. Only physical ROB/IQ/cache/queue capacity is reduced. The
  centralized `OOOParams.storeCommitBufferEntries` is the minimum power of two
  covering `retireWidth * maxMemoryRequestsPerInstruction` in each simulation
  profile; main profiles retain their default capacity.
- IEX remains the sole Option-A author of each initial memory transaction and
  attempt. Full transaction value+generation crosses `StoreReservationTxn` and
  exact `StoreTransactionBindingTxn`; LSU owns only replay/rebind. ROB binding
  is fail-closed on the complete resident identity and memory-order metadata.
- W4 accepts both store dispatch/reservation lanes as one continuous prefix.
  IEX atomically forks each lane to LSU reservation and ROB binding. ROB accepts
  both distinct exact bindings in one cycle; the canonical STQ owner compacts
  both lanes into one atomic multi-beat reservation batch under its existing
  Prepare/Apply/Abort recovery fencing. No speculative ingress queue exists.
- Store authorization and translation classification are internal. A complete
  store-token batch has zero LSU visibility during prepare, is atomically
  captured into the OOO-owned buffer only on the common architectural commit
  fire, and drains to LSU exactly once afterward. Recovery cannot cancel an
  already accepted committed token.
- LSU refill responses preserve the complete cache line. Generic LSU and width
  profiles default to no PMA regions; the natural platform profile alone marks
  the UART and finisher pages as devices. Concurrent trace sources use a
  deterministic prefix packer and exact accepted/dropped event counts.
- `EmitTOP` emits profile metadata used by the natural testbench. Port
  validation initializes every configured P-map entry, uses the configured SP
  architectural tag, pulses bootstrap completion, and observes bootstrap
  readiness instead of relying on fixed W4/STID/GPR constants.

## Fix-round 1 root-cause closure

- The first controlled W4 run stopped at the first elaboration failure: the
  two STD lanes can reserve two memory beats each, but the simulation STQ had
  only two entries. RED reported an atomic reserve width of four against STQ
  capacity two. The minimum GREEN repair centralizes
  `stdPipes == storePipes` and requires
  `storeQueueEntries >= stdPipes * maxMemoryRequestsPerInstruction`.
  Simulation and activation W4 use four STQ entries; the two-entry commit queue
  and four-entry SCB/lower-memory ledger remain unchanged. The run stopped in
  9.684 seconds with 2,382,921,728-byte peak RSS and 105 artifact bytes.
- The second controlled W4 run passed the STQ gate and stopped at the next
  firtool failure: public `OOO` omitted both IEX store-binding Decoupled lanes.
  The minimum GREEN repair wires both lanes' `valid` and complete `bits` inward
  and `ready` outward with no Queue, register, or new state. The public OOO
  FIRRTL round-trip and canonical exactly-once commit selectors passed. The run
  stopped in 18.057 seconds with 4,148,314,112-byte peak RSS and 105 artifact
  bytes.
- The next controlled W4 run emitted and built the VPI model, then failed
  `bootstrap validation never observed bootstrapReady`. RTL dataflow was
  intact: TOP directly projects IEX ready, while IEX registers
  `bootstrapDone` on the rising edge where `bootstrapComplete` and the complete
  P-map coincide. The harness sampled ready before that edge and cleared
  completion without a post-edge sample. The new regression failed because no
  ready sample existed between the completion edge and completion clear. GREEN
  adds exactly one post-edge sample. The failed run took 796.478 seconds,
  peaked at 7,166,525,440-byte RSS, and retained 959,769,017 bytes.
- The same run exposed an independent artifact-form issue. The clock/reset-only
  wrapper required VPI access to nested CoreTOP ports, but global
  `--public-flat-rw` exported 222,287 variables. Generated C++ occupied
  546,208,679 bytes and two nonreusable precompiled headers occupied
  202,356,768 bytes. RED contracts rejected global public exposure and required
  linked PCH removal. GREEN uses `top_natural.vlt` to select only
  `CoreTOP` ports matching `io_*`, retains the working wrapper topology, and
  deletes PCH files after link. The runner already recreates `obj_dir`, so the
  removed PCH files had no incremental reuse.
- A temporary two-port SV microproof validated Verilator 5.044 selective VPI:
  the wrapper-path `io_write` deposit crossed a rising edge and appeared on
  `io_read`, while an internal `hidden` signal had no `varInsert`. It printed
  `selective-vpi-port-write=pass`. A direct-CoreTOP-top experiment was rejected
  because writing the module-side alias was overwritten by the top input;
  production therefore retains `CoreTOPHarness`. The temporary proof directory
  was deleted after the test.
- Simulation-only physical capacities were reduced without changing semantic
  widths, identity widths, lane counts, or main profiles. The literal RED was
  `(16,64,8,8,256) != (4,4,4,4,4)` for
  `(ITLB,L1I sets,miss,join,trace buffer)`. The GREEN table is:

  | Profile | Principal/trace width | ITLB | L1I sets | Miss | Join | Trace buffer |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | W2 | 2 | 4 | 4 | 4 | 4 | 4 |
  | W4 | 4 | 4 | 4 | 4 | 4 | 4 |
  | W6 | 6 | 4 | 4 | 4 | 4 | 4 |
  | W8 | 8 | 4 | 4 | 4 | 4 | 4 |

  Every profile retains `maxGroupsPerTransaction=8`,
  `performanceCounterCount=32`, `maxTemplateUops=32`, and
  ALU/BRU/AGU/STD/load/store lane counts `2/1/2/2/2/2`. The focused contract
  runs `ParamChecks.validate` and compares public STID, RID slot, ROB member,
  BID, P/T/U tag, LSID, transaction, and memory-identity widths with the main
  profile for W2/W4/W6/W8.

## Fix-round 2 root-cause closure

- Direct no-Queue D2-to-D3-to-IEX activation exposed the dual-store deadlock at
  the exact ROB binding boundary. IEX reconstructed only part of
  `MemoryOrderMeta` and unconditionally computed `yostSid = firstSid - 1`;
  the first store therefore underflowed the invalid payload while ROB retained
  canonical zero. The repair preserves `yostSid` and the youngest-load triplet
  in the private issue-row allocation and copies the complete public boundary.
  ROB continues to compare full identity and `memoryOrder.asUInt`; no comparator
  was weakened. The dedicated W4 regression proves two dispatch fires, two LSU
  reservations, and two exact ROB bindings.
- Simulation profiles no longer shorten the MDB failed-wait timeout. A literal
  W2/W4/W6/W8 RED observed `8 != 300`; GREEN inherits the main-profile value
  while leaving all physical capacity reductions intact.
- `LSUParams.physicalMemoryRegions` now defaults empty. The params-folder
  `NaturalPlatformParams` owns noncacheable, non-device normal RAM plus the
  UART `0x10000000` and finisher `0x10009000` device-region overrides, and
  EmitTOP applies them only for the simulation-natural selection.
- EmitTOP metadata now records the configured system-issue and retire widths.
  The natural runner requires and forwards both values. The C++ harness drives
  exactly `systemIssueLanes` ready pins; a missing configured pin is fatal, and
  the previous hard-coded eight-lane catch-and-break probe is gone.
- Natural execution accepts an optional observation-only `--commit-trace`
  JSONL sink. On each fired public `CommitTxn`, it serializes the bounded prefix
  fields `pc`, `insn`, `wb_valid`, `wb_data`, `mem_valid`, `mem_is_store`, and
  transaction value/generation; the observation never drives the DUT. The LR/SC cross-check now
  requests this trace, runs the existing `summarize_trace`, and fails on a
  nonzero natural-runner return code even when the emitted rows are valid.

## Verification

### Fix-round 3 exact committed-store observation

- RED proved that an ordinary committed SWI does not carry the STD payload on
  the public commit prefix while the accepted external `Write` carries the real
  nonzero STD data. The Python summarizer initially could not join those two
  observations. Its focused RED also covered a missing exact request, the
  right transaction value with the wrong generation, a duplicate exact
  request, and a nonzero natural-runner exit.
- The natural harness now emits separate bounded `commit` and
  `memory_request` JSONL events. Every accepted external `Write` records its
  exact transaction value/generation, address, data, and mask. The summarizer
  performs an order-independent exact value-plus-generation join, obtains
  ordinary-store data only from the accepted request, and rejects missing,
  generation-mismatched, or duplicate matches. SC still consumes the commit
  result and is not counted again as an ordinary store.
- The exact LSU regression first reproduced the RTL root cause:
  `storeMemory.bits.identity.value` was `0` instead of the IEX-authored commit
  transaction value `11`. LSU had zero-initialized the ROB-to-STQ commit token
  and copied only logical/owner fields, so the serialized transport exposed
  its private local counter. The repair preserves the IEX-authored transaction
  in an STQ-indexed backend sidecar, projects that identity on the external
  request, validates the exact external response, and maps it back to the
  serializer's internal replay transport identity. IEX remains the owner of
  transaction allocation/initial attempt; LSU only retains and rebinds it.
- GREEN used only
  `LSUIntegrationSpec -z translated_committed_store_projects_exact_transaction_data_and_mask`
  with the explicit arm64 firtool, jobs=1, and a 900,000,000-byte supervisor
  budget. Exactly one test passed, proving `Write`, transaction value `11`,
  generation `1`, address, nonzero STD data `0x1122334455667788`, and mask
  `0xff`. The run completed in 14.063 seconds, peaked at 3,464,871,936-byte
  RSS, and produced 115,111,958 bytes in the owned artifact directory.
- Fresh fix-round 3 checks passed: `Test/compile`; LR/SC Python 9/9; natural
  harness Python 9/9; runner shell syntax; Python bytecode compilation; C++
  retained-header stub syntax; and scoped `git diff --check`. External AVS
  execution remains `BLOCKED_EXTERNAL_ARTIFACT`; no external architectural
  pass is claimed. Fix-round 3 is internally verified and awaits independent
  re-review.

### Fix-round 4 natural RAM transport closure

- Independent review found that the fix-round 3 LSU test proved the serialized
  transport mechanics using a device translation, but the LR/SC workload data
  at `0x11000` still inherited generic `cacheable=true`. Its ordinary SWI would
  therefore use SCB `AcquireWrite` (command 3), while the natural harness
  correctly writes memory and records data only for serialized `Write`
  (command 1). Treating `AcquireWrite` as store data would be semantically
  false because the ownership request does not carry the STD payload.
- RED required a natural-platform normal-RAM attribute owner and failed because
  none existed. GREEN sets only the simulation-natural default memory
  attributes to readable/writable, noncacheable, and non-device. UART and
  finisher retain explicit noncacheable device overrides. Generic
  `LSUParams`, W2/W4/W6/W8 simulation profiles, public identity widths, and the
  IEX-authored transaction/initial-attempt boundary are unchanged.
- The real-command source contract proves that only command 1 is recorded as a
  `memory_request` and applied to sparse memory; command 3 is neither forged
  nor interpreted as a data write. Natural/LRSC Python passed 19/19. The sole
  focused Scala invocation was
  `SimulationParamProfilesSpec -z natural_platform_normal_ram_noncacheable_devices_mmio`
  with explicit arm64 firtool, jobs=1, and a 900,000,000-byte budget: 1/1
  passed in 11.309 seconds, peak RSS was 2,017,263,616 bytes, and it generated
  zero artifact bytes. Shell/Python syntax and scoped diff checks passed.
  Fix-round 4 is internally verified and awaits independent re-review; external
  AVS execution remains `BLOCKED_EXTERNAL_ARTIFACT`.

### Post-fix trace-schema cleanup

- Chisel natural `event:"commit"` rows no longer emit the constant-zero
  `mem_wdata` pseudo-field, and synthetic commit helpers/tests no longer create
  or require it. Exact ordinary-store data continues to come only from the
  accepted `event:"memory_request"` joined by transaction value/generation.
- Legacy/QEMU rows remain explicitly distinguished by the absence of
  `event:"commit"`; only that direct-row path may consume `mem_wdata`.
- With `PYTHONDONTWRITEBYTECODE=1`, the focused natural plus LR/SC Python suites
  passed 19/19. Retained-header C++ syntax and scoped diff checks passed; the
  temporary stub and task-owned Python bytecode were removed. No Chisel, SBT,
  firtool, or Verilator command ran for this cleanup.

### Bounded parallel natural build control

- Behavioral RED invoked the real runner self-test. The default manifest had
  no `build_jobs`, explicit `--build-jobs 3` failed as an unknown option, and
  invalid `0` lacked the required positive-integer diagnostic.
- GREEN adds one bounded control: default `build_jobs=2`, while explicit `1`
  remains the low-memory fallback. `--build-jobs N` accepts only positive
  decimal integers, is passed exactly to Verilator `--build-jobs`, and is
  recorded in both self-test and every natural manifest produced before runner
  exit, including a nonzero harness result. No profile,
  capacity, width, or generated-file type changed.
- With `PYTHONDONTWRITEBYTECODE=1`, focused runner tests passed 12/12, including
  real CLI invocations for default 2, explicit 3, and invalid 0. `bash -n` and
  scoped diff checks passed. No Chisel, SBT, firtool, or Verilator command ran.
  Skill evolution: `no-update`; existing LinxCore workflow guidance already
  covers bounded build-resource controls.

- Fresh fix-round `Test/compile` passed with the explicit native arm64 firtool
  path. The generated JSON/Markdown interface manifest is current;
  `InterfaceManifestSpec` plus `TopInterfaceSpec` passed 18/18.
- Focused behavior passed: simulation profiles 9/9; all public CTU template
  forms across W2/W4/W6/W8 3/3 selected; fast-result peer-block atomicity 1/1;
  commit-token common-fire capture/backpressure/exactly-once 1/1; W4 dual-store
  dispatch 1/1; W4 canonical dual-lane STQ reservation 1/1; exact/max-generation
  and dual-lane ROB binding 3/3 selected; STQ recovery Prepare/Abort 2/2 plus
  peer-STID Apply 1/1; IEX lane-1 transaction sharing 1/1; canonical graph
  common commit exactly once 1/1; full nonuniform refill line 1/1; concurrent
  trace overflow 2/2 selected; derived device-store request 2/2 selected.
- Active natural/cutover/LRSC/IPC, production-owner-manifest, and NDF Python
  checks passed 72/72, including the single-emit VPI/lint reuse contract and
  heartbeat/deadlock CLI/source contract. Runner shell syntax and the natural
  testbench C++ syntax check passed. A deadlock records
  `terminal_status=deadlock` and returns nonzero.
  `git diff --check` passed.
- The final focused parameter suite passed 10/10 with the explicit arm64
  firtool path. Natural-harness Python tests passed 7/7; runner shell syntax,
  retained-header C++ syntax, and diff checks passed.
- Exactly one final controlled W4 verification used jobs=1, 30-second
  heartbeat, 900-second stall, 1800-second wall, and a 900,000,000-byte
  artifact budget with the explicit arm64 firtool. EmitTOP completed; the VPI
  model built from 5,812.549 MB of sources in 192 modules into 279.872 MB and
  122 C++ files in 245.850 seconds. Runtime stdout reported
  `top-vpi-port-validation=pass` and
  `top-vpi-bootstrap-validation=pass entries=24`.
- Lint completed from 5,812.743 MB of sources in 192 modules into 181.870 MB
  and 92 C++ files in 34.620 seconds. Supervisor result was
  `return_status=0`, `child_status=0`, `reason=child-exit`, elapsed 321.041
  seconds, peak RSS 5,755,518,976 bytes, and 377,134,902 artifact bytes in 593
  files. No PCH files remained; artifact headroom was 522,865,098 bytes.
- The final manifest records `terminal_status=port_validation_pass`, 30 cycles,
  zero loaded bytes, `comparison_kind=none`, no instruction or commit oracle,
  zero captured mismatch, and expected zero IFU/CTU/OOO/IEX/LSU activation for
  the port-validation fast path. Profile metadata records `stidCount=1`,
  `gprArchRegs=24`, `spAtag=1`, `traceWidth=4`, `loadLanes=2`, and
  `storeLanes=2`.
- Scalar linx-avs natural smoke: `BLOCKED_EXTERNAL_ARTIFACT`. Canonical source
  entry is `/Users/zhoubot/linx-isa/avs/compiler/linx-llvm/tests/run.sh`; its
  pinned compiler path `compiler/llvm/build-linxisa-clang/bin/clang` is absent,
  no executable clang is present under `compiler/llvm`, and `avs` contains
  zero `.elf` files. Task 18 did not build LLVM or substitute another ELF.
  Consequently no architectural mismatch result is claimed; natural manifests
  explicitly record `comparison_kind=none`. No CoreMark or Dhrystone natural
  pass is claimed from the port-validation result.
- Fix-round 2 focused verification used the explicit native arm64 firtool.
  `OOOIEXDirectDualStoreBoundarySpec` passed 1/1 with two dispatch,
  reservation, and binding fires plus zero exact metadata mismatches
  (`return_status=0`, elapsed 66.899 seconds, peak RSS 5,635,112,960 bytes).
  `SimulationParamProfilesSpec` passed 13/13 (`return_status=0`, elapsed 5.505
  seconds, peak RSS 970,555,392 bytes, zero artifacts). `Test/compile` passed
  without a firtool override. The combined natural harness and LR/SC Python
  suites passed 14/14; runner shell syntax, Python bytecode compilation, C++
  stub syntax, `git diff --check`, and the NDF local-reference check passed
  (`clauses=157`, `l1_must=74`, `verified=83`, `open_questions=0`). No full TOP Verilator run was started in this
  round. The AVS smoke remains `BLOCKED_EXTERNAL_ARTIFACT`, so no external
  architectural result is claimed.

### Fix-round 5 Option-A structural replay and bounded W2 evidence

- Option A remains the frozen ownership contract: IEX allocates the memory
  transaction and initial load attempt; LSU owns every later replay/rebind.
  A private TOP probe found one violation in the integrated structural-forward
  path: `LoadStructuralBlockPolicy` drove LIQ `structuralRetry` directly. LIQ
  advanced to attempt generation 2 while the retained IEX terminal metadata
  remained at generation 1, so the generation-2 result failed the exact
  completion match and left its ROB destination unresolved.
- RED required the emitted public LSU to carry structural replay through
  `loadRepick` and return it through `loadRebindApply`, and rejected a direct
  policy-to-LIQ retry connection. GREEN adds the typed structural disposition
  and exact wait-store key to `LoadRepickTxn`/`LoadReissueTxn`; the IEX metadata
  rebind, selected LIQ mutation, and old-attempt cancel now share one common
  fire. Ordinary replay retains the existing `attemptRebind` path, while a
  structural replay selects `structuralRetry` only after the IEX round trip.
  No physical LIQ row is exposed on the public interface.
- Focused evidence with a 2048 MiB SBT heap: structural integration 1/1,
  TOP interface contract 13/13, and IEX/LSU integration 2/2 passed. The TOP
  contract checks every structural wait-store field and width. Temporary
  private-root C++ probes were removed before the fresh build.
- The fresh natural W2 run used only width 2, Verilator build jobs 2, disabled
  waveform/coverage generation, a 20,000-cycle ceiling, and a 5,000-cycle
  deadlock window. Verilator built 5,451.550 MB of sources into 266.283 MB in
  681.959 seconds; the owned temporary directory reached about 438 MiB. The
  run advanced to 19 OOO commits and terminated `deadlock` at cycle 5,460
  after last progress at cycle 460. Activation was IFU 93, CTU 2, OOO 19,
  IEX 8, and LSU 8. This is forward-progress evidence over the previous
  attempt-generation failure, not an architectural or benchmark pass.
- W2/W4/W6/W8 public width identities and the fixed 2/1/2/2 execution plus
  2-load/2-store topology remain unchanged. Only simulation capacities,
  default SBT heap (2048 MiB), full-build concurrency (2), and watchdog bounds
  are reduced. No full W4/W6/W8 model was generated in this round.
- After evidence capture, the owned W2 model, Chisel simulation output, SBT
  targets, BSP metadata, PCH/object files, and Python bytecode caches were
  removed. No ignored Chisel/SBT/Verilator output remains in the repository.

### Fix-round 6 branch feedback, BROB-bounded commit, and rename floor

- The frozen memory ownership decision is explicitly Option A: IEX allocates
  the transaction plus initial attempt, while LSU owns replay/rebind only.
  Branch terminal publication now preserves fetch/prediction identity through
  DEC and IEX, validates it on one typed B-SIDE transaction, sends the resolved
  prediction to IFU, and emits exact ROB recovery atomically on mismatch.
- Natural W2 first exposed an unsupported `C_SEXT_W`; the ALU now implements
  the byte/half/word sign-extension family. It next exposed branch-control
  outputs tied permanently not-ready in IEX. Removing those sinks and adding
  one depth-one queue per terminal lane breaks the bctrl/recovery ready cycle
  without dropping branch validation. The prior `C_SETC_EQ` row now reaches
  terminal publication and ROB completion.
- The next exact deadlock had five completed ROB rows but no public commit.
  Read-only state showed `ROB valid/count=1/2` and all release owners ready
  except BROB. The prefix crossed BID 3 to BID 4, while BROB correctly accepts
  release only for its current head block. RED observed count two; GREEN makes
  ROB commit preview stop at BID/BROB-generation boundaries. The focused test
  also proves the next block appears after the first prefix applies.
- A fresh width-2 natural build used a 2048 MiB SBT heap, one Verilator build
  job, no lint/waveform/coverage, a 20,000-cycle ceiling, and a 3,000-cycle
  deadlock window. Verilator built 5,972.485 MB of sources in 199 modules into
  284.105 MB and 114 C++ files in 492.633 seconds, allocating 2,822.375 MB.
  Natural execution advanced from the previous 21-commit gate to 25 commits
  and then reported the next independent deadlock at cycle 3,472 after progress
  at cycle 472. Activation was IFU 93, CTU 2, OOO 25, IEX 10, and LSU 8.
- The final retained row was a completed early `opcode 0x12` in an open BID 5.
  The next D2 group was valid but rename was not ready: the aggressively
  reduced W2 P-MapQ was 4/4 full and only one of 32 physical GPRs remained
  free, so the younger suffix could not rename to close the block. Simulation
  rename capacity now has a 16-row forward-progress floor and at least 16
  speculative physical GPRs beyond the 24 architectural registers. This is
  still one quarter of the main MapQ depth; public W2/W4/W6/W8 identities and
  2/1/2/2 plus 2-load/2-store topology remain unchanged.
- Focused verification: branch feedback 2/2, interface plus profile 28/28,
  terminal identity 1/1, ALU sign extension 1/1, and the BROB-bounded ROB
  preview 1/1 passed. After the rename-floor correction,
  `SimulationParamProfilesSpec` passed 16/16 with zero Chisel simulation
  artifacts, a 536,870,912-byte budget, one job, and 1,614,921,728-byte peak
  RSS. The 25-commit model predates the rename-floor correction; no benchmark
  pass is claimed from it.

### Fix-round 7 Option-A IQ progress and low-artifact W2 closure

- The forced decision remains Option A: IEX allocates the memory transaction
  and initial attempt; LSU owns replay/rebind. No ownership field or allocation
  point moved in this round.
- Resource validation was intentionally limited to W2, a 2048 MiB SBT heap,
  one outer test/build job, a 20,000-cycle ceiling, and a 3,000-cycle deadlock
  window. W4/W6/W8 were not generated. The simulation rename/ROB capacity
  remains at the previously proven 16-row forward-progress floor; reducing it
  further would reintroduce the already reproduced open-block deadlock.
- The first W2 deadlock retained two ALU admissions in bank 0 while bank 1 was
  empty because each ALU dispatch lane was pinned to a lane-derived bank mask.
  RED rejected a third admission after filling bank 0. GREEN permits every ALU
  lane to select any eligible bank and proved spill into bank 1. The natural
  run then increased IFU requests from 73 to 81 and trace events from 37 to 48,
  but architectural commit remained 27, so this was progress rather than a
  benchmark pass.
- A temporary read-only root probe identified the next exact owner: ROB
  `rid=3/member=1`, ALU transaction 27, waited on P source `ptag=26`, generation
  1. Its physical-file owner was valid and ready with the same stid/generation,
  but the producer epoch was 5 and consumer epoch was 7. Resident committed
  and speculative wakeup matching incorrectly required equal frontend epochs
  for P operands. RED/GREEN now proves both cross-epoch wakeup kinds; P uses
  stid plus PTag generation, while T/U retain epoch plus local-sequence checks.
- The cross-epoch repair alone left the same natural state because a file write
  and row admission can cross one edge after the one-cycle wakeup pulse. A
  second RED changed the exact operand-file readiness after the row became
  resident and observed readiness remain zero. GREEN makes resident rows
  converge from the complete centralized ready identity: P checks
  `{stid, ptag, generation}` and T/U check
  `{stid, epoch, localTag, localSequence}`. Recovery-fenced rows remain blocked.
- Focused W2 evidence passed `Test/compile` and the one selected
  `IEXPrivateIngressSpec` test covering committed wakeup, speculative-load
  wakeup, and post-admission file-readiness convergence. The final GREEN ran
  1/1 in 211 seconds; the supervisor measured 703,081,306 artifact bytes and
  9,702,883,328-byte peak RSS. ChiselSim output was deleted immediately after
  capture. Temporary C++ root probes were removed before the fresh TOP build.
- A further RED showed that a source could become exactly ready after the IQ
  query or P1 capture while the saved speculative-ready snapshot had already
  expired. GREEN refreshes exact file readiness both when forwarding the IQ
  query and in I1. The I1 test preserves the captured P identity, then makes
  `{stid, ptag, generation}` ready and proves that the lane issues an RF read;
  T/U use `{stid, epoch, localTag, localSequence}`. The focused I1 test passed
  1/1 in 12 seconds with 153,893,985 artifact bytes and about 3.0 GiB peak RSS.
  Its fixture was also aligned with the current three D3 PC-write ports and
  four PC banks. `Test/compile` passed after the final static-index cleanup.
- The final W2 TOP build used only a 2048 MiB SBT heap and one build job. It
  emitted 199 modules / 6,653.193 MB of sources, generated 123 C++ files /
  336.545 MB, and completed in 439.991 seconds with 3,474.516 MB allocated by
  the build tool. The bounded run used 20,000 maximum cycles, 2,000-cycle
  heartbeat, and 3,000-cycle deadlock detection. W4/W6/W8 were not generated.
- Natural execution still stopped at 27 commits at cycle 3,473 after progress
  at cycle 473; therefore no benchmark pass is claimed. A read-only probe of
  that exact retained model proved that the readiness repair worked:
  transaction 27 left its IQ/I1 row and occupied the ALU E1 transfer slot,
  while transaction 36 advanced behind it into I2.
- The next independent owner is ALU execution coverage. Transaction 27 is
  normal scalar `OP_SLL` (`0x119`) with two P sources in the exact recipe, but
  `OooIexAluPipeline.SupportedOpcodes` does not include `OP_SLL`; its expected
  source count remains zero, so exact E1 admission is rejected. This is the
  next implementation gap, not a reason to weaken recipe validation.
- ChiselSim's outer job count was one, but its internal Verilator invocation
  still used `-j 0`; the 9.7 GiB focused-test peak is therefore a remaining
  resource-control gap. Final validation artifacts are removed after evidence
  capture; the repository retains only source, tests, and reports.

### Fix-round 8 scalar ALU coverage under the low-resource W2 iteration profile

- The architectural ownership decision remains Option A: IEX allocates the
  memory transaction and initial attempt; LSU owns replay and rebind. This
  round changes scalar ALU coverage only and does not move transaction or
  attempt ownership.
- Iteration stayed on the low-resource W2 profile: W2 only, a 2048 MiB SBT
  heap, one outer build job, 20,000 maximum cycles, a 2,000-cycle heartbeat,
  and a 3,000-cycle deadlock window. W4/W6/W8 were not generated. The existing
  16-row simulation rename/ROB floor was retained because the earlier 4-row
  profile was already proven unable to close an open block.
- Those bounds are now the natural runner defaults instead of command-line
  discipline only: width 2, one build job, 20,000 maximum cycles, 2,000-cycle
  heartbeat, and 3,000-cycle deadlock detection. Every value remains explicitly
  overridable for final benchmark and width-matrix gates. RED observed the old
  W4/two-job/million-cycle defaults; GREEN passed four focused Python checks,
  including the default self-test manifest and explicit parallel override.
- A directed RED rejected `OP_SLL` in E1. The implementation now covers the
  complete base scalar shift family rather than special-casing the observed
  opcode: `SLL/SRL/SRA`, `SLLI/SRLI/SRAI`, `SLLW/SRLW/SRAW`, and
  `SLLIW/SRLIW/SRAIW`. Register shifts require exactly two sources, immediate
  shifts exactly one, shift amounts use the architectural 6-bit or 5-bit mask,
  and word results are sign-extended. The 12-case focused GREEN passed 1/1 in
  11.145 seconds (33.099 seconds total), retained 117,403,177 artifact bytes
  before cleanup, and measured 3,694,411,776-byte peak RSS.
- The fresh W2 TOP elaboration completed in approximately 649 seconds. Its
  interrupted outer wrapper left both large generated objects intact; a
  single-job make recovered and linked the exact model without regenerating
  RTL. The generated directory was approximately 534 MB before cleanup.
- Natural execution reached a heartbeat at cycle 2,000 with 36 commits, 81
  instruction requests, 9 data requests, and 54 trace events. It stopped at
  cycle 3,497 after last progress at cycle 496. Activation was IFU 116, CTU 5,
  OOO 36, IEX 14, and LSU 9. Commit progress from 27 to 36 proves the previous
  `OP_SLL` owner is resolved, but the terminal state remains deadlock and no
  benchmark pass is claimed.
- A temporary read-only root probe identified the next exact retained owner as
  ROB `rid=12/member=0`, ALU transaction 36, `OP_HL_LUI` (`0xa4`), with zero
  sources. A younger retained slot held `OP_LUI` (`0x10c`). The probe was
  removed after capture and is not part of the delivered harness.
- Frontend decode already publishes a normalized immediate for `OP_LUI`,
  `OP_HL_LUI`, `OP_HL_LIS`, and `OP_HL_LIU`; IEX must consume that value
  without re-decoding instruction bits. A directed RED observed `OP_LUI`
  rejected in E1. GREEN adds the four zero-source load-immediate opcodes and
  returns the normalized immediate. The focused test passed 1/1 in 5.964
  seconds (18.279 seconds total), retained 117,262,350 artifact bytes before
  cleanup, and measured 3,677,093,888-byte peak RSS.
- A second fresh W2 model containing the load-immediate repair elaborated in
  72 seconds with cache reuse. Verilator built 6,653.496 MB of sources in 199
  modules into 123 C++ files / 336.590 MB in 470.597 seconds on one thread;
  the complete disposable build directory occupied 494 MB at evidence
  capture. Natural execution advanced from 36 to 37 commits, with activation
  IFU 114, CTU 5, OOO 37, IEX 14, and LSU 9. It stopped at cycle 3,488 after
  last progress at cycle 488; terminal status therefore remains deadlock and
  no benchmark pass is claimed. The trace contains `OP_HL_LUI` in IEX after
  the repair, confirming the previously rejected owner advanced.
- A proposed temporary root probe would have caused make to rebuild the large
  generated translation unit because of PCH dependency invalidation. That
  rebuild was stopped immediately, and the temporary source probe was removed.
  The next loop must locate the new exact owner without regenerating RTL merely
  for diagnostics. No W4/W6/W8 generation is warranted until W2 advances.
  ChiselSim's internal Verilator `-j 0`, PCH invalidation, and the generated
  model's large translation unit remain resource-control gaps despite the
  one-job outer wrappers.

### Fix-round 9 compact memory decode and smaller W2 IQ

- Option A remains authoritative and unchanged: IEX allocates the memory
  transaction and initial attempt; LSU owns replay, reissue, repick, rebind,
  and every subsequent attempt. Compact-memory support reuses that ownership
  boundary and does not introduce a second allocator.
- A catalog regression exposed all four compact memory forms as generic
  compressed ALU operations. `C.LWI/C.LDI` now generate load recipes and
  `C.SWI/C.SDI` generate store recipes while their stable opcode IDs remain
  unchanged. D1 normalizes the encoded compact offset exactly once according
  to access size, compact loads allocate a T destination, and compact stores
  consume the encoded base plus T source.
- The IEX-to-LSU-to-IEX return path now preserves GPR/T/U destination kind and
  its matching PTag/TTag/UTag rather than rewriting every load destination to
  GPR. Focused regressions pass for frontend offset preservation, all four D1
  compact-memory forms, AGU T destination admission, LIQ T destination
  binding, and terminal T metadata. The generated catalog form suite passes
  8/8 and the deterministic recipe audit passes 689 entries.
- The fresh low-resource W2 natural build used one configuration, one build
  job, and a 2048 MiB SBT heap. Verilator processed 199 modules, reported
  6,662.148 MB of generated source, emitted 123 C++ files / 336.664 MB, and
  completed in 839.007 seconds. The disposable directory occupied 532 MB.
  Natural execution advanced from 37 to 40 commits, reached a cycle-2,000
  heartbeat with 89 instruction and 10 data requests, and stopped at cycle
  3,522 after last progress at cycle 522. Activation was IFU 135, CTU 5, OOO
  40, IEX 21, and LSU 10. This proves the compact-memory owner advanced; the
  terminal state remains deadlock and no benchmark pass is claimed.
- Static ELF decode places the next architectural instruction after the last
  committed `OP_C_SEXT_W` at `pc=0x10100`: `OP_C_SETC_NE`, followed by the
  next block boundary. That is the next independent execution/control owner
  to diagnose; this inference is not yet a retained-state root probe.
- W2 no longer retains an unnecessary second row in each scalar IQ bank. Its
  bounded capacity changes from four total entries to two total entries while
  preserving both banks, the 2-ALU/1-BRU/2-AGU/2-STD/1-system/1-CMD physical
  topology, two load/two store pipes, rename/ROB forward-progress floors, and
  every fixed identity width. The parameter regression first failed 4 != 2
  and then passed all 16 cases after the capacity change. A fresh W2 RTL-only
  emission completed in 71 seconds and reduced emitted SV from 63,896,864 to
  55,722,033 bytes (12.8%) and from 198 to 194 files. A size-one Vec selector
  removes the 21 dynamic-index warnings introduced by one row per bank; only
  the two pre-existing trace-enum cast warnings remain.

### Fix-round 10 exact local-owner epoch and bounded W2 progress

- Option A remains frozen: IEX allocates the memory transaction and initial
  attempt; LSU owns replay, reissue, repick, rebind, and every subsequent
  attempt. No transaction or attempt owner moved in this round.
- The retained W2 `OP_C_SETC_NE` row named T tag 4 / sequence `{4, 0}`. A
  read-only probe showed that exact T-file row allocated and ready with owner
  epoch 9, while issue incorrectly qualified it with the consumer frontend
  epoch 11. The former producer-commit reclamation hypothesis was disproved by
  a fresh natural run and its temporary code and tests were removed.
- Rename now carries the producer allocation epoch as part of every T/U source
  identity. TURename retains it in the ordered MapQ, RENU preserves it across
  D2-D3, and IEX uses it for admission readiness, resident wakeup, bypass,
  P1 readiness, and T/U operand-file reads. The consuming row epoch remains
  unchanged for recovery and row ownership.
- Directed RED/GREEN evidence passed the TURename producer-to-consumer epoch
  case 1/1, the RENU seam case 1/1, and the IEX exact local-owner readiness
  case 1/1. The latter passed on W4; a W2 rerun exposed only ChiselSim access
  to legal zero-width minimum-profile fields, so the shared test helpers now
  avoid touching invalid payloads and derive query geometry from the DUT.
- Iteration remained W2-only with a 2048 MiB SBT heap, one build job, 20,000
  maximum cycles, a 2,000-cycle heartbeat, and a 3,000-cycle deadlock window.
  The two-entry scalar IQ profile was retained. Verilator processed 196
  modules / 5,829.528 MB of sources, emitted 116 C++ files / 291.136 MB, and
  completed in 485.346 seconds on one thread. The disposable directory was
  approximately 466 MB before cleanup; W4/W6/W8 TOP models were not generated.
- Natural execution advanced from 40 to 46 commits. `OP_C_SETC_NE` at
  `pc=0x10100` reached IEX at cycle 235, proving the exact local-owner epoch
  repair removed the previous deadlock. The run stopped at cycle 3,273 after
  last progress at cycle 273, with 89 instruction requests, 12 data requests,
  and 74 trace events. No benchmark pass is claimed.
- The new independent retained head is transaction 46 at `pc=0x10110`, opcode
  7 (`OP_BSTART_FP_FALL`), instruction ID 66, epoch 12. The BRU has transaction
  49 in flight and transaction 57 resident on T tag 6 / sequence `{6, 0}` with
  that source not ready. This control/BROB/BCTRL owner needs a separate exact
  diagnosis; the evidence does not justify increasing W2 capacity.

## Initial pre-review resource evidence

- The following measurements belong to the initial implementation before the
  fix-round review; they are not claimed as final fix-round evidence.
- Focused selectors used repository wrappers, jobs=1, native arm64 firtool,
  and a 900,000,000-byte artifact budget. All reported zero retained artifact
  bytes. Peak RSS: TOP topology SV 6,353,977,344 bytes; width CHIRRTL
  4,903,763,968 bytes; interface 999,899,136 bytes; manifest 1,027,358,720
  bytes.
- Initial pre-review bounded W4 emit: 40 seconds, max RSS 3,859,513,344 bytes.
- Verilator VPI build: 646.9 seconds, max RSS 6,536,298,496 bytes, jobs=1.
- Verilator lint: 12.6 seconds, max RSS 3,059,433,472 bytes.
- Verilator 5.044 reserves the identifier `TOP` internally. The lint/harness
  path therefore makes a simulation-only textual copy named `CoreTOP` and
  wraps it with `CoreTOPHarness`; the delivered emitted module remains `TOP`.

## Concerns and review handoff

- All fix-round 4 review findings within repository control have focused
  dynamic or static closure. The only external workload gap is the unavailable canonical
  AVS compiler/ELF described above; no substitute architectural oracle is
  claimed. Fix-round 4 implementation and focused verification are complete;
  Task 18 remains awaiting independent re-review and is not finally approved.
- Skill evolution: `no-update`; the LinxCore workflow already covers this
  topology/interface/harness cutover and no reusable gap was found.
