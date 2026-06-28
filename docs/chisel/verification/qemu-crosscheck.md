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
```

Full compare gate, once a Chisel commit trace exists:

```bash
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace <qemu.jsonl> \
  --dut-trace <chisel.jsonl> \
  --report-dir <report-dir> \
  --max-commits 1000
```

## Current Status

The adapter, wrapper, typed Chisel commit-row bundles, and reduced ROB
Verilator smoke are ready. `run_chisel_reduced_rob_xcheck.sh` currently compares
three Verilator-produced reduced ROB rows with zero mismatches. Full-core QEMU
comparison remains blocked until the Chisel top emits live architectural commit
rows from real frontend/decode/execute/LSU paths.
