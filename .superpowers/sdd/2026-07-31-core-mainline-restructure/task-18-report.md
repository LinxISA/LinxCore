# Task 18 implementation report

## Baseline and audit

- Baseline: `e0c90c2c75942b01b3a175fbdc916a04cc83b0c2`, clean assigned branch.
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
  IEX ready. The boundary queues were moved into the OOO owner.
- `TOPWidthProfilesSpec` initially rejected W2 because its two-entry directed
  fetch buffer could not accept the fixed four-instruction I-side ingress.
  The directed profile now keeps a four-entry minimum without changing any
  public width.
- `InterfaceManifestSpec` initially rejected the checked-in JSON/Markdown
  because they lacked `top_io` and the typed memory-size leaves.
- `tests.test_top_natural` initially failed because the Task-18 runner and C++
  harness did not exist.

## Changes

- Added the state-free `TOP` and its sole `EmitTOP` entry point. It instantiates
  one IFU, CTU, OOO, IEX, LSU, and DTU and routes typed recovery, memory,
  bootstrap, debug, system/CMD, LSU control, commit/trap, and observation
  channels.
- Added one-entry retained OOO-owned cuts on payload-sensitive OOO/IEX
  channels. TOP itself contains no `Reg`, `Mem`, or `Queue`.
- Replaced external `sizeBytes: UInt(4.W)` with typed log2-byte `MemorySize`;
  `Bytes64` is exactly value six. Updated IFU/LSU producers, tests, manifests,
  parameter verification, and external-interface documentation.
- Expanded `TOPIO` to one data-memory channel per LSU lane and the remaining
  typed platform endpoints. Trace inputs are always accepted; CMD remains a
  real backpressured ready/valid output.
- Added a no-oracle natural runner, four-lane sparse-memory responder,
  UART/finisher handling, boot map initialization, activation accounting, run
  manifest, dual benchmark wrapper, and VPI port-validation mode.
- Removed the callable `LinxCore*Top` sources/emitters, their top-only specs,
  old trace and autonomous harness entry points, and stale benchmark callers.
  The old activation graph is test-scope only and no longer has an emitter.
- Cut the emitter, lint, owner-manifest, and cross-check dependency gates to
  `EmitTOP`/`TOP`.

## Verification

- `TOPSpec`: 2/2 passed through `emitSystemVerilog` and native firtool.
- `TOPWidthProfilesSpec`: W2/W4/W6/W8 CHIRRTL, 4/4 passed.
- `TopInterfaceSpec`: 13/13 passed.
- `InterfaceManifestSpec`: 5/5 passed; generated projections are current.
- `build_chisel.sh`: `Test/compile` passed.
- `check_ndf_profile.py docs/spec`: 157 clauses, 74 L1 must, 83 verified,
  zero open questions.
- owner manifest checker: 27 closed owners, 22 classified emitters, six
  declared adapters.
- Python cutover/owner/harness suite: 50/50 passed.
- W4 bounded `EmitTOP`: passed. Verilator lint passed over all 191 emitted
  modules. The generated VPI testbench compiled and reported
  `top-vpi-port-validation=pass`.
- Scalar linx-avs natural smoke: `BLOCKED_EXTERNAL_ARTIFACT`. Canonical source
  entry is `/Users/zhoubot/linx-isa/avs/compiler/linx-llvm/tests/run.sh`; its
  pinned compiler path `compiler/llvm/build-linxisa-clang/bin/clang` is absent,
  no executable clang is present under `compiler/llvm`, and `avs` contains
  zero `.elf` files. Task 18 did not build LLVM or substitute another ELF.
  Consequently no architectural mismatch result is claimed; natural manifests
  explicitly record `comparison_kind=none`.

## Resource evidence

- Focused selectors used repository wrappers, jobs=1, native arm64 firtool,
  and a 900,000,000-byte artifact budget. All reported zero retained artifact
  bytes. Peak RSS: TOP topology SV 6,353,977,344 bytes; width CHIRRTL
  4,903,763,968 bytes; interface 999,899,136 bytes; manifest 1,027,358,720
  bytes.
- Final bounded W4 emit: 40 seconds, max RSS 3,859,513,344 bytes.
- Verilator VPI build: 646.9 seconds, max RSS 6,536,298,496 bytes, jobs=1.
- Verilator lint: 12.6 seconds, max RSS 3,059,433,472 bytes.
- Verilator 5.044 reserves the identifier `TOP` internally. The lint/harness
  path therefore makes a simulation-only textual copy named `CoreTOP` and
  wraps it with `CoreTOPHarness`; the delivered emitted module remains `TOP`.

## Concerns and review handoff

- The only unmet workload gate is the explicit external-artifact blocker above.
- Generated build/target/output directories are removed before handoff.
- Skill evolution: `no-update`; the LinxCore workflow already covers this
  topology/interface/harness cutover and no reusable gap was found.
