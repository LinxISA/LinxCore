# QEMU Cross-Check Adapter

## Purpose

Phase 0B creates the neutral commit-row path before the full Chisel core exists.
The adapter lets Chisel, pyCircuit, and QEMU traces flow into the existing
`tools/trace/crosscheck_qemu_linxcore.py` comparator without baking producer
details into the comparator itself.

## Tools

- `tools/chisel/trace_schema_adapter.py`
- `tools/chisel/run_chisel_qemu_crosscheck.sh`
- `tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `tools/chisel/run_chisel_top_xcheck.sh`
- `tools/chisel/run_chisel_trace_replay_xcheck.sh`
- `tools/chisel/run_chisel_frontend_trace_top_lint.sh`
- `tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `tools/trace/crosscheck_qemu_linxcore.py`

## Normalized Fields

The adapter emits the mandatory commit fields currently required by the
QEMU/LinxCore comparator:

```text
pc insn len wb_valid wb_rd wb_data
src0_valid src0_reg src0_data
src1_valid src1_reg src1_data
dst_valid dst_reg dst_data
mem_valid mem_is_store mem_addr mem_wdata mem_rdata mem_size
trap_valid trap_cause traparg0 next_pc
```

It also preserves sideband fields when present: `seq`, `cycle`, `slot`, `bid`,
`gid`, `rid`, `rob_valid`, `rob_wrap`, `rob_value`, `block_bid_valid`, and
`block_bid`.

For nested Chisel rows, the adapter accepts `identity.bid`, `identity.gid`,
`identity.rid`, `rob.valid`, `rob.wrap`, `rob.value`, `blockBidValid`,
`blockBid`, `mem.isStore`, `trap.arg0`, and `nextPc`. Rows with `valid: 0` are
filtered before sequence numbering so a fixed-width commit vector can be dumped
without creating false comparator rows.

## QEMU Binary Selection

`run_chisel_qemu_crosscheck.sh` uses this priority:

1. explicit `QEMU=...`
2. `emulator/qemu/build-linx/qemu-system-linx64`
3. legacy `emulator/qemu/build/qemu-system-linx64`

## Gates

```bash
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_trace_replay_xcheck.sh
bash tools/chisel/run_chisel_frontend_trace_top_lint.sh
bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh
```

`run_chisel_trace_replay_xcheck.sh` is the bridge between synthetic reduced
smokes and a live QEMU/CoreMark window. It accepts a flat or nested commit
JSONL with `--input-trace`, normalizes it through `trace_schema_adapter.py`,
replays the bounded row stream through the `LinxCoreTop` commit export in a
Verilator harness, and compares the resulting DUT JSONL against the same rows
as QEMU-shaped reference data. When `--input-trace` is omitted it generates a
four-row fixture covering writeback, store, load, and trap envelopes.

Full compare gate, once a Chisel commit trace exists:

```bash
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace <qemu.jsonl> \
  --dut-trace <chisel.jsonl> \
  --report-dir <report-dir> \
  --max-commits 1000
```

## Current Status

The adapter, wrapper, typed Chisel commit-row bundles, reduced ROB Verilator
smoke, reduced top Verilator smoke, top trace replay smoke,
frontend-window trace-top Verilator lint, frontend-window trace-top Verilator
xcheck, and frontend-window ALU trace-top Verilator xcheck are ready.
`run_chisel_reduced_rob_xcheck.sh` and `run_chisel_top_xcheck.sh` currently
compare three Verilator-produced rows with zero mismatches.
`run_chisel_trace_replay_xcheck.sh` proves that a normalized external commit
row stream can drive the top-level commit observation surface and pass the same
comparator. `run_chisel_frontend_trace_top_xcheck.sh` drives raw frontend
packets containing scalar `ADD`, `ADDI`, and compressed move rows through
`LinxCoreFrontendTraceTop`, uses the explicit completion surrogate to retire
the allocated ROB rows, dumps DUT JSONL, and compares three rows against a
QEMU-shaped reference with zero mismatches.
`run_chisel_frontend_alu_trace_top_xcheck.sh` drives the same reduced frontend
smoke through `LinxCoreFrontendAluTraceTop`, replaces the completion surrogate
with `ReducedScalarAluExecute`, and compares three rows with nonzero source,
destination, and writeback data against QEMU-shaped reference rows with zero
mismatches. Full-core QEMU comparison remains blocked until the Chisel top
emits live architectural commit rows from real fetch, issue, register-file,
LSU, and recovery paths.
