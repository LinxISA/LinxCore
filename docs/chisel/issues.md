# Chisel Issues

## CHISEL-ISSUE-011: CoreMark PC Filters Pass QEMU Guards Without RTL Launch State

Status: open

Impact:

- A PC-filtered CoreMark QEMU preflight can pass exact store/load PC guards but
  still be illegal for generated RTL because the reduced preview starts after
  hidden predecessor rows have produced architectural register state.
- R626 demonstrated the failure mode on `pc=0x4000d7e6`: QEMU expected
  `rd1=1342110568`, but the generated RTL started from reset/RF preload state
  and reported `rd1=18446744073709551608`.
- R628 scanned all 12 narrow exact store-before-load PC-filter candidates from
  the expanded R625 report and found no generated-RTL-ready shape.

Evidence:

- `generated/r626-replay-liq-pc-filter-activation-report/report/replay_liq_pc_filter_activation_report.json`
  classifies the first generated-RTL attempt as failed before manifest
  generation.
- `generated/r627-replay-liq-pc-filter-state-seed-search/report/pc_filter_preflight_search.json`
  adds `state_seed_audit.status="insufficient"` for the top candidate.
- `generated/r628-replay-liq-pc-filter-state-seed-scan12/report/pc_filter_preflight_search.json`
  reports `trial_count=12`, `pass_count=1`, `state_seed_ready_count=0`, and
  `generated_rtl.status="blocked"`.

Current mitigation:

- Require both exact memory-PC guards and `state_seed_audit.status="ready"`
  before promoting any PC-filtered QEMU-only preflight to generated RTL.
- Use `--stop-on-generated-ready` for broader PC-filter searches.
- Prefer checkpoint/state replay or a legal natural workload shard from reset
  for the next CoreMark replay-LIQ activation attempt.

## CHISEL-ISSUE-001: Local JVM/SBT Toolchain Missing

Status: closed

Impact:

- The initial Chisel compile, Scala test, and Verilog emit were blocked until a
  local JDK and `sbt` were installed.

Evidence:

```bash
bash tools/chisel/build_chisel.sh                 # pass
bash tools/chisel/run_chisel_tests.sh --only ROBID # pass
bash tools/chisel/emit_verilog.sh                 # pass
```

Resolution:

- Installed Homebrew `openjdk@17` and `sbt`.
- Added `tools/chisel/chisel_env.sh` so wrappers set `JAVA_HOME` to Homebrew
  `openjdk@17` when `JAVA_HOME` is unset.
- Updated the Chisel project to Scala `2.13.17` to match the resolved
  dependency graph.

## CHISEL-ISSUE-002: SBT Server Socket Race Under Parallel Invocations

Status: open

Impact:

- Running two SBT-backed wrappers at the same time can race the SBT 2 server
  socket and produce `Connection refused`.

Evidence:

- A parallel invocation of `run_chisel_tests.sh --only ROBID` and
  `run_chisel_rob_bookkeeping.sh --robid-only` produced an SBT client
  `Connection refused` error, while the same ROBID gate passed when rerun
  sequentially.
- A 2026-06-28 parallel invocation of `build_chisel.sh` and
  `run_chisel_tests.sh --only CommitTrace` reproduced the same SBT client
  `Connection refused` failure; `run_chisel_tests.sh --only CommitTrace` passed
  when rerun sequentially.

Current mitigation:

- Run SBT-backed Chisel gates sequentially.
- Wrappers use `--batch --no-colors` for CI-like output, but this does not
  prove parallel server use is safe.

## CHISEL-ISSUE-003: CSEL Model/QEMU Source-Order Divergence

Status: resolved and promoted through the R139 live CoreMark prefix

Impact:

- The R136 reduced CoreMark live fetch/RF/ALU gate passes through 1620 raw QEMU
  rows, then the 1660-row probe reaches `OP_CSEL` at `pc=0x40005d32`.
- LinxCoreModel and Sail select `SrcL` when `SrcP != 0`; the old QEMU
  translator selected `SrcR` when `SrcP != 0`.
- LinxCore RTL must keep the model/Sail behavior and reject any downstream
  regression that silently restores the old QEMU source order.

Evidence:

- LinxCore `21c630e0bfb024446b0beb378eecfabbbbadbb7f`.
- Local LinxCoreModel `1993e4e749403824a4908548baf77d5e15117068`.
- Fetched `origin/SuperScalarModel` `704a779` preserves the same model
  `CSEL` true-to-`SrcL` behavior.
- Sail source:
  `isa/sail/model/execute/execute.sail:1118` documents
  `exec_csel` as `SrcP != 0` selecting `SrcL`.
- LinxCoreModel source:
  `model/LinxCoreModel/isa/calculate/compound/Compound.cpp:7` decodes
  `srcP = srcs[0]`, `srcL = srcs[1]`, `srcR = srcs[2]`, and writes `srcL`
  when `srcP != 0`.
- QEMU source:
  old `emulator/qemu/target/linx/translate.c:3404` initialized the output from
  `SrcL`, then overwrote it with `SrcR` when the predicate was nonzero.
- Live QEMU row 1625 in the 1660-row probe distinguishes the behavior:
  immediately before the row, `srcp=x24/T0=1`, `SrcL=x2=0`, and
  `SrcR=x28/U0=0x40000768`; QEMU writes `U0=0x40000768`, which is
  true-to-`SrcR`.
- R138 local patches align QEMU scalar `trans_csel`, LLVM CSEL MC lowering, the
  reduced Chisel `OP_CSEL` execute result, and the row-reducer expected value to
  true-to-`SrcL`.

Current mitigation:

- The current reduced QEMU row schema exposes only two source fields, so the row
  reducer admits CSEL when `SrcP` is in the reduced T/U local overlay. A
  scalar-predicate CSEL row must wait for a source-2 trace-schema extension.

## CHISEL-ISSUE-004: Conditional Marker Drain After R139 LBUI

Status: closed in R140

Impact:

- The R139 `LBUI` packet promotes the live CoreMark prefix to 1642 captured raw
  QEMU rows with zero normalized mismatches.
- A 1660-row probe reaches `pc=0x40005d94`, a `C.BSTART COND`, and the
  Verilator harness reports the dense slot queue did not drain.
- At the failure, `markerSkipValid=1`, `markerActiveValid=1`,
  `markerActiveBid=0x112`, `markerActiveTarget=0x40005d72`, `issueCount=1`,
  and `executeBusy=1`. This points at marker admission/drain timing around an
  active conditional block, not at `LBUI` data or memory semantics.
- R140 showed the RTL drains naturally once the harness row-drain budget covers
  the reduced RF/issue/execute latency for the in-flight `c.setc.eq` decision.

Evidence:

- LinxCore `540ed8a3c26bd27b4e97d5a7cf07b03a61ad8d46` before R139 edits.
- Local LinxCoreModel `1993e4e749403824a4908548baf77d5e15117068`.
- QEMU `513018b25c8212bc38e9f42241d3996e79e918c7`.
- Passing gate:
  `generated/r139-lbui-1642-qemu-elf-xcheck/report/crosscheck_report.md`
  compares 1114 normalized rows with zero mismatches.
- Failing probe:
  `generated/r139-lbui-1660-qemu-elf-xcheck` extracts 1534 expected rows, then
  fails at the conditional marker drain.
- R140 passing gate:
  `generated/r140-drain64-1660-qemu-elf-xcheck/report/crosscheck_report.md`
  compares 1124 normalized rows with zero mismatches after increasing the
  harness dense-row drain budget from 16 to 64 cycles.

Resolution:

- The RTL was not changed for this issue.
- `tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp` now names the dense-row
  drain budget as `kDenseRowDrainCycles = 64`, allowing the marker row to wait
  for the prior block's in-flight scalar branch-decision row to complete.
- The promoted CoreMark prefix is now the 1660 captured raw-row window.

## CHISEL-ISSUE-005: Reduced GPR Rename Reused Live Physical Tags

Status: resolved in R141

Impact:

- Long CoreMark RF/ALU probes exposed an alias where a physical tag was
  returned to the free pool while still referenced by a live rename map. The
  visible symptom was unrelated architectural registers sharing a physical
  source tag later in the reduced top.
- After identity-tag release was blocked, the same probe showed real
  free-list pressure with 64 physical tags: `gprFree=0` while mapQ still had
  entries available.

Evidence:

- LinxCoreModel `configs/core.toml` sets `ggpr_count = 128` and
  `ggpr_mapq_depth = 256`.
- The reduced CoreMark diagnostic replay showed `decodeBlockedByRename=1`,
  `gprFree=0`, and nonzero `gprMapQFree`, proving physical capacity, not mapQ,
  was the active bottleneck.

Resolution:

- `GPRRenameCheckpoint` no longer releases identity tags and forces any tag
  referenced by next `smap`, `cmap`, or valid `mapQ` to remain allocated.
- `LinxCoreFrontendFetchRfAluTraceTop` emits with 128 physical registers and
  7-bit physical tags, while common bundle invalid physical tags widen to
  all-ones so physical tag `63` remains usable.
- The reduced issue queue treats `x1/sp` as ready because the live top sources
  SP data from its scalar-SP shadow.

## CHISEL-ISSUE-006: Bounded Crosscheck False Trace-Length Failure

Status: resolved in R141

Impact:

- A finite expected-row replay loaded 1189 QEMU rows and 1189 DUT rows, matched
  every compared architectural field, then failed with a synthetic
  `trace_length` mismatch because one side-effect-free metadata row was
  filtered before compare.

Evidence:

- `generated/r141-diagnostic-replay-1747-fret-target-priority/report/crosscheck_report.md`
  reported equal loaded row counts and the final architectural row matched,
  but `compared=1188` against `--max-commits 1189`.

Resolution:

- `tools/trace/crosscheck_qemu_linxcore.py` now allows bounded finite windows
  to pass when both metadata-filtered streams end together before
  `--max-commits`.
- The same traces rerun through
  `tools/chisel/run_chisel_qemu_crosscheck.sh` pass with
  `compared=1188 mismatches=0`.

## CHISEL-ISSUE-007: FALL Block Re-entry Needs F4 Body-Boundary Cut

Status: closed for the reduced live top by R143; full BFU metadata ownership
remains future work

Impact:

- The first post-R141 CoreMark loop probe reaches a model/QEMU dynamic FALL
  block re-entry around `pc=0x4000630c`. QEMU repeats the `C.BSTART.STD.FALL`
  header after the body row at `pc=0x4000632c`, while the previous scalar row
  still reports `next_pc=0x4000632e`.
- The reduced QEMU-row extractor can now admit and annotate this shape with
  `--allow-block-loop-reentry`, but the R142 Chisel live-fetch path still
  captures an extra F4 slot at `0x4000632e` instead of cutting the packet at the
  model block body boundary and restarting at the FALL header.

Evidence:

- `generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.raw.jsonl`
  has 1900 raw QEMU rows. Strict extraction fails at raw row 1747 with
  `pc=0x4000630c, expected 0x4000632e`.
- The loop-aware extraction command emits 1766 expected rows and 11 annotated
  `loop_reentry` marker rows:
  `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --input generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.raw.jsonl --output generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.expected.loop.jsonl --max-rows 0 --allow-block-markers --allow-block-loop-reentry`.
- Replaying that expected stream against the R142 top fails before compare:
  `frontend fetch RF ALU dense packet was not captured pc=0x4000632a
  expected_mask=0x3 observed_mask=0x7 expected_slots=2 observed_f4_slots=3
  expected_advance=4 observed_advance=8`.
- R143 adds a temporary reduced BFU body-cut sideband to the live top and
  harness. The loop-aware expected replay in
  `generated/r143b-loop-reentry-rtl-replay/report` compares 1330 normalized
  QEMU/DUT rows with zero mismatches.
- R144 moves the arithmetic into `ReducedBfuBodyCutPredictor`: the reduced
  harness now supplies `headerPc`/`hsize`/`bsize` geometry, and the top computes
  the cut and restart locally.

Resolution:

- The reduced live-fetch/F4 path now consumes `reducedBodyCut*` metadata derived
  from the loop-aware row stream, masks off slots at and after the model body
  boundary, advances the source only to that cut PC, preserves the clipped dense
  rows, and schedules a source-only restart to the annotated FALL header.
- R144 narrows this temporary contract to BFU-style body geometry rather than
  exact cut/restart commands: `cutPc = headerPc + 2 + bsize`, matching
  LinxCoreModel `BFUUtils::NextBlockPC`, and `restartPc = headerPc + hsize`,
  matching `BCtrlUnit::ConvertToBHeader` FALL resolution.
- This closes the R142 packet-boundary failure as replacement evidence for the
  reduced top. It does not close the architectural BFU work: a later packet must
  replace the harness-provided sideband with real static-predictor block-body
  metadata (`bsize`/`hsize`/fallBPC ownership) before claiming full
  block-control closure.

## CHISEL-ISSUE-008: Scalar GPR mapQ Capacity Was Tied To Local T/U Depth

Status: resolved in R196

Impact:

- The admitted-marker CoreMark path stopped before QEMU row 227 at
  `pc=0x4000557a` with `decodeBlockedByRename=1`, `gprFree=63`, and
  `gprMapQFree=0`.
- This was not the stale wrapped-BID rename bug fixed earlier; it was a
  capacity mismatch. The reduced Chisel top used one `mapQDepth = 32` for both
  scalar GPR mapQ pressure and local T/U sequence plumbing.

Evidence:

- LinxCoreModel `configs/core.toml` sets `ggpr_count = 128` and
  `ggpr_mapq_depth = 256`.
- LinxCoreModel `model/bctrl/spe/GPRRename.cpp` builds the GGPR mapQ from
  `sim->core->configs.ggpr_mapq_depth` and stalls when `mapQFreeSize[stid]`
  reaches zero.
- The R195 gate
  `generated/r195-marker-row-scale-1024-qemu-elf-xcheck` compared 157
  normalized scalar rows before the model-sized queue mismatch stopped the RTL.

Resolution:

- `ScalarTURenameBridge`, `DecodeRenameROBPath`, and the fetch/RF/ALU trace
  tops now carry a separate `gprMapQDepth` parameter.
- The marker-row emitters instantiate `gprMapQDepth = 256` and `physRegs = 128`
  while leaving local T/U `mapQDepth = 32`.
- Focused interface tests assert that scalar GPR mapQ free-count width follows
  the model-sized capacity without widening local T/U ROBID sequence widths.

## CHISEL-ISSUE-009: Model-sized GPRRenameCheckpoint Is Too Large For Fast Verilator Front-end Compile

Status: closed in R198

Impact:

- The exact 1024-row admitted-marker CoreMark gate could not produce a DUT
  trace at `gprMapQDepth = 256` because Verilator stayed in front-end
  processing before creating `obj_dir`.
- This blocked proving whether the R196 model-capacity fix advanced past the
  old R195 `pc=0x4000557a` stall.

Evidence:

- R196 emitted the marker-row top in roughly 108 seconds and Verilator reached
  about 4.7 GB RSS before the run was interrupted while still before object
  generation.
- R197 rewrote scalar GPR release/live masks with one-hot reductions. Chisel
  emit dropped to roughly 20 seconds and Verilator RSS dropped to roughly
  1.6 GB, but `GPRRenameCheckpoint.sv` was still about 24 MB and 252k lines,
  and Verilator still did not create `obj_dir` after a long front-end run.
- R198 split the largest per-architectural-register replay and commit mapQ
  scans into `GPRRenameReplaySurvivorSelect` and
  `GPRRenameCommitArchSelect`. The generated parent checkpoint module dropped
  to roughly 7.4 MB and 154k lines, with the helper modules emitted separately.
- `BUILD_DIR=generated/r198-helper-split-marker-row-smoke bash tools/chisel/run_chisel_frontend_fetch_rf_alu_marker_rows_smoke.sh`
  passed. Verilator reported 46 generated modules, 73 C++ files, roughly
  262 seconds wall time, and roughly 1.6 GB allocation.
- The 1024-row admitted-marker CoreMark gate now reaches DUT comparison,
  proving the old pre-`obj_dir` compile blocker is gone.

Resolution:

- Keep the helper-module split for model-sized scalar GPR mapQ scans. Do not
  inline those per-architecture replay/commit scans back into the parent
  checkpoint without rerunning the model-sized Verilator smoke and 1024-row
  marker-row gate.

## CHISEL-ISSUE-010: R198 Marker-row Source Read Sees x6 As Zero After Capacity Fix

Status: closed in R202

Impact:

- After the model-sized GPR mapQ path became Verilator-practical, the 1024-row
  admitted-marker CoreMark gate advanced past the old R195
  `gprMapQFree=0` stall and exposed a functional RF/source-value mismatch.
- The first diagnostic framed this as RF/source-value, but the later ROB-keyed
  source-tag diagnostic showed a rename recovery problem: the row was using an
  older physical tag while the latest architectural writer had allocated a
  newer tag.

Evidence:

- Command:
  `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r198-helper-split-marker-row-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- The run captured 1024 raw QEMU rows, extracted 953 expected rows, emitted the
  Chisel top in roughly 16 seconds, and built the Verilated model before
  failing in the comparator.
- Comparator failure:
  `expected_pc=0x400055be`, `observed_pc=0x400055be`,
  `expected_insn=0x31f6`, `observed_insn=0x31f6`,
  `expected=(6,296)`, `observed=(6,0)`,
  `src_phys_mask=0x3`, `src_phys=(46,20,0)`, and `rf_write=(0,0,0)`.
- The comparator's last-writer diagnostic reported source-1 physical tag 20:
  `writer_pc=0x40005576`, `writer_insn=0xffe13319`, `writer_wb=6`,
  `writer_data=0`.
- The saved JSONL traces contain the first 181 matched rows; the failing row
  is printed by the Verilator comparator before it is appended to
  `generated/r198-helper-split-marker-row-1024-qemu-elf-xcheck/traces/dut.chisel.jsonl`.
- R202 diagnostics corrected the source tag printing to decimal and keyed
  issue source tags by ROB. The old `src_phys=(46,20,0)` line had been
  misleading because the relevant tag was printed as hex; the true stale tag
  was physical 32. The same diagnostic later caught the first `FENTRY` save
  row using identity physical tag 10 while `C.SETRET pc=0x40005506` had already
  produced architectural `x10` into physical tag 25.

Resolution:

- `ScalarDecodeRenameBridge` now refreshes the scalar GPR checkpoint for the
  current block after each accepted reduced in-order row, using the post-rename
  map when a destination is allocated.
- `LinxCoreFrontendFetchRfAluTraceTop` now feeds marker-stop cleanup with the
  next block BID. `GPRRenameCheckpoint` follows the model restore rule
  `restoreBid = flush.bid - 1`, so a block-stop redirect must ask to restore
  the just-finished block checkpoint rather than the checkpoint before it.
- The passing R202 command:
  `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r202-marker-stop-restore-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 24 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf`
- Result: 1024 raw QEMU rows captured, 953 expected rows extracted, 288 marker
  commits admitted/filtered, 665 normalized QEMU/DUT rows compared, and zero
  mismatches.
