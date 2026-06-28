# Chisel Flow

## Phase 0 Target Shape

The LinxCore Chisel lane uses explicit targets:

| Target | Script | Purpose |
|---|---|---|
| `build` | `tools/chisel/build_chisel.sh` | Compile Chisel sources and tests. |
| `test` | `tools/chisel/run_chisel_tests.sh` | Run Scala/Chisel tests. |
| `emit-verilog` | `tools/chisel/emit_verilog.sh` | Emit `generated/chisel-verilog/LinxCoreTop.sv`. |
| `verilator-lint` | `tools/chisel/run_chisel_verilator_lint.sh` | Emit the Chisel top and run Verilator lint. |
| `robid-xcheck` | `tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` | Run Packet A ROBID semantic gate. |
| `flushcontrol-xcheck` | `tools/chisel/run_chisel_tests.sh --only FlushControl` | Run Packet B FlushControl classification and older-signal tests. |
| `brob-xcheck` | `tools/chisel/run_chisel_tests.sh --only BROB` | Run Packet C BID encoding and BROB metadata lifecycle tests. |
| `commit-monitor-xcheck` | `tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor` | Run Phase 1 fixed-width commit-window contract checks. |
| `reduced-rob-xcheck` | `tools/chisel/run_chisel_reduced_rob_xcheck.sh` | Emit `ReducedCommitROB`, build the Verilator harness, assert commit-window monitor outputs, and compare normalized DUT rows against QEMU-shaped reference rows. |

The target shape follows the useful part of the OpenXiangShan flow: make Chisel
generation, simulation/emulator construction, and architectural cross-checking
separate gates. LinxCore does not import XiangShan's RISC-V payloads.

## Current Toolchain Status

The Chisel wrappers set `JAVA_HOME` to Homebrew `openjdk@17` when `JAVA_HOME` is
not already set. The Packet A ROBID gate runs both a hermetic Python semantic
check derived from LinxCoreModel and the Scala ROBID test.

SBT-backed wrappers must be run sequentially until `CHISEL-ISSUE-002` is closed.

## Version Decision

The initial `build.sbt` pins Chisel `7.3.0`, matching the OpenXiangShan
`kunminghu-v3` build reference inspected for this plan. The Scala version is
`2.13.17`, matching the dependency graph resolved by sbt 2.0.0. This can be
revised only through a Chisel-flow update that records the build evidence and
any CIRCT compatibility reason.
