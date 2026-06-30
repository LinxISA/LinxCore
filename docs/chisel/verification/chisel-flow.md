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
| `frontend-fetch-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh` | Emit `LinxCoreFrontendFetchTraceTop`, build the Verilator harness, drive a bounded memory-window fixture through `FrontendFetchPacketSource`, F4, and reduced decode/ROB, and compare commit rows against QEMU-shaped reference rows. |
| `frontend-alu-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh` | Emit `LinxCoreFrontendAluTraceTop`, build the Verilator harness, drive frontend packets through reduced scalar ALU execute, and compare nonzero writeback rows against QEMU-shaped reference rows. |
| `frontend-rf-alu-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh` | Emit `LinxCoreFrontendRfAluTraceTop`, build the shared Verilator harness in RF mode, preload identity scalar registers, enqueue dependent scalar ALU rows through the reduced issue queue, and compare RF-sourced writeback rows against QEMU-shaped reference rows. |
| `frontend-fetch-rf-alu-trace-top-xcheck` | `tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` | Emit `LinxCoreFrontendFetchRfAluTraceTop`, build the Verilator harness, drive PC request/response windows from a binary or sparse ELF fetch-memory image plus QEMU-shaped expected-row JSONL through `FrontendFetchPacketSource`, F4, reduced rename/ROB, RF-backed issue, and ALU execute, then compare dependent scalar commit rows against QEMU-shaped reference rows. |
| `frontend-fetch-rf-alu-fixture-rows` | `tools/chisel/frontend_fetch_rf_alu_fixture_rows.py --self-test` | Validate the default QEMU-shaped expected-row fixture used when `FETCH_EXPECTED_ROWS` is unset. |
| `frontend-fetch-rf-alu-qemu-rows` | `tools/chisel/frontend_fetch_rf_alu_qemu_rows.py --self-test` | Validate strict QEMU commit JSONL prefix extraction for the reduced scalar RF/ALU gate used by `FETCH_QEMU_TRACE`. |
| `frontend-fetch-elf-memory` | `tools/chisel/frontend_fetch_elf_memory.py --self-test` | Validate the ELF64 little-endian PT_LOAD extractor that creates sparse address-to-byte fetch-memory images for `FETCH_ELF` runs. |
| `commit-jsonl-writer` | `tools/chisel/commit_trace_jsonl.h` | Shared C++ helper used by Verilator harnesses to emit QEMU-shaped reference rows and DUT sideband rows without per-harness field spelling drift. |
| `qemu-crosscheck` | `tools/chisel/run_chisel_qemu_crosscheck.sh` | Normalize QEMU and DUT commit JSONL, run the neutral comparator, and emit `crosscheck_manifest.json` tying raw traces, normalized traces, reports, QEMU binary, row counts, and git context into one evidence bundle. |
| `qemu-trace-replay-xcheck` | `tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh` | Capture or consume a bounded QEMU commit JSONL prefix, replay those rows through the current Chisel commit surface in an isolated build directory, and preserve comparator manifest evidence. |

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
counts, `max_commits`, `normalize_rows`, and the LinxCore/superproject
revisions used by the run.
Generated-RTL Verilator harnesses should use `tools/chisel/commit_trace_jsonl.h`
for emitted commit JSONL. Harness code may still convert top-specific pins into
rows, but the helper owns QEMU field names, default zero values, boolean
encoding, and the fixed DUT sideband fields used by `trace_schema_adapter.py`.
The QEMU trace replay wrapper is a bridge gate: it validates QEMU-row schema
and Chisel commit-surface replay before the top-level core emits live commit
rows from fetch/issue/execute/LSU/recovery.
The frontend fetch trace-top xcheck is the first live source packet gate: it
replaces testbench-supplied `FrontendDecodePacket` inputs with a PC
request/response source and a bounded instruction-window fixture, then compares
the retired rows through the same manifest-producing comparator path.
The frontend fetch RF/ALU trace-top xcheck extends that live source gate into
the RF-backed reduced issue and ALU path. The wrapper emits
`fixture.fetch.bin` by default and passes it as `FETCH_MEMORY_BIN`/`--memory-bin`
to the Verilator driver. R97 adds `FETCH_ELF` and `FETCH_MEMORY_HEX`; ELF runs
extract PT_LOAD segments into a sparse address-to-byte map before the driver
reads instruction bytes at each requested PC and forms the bounded
single-instruction response window. R98 adds `FETCH_EXPECTED_ROWS`: the driver
now reads PC, instruction length, scalar source data, and writeback
expectations from QEMU-shaped JSONL and the wrapper sizes the comparator window
from that row count. R99 adds `FETCH_QEMU_TRACE`, which normalizes an existing
QEMU commit JSONL and extracts a strict sequential reduced-scalar prefix into
the expected-row stream. Chisel now owns packet creation, F4 byte-count PC
advance, rename/ROB allocation, RF source data, issue residency, ALU
completion, and commit-row export for those reduced scalar rows. Live QEMU
capture automation and full live DUT comparison remain later owners.
QEMU-row gates must keep raw replay/normalization depth separate from the
architectural compare depth because the comparator filters metadata rows before
checking lockstep architectural commits.
For direct-boot benchmark ELFs mapped above the default 128 MiB RAM window,
pass explicit QEMU memory in the trailing args. The current CoreMark replay
prefix gate uses `-- -nographic -monitor none -machine virt -m 1280M -kernel
tests/benchmarks/build/coremark_real.elf`, captures 128 raw rows, and compares
the first 4 architectural commits through the Chisel replay surface.

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
