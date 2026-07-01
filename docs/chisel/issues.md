# Chisel Issues

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

Status: open after R142

Impact:

- The first post-R141 CoreMark loop probe reaches a model/QEMU dynamic FALL
  block re-entry around `pc=0x4000630c`. QEMU repeats the `C.BSTART.STD.FALL`
  header after the body row at `pc=0x4000632c`, while the previous scalar row
  still reports `next_pc=0x4000632e`.
- The reduced QEMU-row extractor can now admit and annotate this shape with
  `--allow-block-loop-reentry`, but the current Chisel live-fetch path still
  captures an extra F4 slot at `0x4000632e` instead of cutting the packet at the
  model block body boundary and restarting at the FALL header.

Evidence:

- `generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.raw.jsonl`
  has 1900 raw QEMU rows. Strict extraction fails at raw row 1747 with
  `pc=0x4000630c, expected 0x4000632e`.
- The loop-aware extraction command emits 1766 expected rows and 11 annotated
  `loop_reentry` marker rows:
  `python3 tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --input generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.raw.jsonl --output generated/r142-loop-frontier-1900-qemu-probe/traces/qemu.live.expected.loop.jsonl --max-rows 0 --allow-block-markers --allow-block-loop-reentry`.
- Replaying that expected stream against the current top fails before compare:
  `frontend fetch RF ALU dense packet was not captured pc=0x4000632a
  expected_mask=0x3 observed_mask=0x7 expected_slots=2 observed_f4_slots=3
  expected_advance=4 observed_advance=8`.

Resolution plan:

- Add model-derived block-body end metadata to the reduced frontend path, using
  LinxCoreModel BFU evidence that FALL resolves to `fallBPC`, computed from
  header geometry (`spInfo->hsize` when present).
- Teach the live-fetch/F4 path to cut the response before the static fallBPC
  continuation when the active reduced block resolves back to the FALL header,
  then restart at the annotated re-entry PC.
- Promote the R142 loop stream only after the generated-RTL replay compares
  past the first `loop_reentry` row.
