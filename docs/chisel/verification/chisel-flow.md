# Chisel Flow

## Phase 0 Target Shape

The LinxCore Chisel lane uses explicit targets:

| Target | Script | Purpose |
|---|---|---|
| `build` | `tools/chisel/build_chisel.sh` | Compile Chisel sources and tests. |
| `test` | `tools/chisel/run_chisel_tests.sh` | Run Scala/Chisel tests. |
| `emit-verilog` | `tools/chisel/emit_verilog.sh` | Emit `generated/chisel-verilog/LinxCoreTop.sv`. |
| `verilator-lint` | `tools/chisel/run_chisel_verilator_lint.sh` | Emit the Chisel top and run Verilator lint over every top-level emitted SystemVerilog file. |
| `robid-xcheck` | `tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` | Run Packet A ROBID semantic gate. |
| `flushcontrol-xcheck` | `tools/chisel/run_chisel_tests.sh --only FlushControl` | Run Packet B FlushControl classification and older-signal tests. |
| `brob-xcheck` | `tools/chisel/run_chisel_tests.sh --only BROB` | Run Packet C BID encoding and BROB metadata lifecycle tests. |
| `commit-monitor-xcheck` | `tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor` | Run Phase 1 fixed-width commit-window contract checks. |
| `reduced-rob-xcheck` | `tools/chisel/run_chisel_reduced_rob_xcheck.sh` | Emit `ReducedCommitROB`, build the Verilator harness, assert commit-window monitor outputs, and compare normalized DUT rows against QEMU-shaped reference rows. |
| `top-xcheck` | `tools/chisel/run_chisel_top_xcheck.sh` | Emit the reduced `LinxCoreTop` xcheck configuration, build the same Verilator harness against top-level IO, assert monitor outputs, and compare normalized DUT rows against QEMU-shaped reference rows. |
| `frontend-alu-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh` | Emit `LinxCoreFrontendAluTraceTop`, build the Verilator harness, drive frontend packets through reduced scalar ALU execute, and compare nonzero writeback rows against QEMU-shaped reference rows. |
| `frontend-rf-alu-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh` | Emit `LinxCoreFrontendRfAluTraceTop`, build the shared Verilator harness in RF mode, preload identity scalar registers, enqueue dependent scalar ALU rows through the reduced issue queue, and compare RF-sourced writeback rows against QEMU-shaped reference rows. |
| `qemu-crosscheck` | `tools/chisel/run_chisel_qemu_crosscheck.sh` | Normalize QEMU and DUT commit JSONL, run the neutral comparator, and emit `crosscheck_manifest.json` tying raw traces, normalized traces, reports, QEMU binary, row counts, and git context into one evidence bundle. |

The target shape follows the useful part of the OpenXiangShan flow: make Chisel
generation, simulation/emulator construction, and architectural cross-checking
separate gates. LinxCore does not import XiangShan's RISC-V payloads. The
agent-facing runbook for applying this target shape module by module is
`docs/chisel/agent-loop.md`.

## Current Toolchain Status

The Chisel wrappers set `JAVA_HOME` to Homebrew `openjdk@17` when `JAVA_HOME` is
not already set. The Packet A ROBID gate runs both a hermetic Python semantic
check derived from LinxCoreModel and the Scala ROBID test.

SBT-backed wrappers must be run sequentially until `CHISEL-ISSUE-002` is closed.
Verilator wrappers must pass the full emitted SystemVerilog set, because CIRCT
emits instantiated Chisel modules as sibling `.sv` files rather than inlining
them into the top module.
The top xcheck intentionally emits an 8-entry, two-wide `LinxCoreTop`
configuration into `generated/chisel-verilog/top-xcheck` so it can reuse the
same bounded three-row smoke as the reduced ROB harness while the default top
configuration remains `CoreParams()`.
Generated-RTL comparison wrappers that call `run_chisel_qemu_crosscheck.sh`
write `crosscheck_manifest.json` in their report directory. Treat that manifest
as the handoff artifact for later QEMU/CoreMark promotion: it should name the
raw traces, normalized traces, comparator reports, selected QEMU binary, row
counts, and the LinxCore/superproject revisions used by the run.

## Version Decision

The initial `build.sbt` pins Chisel `7.3.0`, matching the OpenXiangShan
`kunminghu-v3` build reference inspected for this plan. The Scala version is
`2.13.17`, matching the dependency graph resolved by sbt 2.0.0. This can be
revised only through a Chisel-flow update that records the build evidence and
any CIRCT compatibility reason.

## XiangShan Flow Notes

The OpenXiangShan `kunminghu-v3` Makefile uses explicit targets for generated
RTL, simulation RTL, and emulator construction, with `CHISEL_TARGET` selecting
the emission backend and difftest enabled in the simulation build. Its
`build.mill` keeps Chisel `7.3.0`, Scala `2.13.17`, and firtool resolution in a
single build graph. The DiffTest README shows the reusable verification shape:
typed Chisel event bundles are connected near the design top, finalized at the
simulation top, and consumed by a simulator/emulator harness.

For LinxCore, the borrowed pattern is target and event-shape discipline: typed
Linx commit, trap, memory, block, and recovery rows feed the neutral
QEMU/LinxCoreModel cross-check path. The RISC-V-specific payloads, CSR probes,
and NEMU/Spike reference flow are intentionally not imported.
