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
