# Standalone OEX (OOO+IEX) Superscalar Rewrite Plan (pyCircuit v3.5)

This directory implements a **standalone, trace-driven** OEX cluster for LinxCore bring-up.

## Goal

- Provide a **parameterized**, **scalable** OEX superscalar cluster written in **pyCircuit v3.5**.
- Use QEMU Linx as the **architectural oracle** (shadow-check mode):
  - OEX must **verify retired results** (src/dst + mem fields) against QEMU commit trace.
  - OEX must report **IPC** (commits/cycles) for **CoreMark** and **Dhrystone**.
- Keep interfaces and module boundaries aligned with eventual integration into the full core.

## Mode of operation (today)

OEX is driven by a **QEMU commit-trace JSONL** stream:

- Each JSONL row includes: `pc, insn, len, src0/src1, dst, mem, trap, next_pc` (and optional `cycle/seq`).
- In standalone mode, OEX uses these fields as the **oracle payload** for correctness checking, while its
  superscalar scheduler models **dispatch/issue/execute/commit timing** with configurable widths/lanes/latencies.

This intentionally separates:

- **Functional correctness** (QEMU = reference),
- **Microarchitectural timing/throughput** (OEX = model under test).

## Major configuration knobs (must be parameterized)

All sizing must derive from the profile/config, not hard-coded widths:

- Windowing:
  - `rob_depth` (e.g. 64 debug, 256 target)
  - `issueq_depth` (per-lane)
- Widths:
  - `dispatch_w` (front-end rename/dispatch per cycle)
  - `issue_width` (global issue budget per cycle)
  - `commit_w` (in-order retire width per cycle)
- Lane counts:
  - `alu_lanes`, `bru_lanes`, `agu_lanes`, `std_lanes`, `cmd_lanes`, `fsu_lanes`, `tpl_lanes`
- Register renaming:
  - `aregs` (architectural registers; default 64)
  - `ptag_rf_entries` (physical tag space)
- Latencies (modeling only; correctness stays oracle-driven):
  - `lat_alu`, `lat_bru`, `lat_agu`, `lat_std`, `lat_cmd`, `lat_fsu`, `lat_tpl`

Derived widths:

- `rob_w = clog2(rob_depth)`
- `ptag_w = clog2(ptag_rf_entries)`
- `aregs_w = clog2(aregs)`

## Architecture contract (module boundaries)

OEX consists of two primary clusters:

1) **OOO cluster**
- D1/D2 decode classification (standalone uses trace-derived hints)
- D3 rename + ROB allocation
- Commit/retire (in order, `commit_w`)
- Flush control / epoching (present for future integration)

2) **IEX cluster**
- Per-lane issue queues (ALU/BRU/AGU/STD/CMD/FSU/TPL)
- Ready table wakeups (multi-wakeup per cycle)
- Per-lane pipelines with fixed modeled latency
- Writeback events feed OOO (ROB completion + tag wakeup)

## Verification contract

The OEX retire stream must match QEMU row-by-row for the following fields:

- `pc`, `insn/raw masked by len`, `len`, `next_pc`
- `src0_valid/src0_reg/src0_data`, `src1_*`
- `dst_valid/dst_reg/dst_data`
- `mem_valid/mem_is_store/mem_addr/mem_size/(mem_wdata or mem_rdata)`
- `trap_valid/trap_cause/traparg0`

Fail-fast on first mismatch.

## IPC contract

For a workload with `N` committed instructions:

- `cycles` = OEX modeled cycles to retire all `N`.
- `IPC = N / cycles`.

We report IPC for CoreMark and Dhrystone under the `oex_target` profile.

## How to run

High-level flow:

1) Produce QEMU commit trace JSONL:
- Use `/Users/zhoubot/LinxCore/tools/qemu/run_qemu_commit_trace.sh`

2) Build OEX C++ simulator (generated from pyCircuit) and run shadow check:
- Use scripts under `/Users/zhoubot/LinxCore/standalone/OEX/tools` (added by this rewrite)

## Implementation notes (current)

- OEX models a superscalar OOO cluster with per-class issue queues and fixed-latency execution pipes.
- Issue scheduling uses a simple round-robin start pointer across queues plus lane-rounds (lane0/lane1/...)
  to avoid starving stores behind load traffic.
- Issue-queue enqueue performs **same-cycle wakeup** against current writeback ports to avoid missing
  wakeups when a consumer is enqueued in the same cycle its producer completes.

## Results (oex_target, shadow-checked vs QEMU)

Measured on **Feb 20, 2026** with:

- Build: `bash /Users/zhoubot/LinxCore/standalone/OEX/tools/build_oex_shadow.sh`
- Run: `PYC_SIM_FAST=1 /Users/zhoubot/LinxCore/standalone/OEX/generated/tb_oex_shadow --trace <trace>`

Benchmarks:

- CoreMark (`tests/benchmarks_latest_llvm_musl/verify/coremark/qemu_trace.jsonl`):
  - `cycles=794987 commits=1216365 ipc=1.53004`
- Dhrystone (`tests/benchmarks_latest_llvm_musl/verify/dhrystone/qemu_trace.jsonl`):
  - `cycles=820380 commits=1255201 ipc=1.53002`

## Non-goals (this phase)

- Full ISA semantic execution inside OEX (QEMU is the oracle).
- Cycle-true LinxTrace export and LinxCoreSight UI fixes (tracked separately).
