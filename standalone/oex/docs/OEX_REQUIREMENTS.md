# Standalone OEX (pyCircuit v3.5) — Requirements

This document captures the functional and non-functional requirements for rewriting
`/Users/zhoubot/LinxCore/standalone/OEX` into a scalable, parameterized **pyCircuit v3.5**
design that models a **superscalar** out-of-order execution cluster and is
**shadow-checked** against QEMU.

## 1. Scope / Deliverable

- Rewrite the standalone OEX design under `standalone/OEX` into a **parameterized**,
  **scalable**, **pyCircuit v3.5** implementation.
- Improve the existing draft into a **CPU superscalar** timing model with configurable:
  - multi-dispatch,
  - multi-issue,
  - multi-commit,
  - multiple execution lanes (ALU/BRU/AGU/STD/CMD/FSU/TPL),
  - modeled per-class fixed latencies.
- Provide a **C++ shadow harness** that:
  - streams a QEMU commit-trace JSONL file,
  - drives OEX inputs using ready/valid,
  - verifies retired rows **match QEMU** (src/dst/mem/trap/next_pc),
  - reports **cycles**, **commits**, and **IPC** for benchmarks.

## 2. Operating Mode (Current Phase)

OEX is **oracle-driven**:

- QEMU is the architectural oracle (functional correctness).
- OEX models microarchitectural timing/throughput (performance model).

The input stimulus is a QEMU **commit trace** (JSONL). Each row contains at least:

- `pc`, `insn`, `len`, `next_pc`
- `src0_valid/src0_reg/src0_data`, `src1_*`
- `dst_valid/dst_reg/dst_data` (or derived from `wb_*` for backward compatibility)
- `mem_valid/mem_is_store/mem_addr/mem_wdata/mem_rdata/mem_size`
- `trap_valid/trap_cause/traparg0`
- optional: `seq`, `cycle`, other debug keys

OEX must retire rows in the **same order** and with the **same field values** as QEMU.

## 3. Benchmarks / Must-Run Checks

The rewrite must run against the existing benchmark traces:

- CoreMark: `tests/benchmarks_latest_llvm_musl/verify/coremark/qemu_trace.jsonl`
- Dhrystone: `tests/benchmarks_latest_llvm_musl/verify/dhrystone/qemu_trace.jsonl`

For each benchmark, the harness must:

- complete without mismatches (fail-fast on first mismatch),
- print:
  - `cycles`,
  - `commits`,
  - `IPC = commits / cycles`.

## 4. Parameterization Requirements

All major sizing/width knobs must be parameterized (no hard-coded constants):

### Windowing
- `rob_depth` (power-of-two)
- `issueq_depth` (power-of-two; depth per lane or per-class IQ)

### Widths
- `dispatch_w` (max input/rename per cycle)
- `issue_width` (global issue budget per cycle)
- `commit_w` (max retire per cycle)

### Lane Counts
- `alu_lanes`, `bru_lanes`, `agu_lanes`, `std_lanes`, `cmd_lanes`, `fsu_lanes`, `tpl_lanes`

### Renaming / Tags
- `aregs` (architectural registers; power-of-two)
- `ptag_rf_entries` (physical tag space; power-of-two)

### Fixed Latencies (modeled)
- `lat_alu`, `lat_bru`, `lat_agu`, `lat_std`, `lat_cmd`, `lat_fsu`, `lat_tpl` (all > 0)

Derived widths must be computed from parameters (template arithmetic):

- `aregs_w = clog2(aregs)`
- `rob_w = clog2(rob_depth)`
- `ptag_w = clog2(ptag_rf_entries)`

## 5. Superscalar Timing Model Requirements

The model must include, at minimum:

- **Rename + ROB allocation** for up to `dispatch_w` instructions/cycle.
- **Register dependency tracking** via tags (or equivalent) such that:
  - consumers wait for producers’ completion,
  - wakeups occur from writeback events,
  - destination tags become ready only after modeled execution latency.
- **Issue scheduling** capable of issuing up to:
  - `issue_width` total uops/cycle,
  - and at most one uop/cycle per lane instance.
- **In-order commit** of up to `commit_w` instructions/cycle, blocked by oldest not-done ROB entry.

Instruction class selection for lane routing must be deterministic and based on trace fields
and/or lightweight decoding (no full ISA semantics required).

## 6. Interfaces (Standalone Top)

Top module (generated to C++ via `pycc --emit=cpp`) must expose:

- Clock/reset.
- An input bundle of `dispatch_w` trace rows (ready/valid).
- An output bundle of `commit_w` retired trace rows (valid).
- Statistics outputs:
  - `cycles` (64-bit),
  - `commits` (64-bit).

## 7. Verification Contract (Shadow Check)

The checker must compare retired rows against QEMU row-by-row for:

- `pc`, `insn` (masked by `len`), `len`, `next_pc`
- `src0_valid/src0_reg/src0_data`, `src1_*`
- `dst_valid/dst_reg/dst_data`
- `mem_valid/mem_is_store/mem_addr/mem_wdata/mem_rdata/mem_size`
- `trap_valid/trap_cause/traparg0`

Mismatch handling:

- fail-fast,
- print the failing `seq`, field name, and a concise per-row summary.

## 8. Non-Goals (Current Phase)

- Implementing full Linx ISA semantics inside OEX (QEMU remains oracle).
- Modeling branch prediction accuracy, exceptions side-effects, or MMU/devices in OEX.
- Integrating into the full core in this phase (interfaces must remain integration-friendly).

## 9. Build / Run (pyCircuit v3.5 flow)

The standalone build must use the v3.5 multi-stage flow:

- Frontend emit: `python3 -m pycircuit.cli emit standalone.oex.design.oex_top --output <out>.pyc`
- Backend C++ emit: `pycc --emit=cpp --cpp-split=module --out-dir <cpp_dir> <out>.pyc`
- Compile + run shadow TB against the QEMU JSONL trace.

For convenience, the repo provides:

- Build script: `/Users/zhoubot/LinxCore/standalone/OEX/tools/build_oex_shadow.sh`
- Shadow-check TB: `/Users/zhoubot/LinxCore/standalone/OEX/tb/tb_oex_shadow.cpp`

## 10. Profiles (expected default)

The design must support at least:

- `oex_debug`: small, 2-wide (for quick debug).
- `oex_target`: superscalar target for IPC reporting:
  - `dispatch_w=4`, `issue_width=4`, `commit_w=4`
  - `rob_depth=256`, `ptag_rf_entries=128`, `tu_rf_entries=128`
  - multi-lane AGU/STD (≥2) and at least one CMD/FSU/TPL lane (stubbed execution ok).

The delivered IPC numbers for CoreMark and Dhrystone must be taken under `oex_target`.
