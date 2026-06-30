# QEMU Cross-Check Adapter

## Purpose

Phase 0B creates the neutral commit-row path before the full Chisel core exists.
The adapter lets Chisel, pyCircuit, and QEMU traces flow into the existing
`tools/trace/crosscheck_qemu_linxcore.py` comparator without baking producer
details into the comparator itself.

## Tools

- `tools/chisel/commit_trace_jsonl.h`
- `tools/chisel/trace_schema_adapter.py`
- `tools/chisel/run_chisel_qemu_crosscheck.sh`
- `tools/chisel/run_chisel_reduced_rob_xcheck.sh`
- `tools/chisel/run_chisel_top_xcheck.sh`
- `tools/chisel/run_chisel_trace_replay_xcheck.sh`
- `tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh`
- `tools/chisel/run_chisel_frontend_trace_top_lint.sh`
- `tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh`
- `tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh`
- `tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh`
- `tools/trace/crosscheck_qemu_linxcore.py`

## Evidence Manifest

For non-dry-run comparisons, `run_chisel_qemu_crosscheck.sh` now writes
`crosscheck_manifest.json` next to the comparator reports. The manifest schema
is `linxcore.chisel.crosscheck_manifest.v1` and records:

- selected QEMU binary,
- raw QEMU and DUT trace paths,
- normalized QEMU and DUT trace paths,
- comparator report paths,
- row counts, mismatch count, first mismatch, and CBSTOP inflation summary,
- `max_commits` plus the raw `normalize_rows` window used before metadata
  filtering,
- `rtl/LinxCore` and superproject `HEAD` plus dirty flags.

The wrapper preserves the comparator exit status after writing the manifest.
On a mismatch, the manifest is still emitted with `status: "fail"` so agents
can archive the complete evidence bundle before debugging the first
divergence.

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

## Shared C++ Writer

Generated-RTL Verilator harnesses should emit commit JSONL through
`tools/chisel/commit_trace_jsonl.h` instead of open-coding JSON strings. The
helper owns two wire formats:

- `write_qemu_commit_jsonl`: architectural fields only, matching the QEMU
  commit trace shape in `target/linx/helper.c` and the trace-manager plugin.
- `write_dut_commit_jsonl`: the same architectural fields plus fixed-width
  Chisel sidebands: `valid`, `seq`, `cycle`, `slot`, `bid/gid/rid`,
  `rob_valid/rob_wrap/rob_value`, `block_bid_valid`, and `block_bid`.

Harness-specific code still owns how it reads Verilated pins and how it builds
expected rows. The shared writer only owns spelling, default zeros, and boolean
encoding. This preserves the LinxCoreModel split between 32-bit
`CommitInfo(bid,gid,rid)` identity and the 64-bit hardware `block_bid` while
future live Chisel tops are wired into the same neutral comparator path.

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
bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh --dry-run
bash tools/chisel/run_chisel_frontend_trace_top_lint.sh
bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
bash tools/chisel/run_chisel_frontend_fetch_trace_top_xcheck.sh
bash tools/chisel/run_chisel_frontend_alu_trace_top_xcheck.sh
bash tools/chisel/run_chisel_frontend_rf_alu_trace_top_xcheck.sh
```

`run_chisel_trace_replay_xcheck.sh` is the bridge between synthetic reduced
smokes and a live QEMU/CoreMark window. It accepts a flat or nested commit
JSONL with `--input-trace`, normalizes it through `trace_schema_adapter.py`,
replays the bounded row stream through the `LinxCoreTop` commit export in a
Verilator harness, and compares the resulting DUT JSONL against the same rows
as QEMU-shaped reference data. When `--input-trace` is omitted it generates a
four-row fixture covering writeback, store, load, and trap envelopes.

`run_chisel_qemu_trace_replay_xcheck.sh` is the QEMU-facing replay bridge. In
`--elf` mode it first runs `tools/qemu/run_qemu_commit_trace.sh` to collect a
QEMU JSONL trace, then runs `run_chisel_trace_replay_xcheck.sh` in
`generated/chisel-qemu-trace-replay-xcheck`. In `--qemu-trace` mode it skips
QEMU execution and replays an existing QEMU JSONL trace. Both paths preserve
the comparator `crosscheck_manifest.json` under the isolated report directory.
The bridge first normalizes a wider raw window, then slices the replay input to
the smallest prefix that contains the requested number of non-metadata
architectural rows. This keeps QEMU BSTART/template/control metadata from
starving the compare window while avoiding extra DUT tail rows after the
bounded compare point.

In `--elf` mode, `--replay-rows` also bounds the raw QEMU prefix collected from
the FIFO before the wrapper stops QEMU. If QEMU exits before opening or
producing the FIFO, the reader is killed and the wrapper must fail with an
empty-trace error rather than hanging. Direct-boot CoreMark-style ET_DYN images
currently carry physical load segments at `0x40000000`, while the Linx QEMU
`virt` machine defaults to 128 MiB of RAM. Use an explicit memory argument for
that class of ELF:

```bash
bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh \
  --build-dir generated/r91-coremark-elf-prefix \
  --elf tests/benchmarks/build/coremark_real.elf \
  --max-commits 4 \
  --replay-rows 128 \
  --max-seconds 10 \
  -- -nographic -monitor none -machine virt -m 1280M \
     -kernel tests/benchmarks/build/coremark_real.elf
```

R91 evidence for that command captured 128 raw QEMU rows, sliced 5 replay rows
containing 4 architectural commits, and passed the Chisel replay cross-check
with `compared_rows: 4` and `mismatch_count: 0`.

The common wrapper exposes the same distinction directly:

```bash
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace <qemu.jsonl> \
  --dut-trace <dut.jsonl> \
  --max-commits 4 \
  --normalize-rows 8
```

`--max-commits` is the architectural compare window. `--normalize-rows` is the
raw row window presented to schema normalization before comparator metadata
filtering.

QEMU-row replay gate:

```bash
bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh \
  --elf <direct-boot-smoke.elf> \
  --max-commits 32 \
  --max-seconds 30
```

Full compare gate, once a Chisel commit trace exists:

```bash
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace <qemu.jsonl> \
  --dut-trace <chisel.jsonl> \
  --report-dir <report-dir> \
  --max-commits 1000
```

Expected report outputs:

```text
<report-dir>/qemu.normalized.jsonl
<report-dir>/dut.normalized.jsonl
<report-dir>/crosscheck_report.json
<report-dir>/crosscheck_report.md
<report-dir>/crosscheck_mismatches.json
<report-dir>/crosscheck_manifest.json
```

## Current Status

The adapter, shared C++ commit JSONL writer, wrapper, typed Chisel commit-row
bundles, reduced ROB Verilator smoke, reduced top Verilator smoke, top trace
replay smoke, QEMU trace replay bridge,
frontend-window trace-top Verilator lint, frontend-window trace-top Verilator
xcheck, live frontend fetch trace-top Verilator xcheck, frontend-window ALU
trace-top Verilator xcheck, and RF-backed ALU
trace-top Verilator xcheck are ready. The common wrapper emits a
machine-readable `crosscheck_manifest.json` for generated-RTL comparisons and
future full QEMU-vs-DUT windows.
`run_chisel_reduced_rob_xcheck.sh` and `run_chisel_top_xcheck.sh` currently
compare three Verilator-produced rows with zero mismatches.
`run_chisel_trace_replay_xcheck.sh` proves that a normalized external commit
row stream can drive the top-level commit observation surface and pass the same
comparator. `run_chisel_qemu_trace_replay_xcheck.sh` can feed archived or fresh
QEMU rows into that same replay path while the live Chisel top is still being
connected. `run_chisel_frontend_trace_top_xcheck.sh` drives raw frontend
packets containing scalar `ADD`, `ADDI`, and compressed move rows through
`LinxCoreFrontendTraceTop`, uses the explicit completion surrogate to retire
the allocated ROB rows, dumps DUT JSONL, and compares three rows against a
QEMU-shaped reference with zero mismatches.
`run_chisel_frontend_fetch_trace_top_xcheck.sh` drives a bounded instruction
window fixture through `FrontendFetchPacketSource`, F4, and the reduced
decode/ROB path in `LinxCoreFrontendFetchTraceTop`, uses the explicit
completion surrogate to retire allocated ROB rows, and compares three rows
against a QEMU-shaped reference with zero mismatches. Its manifest under
`generated/chisel-frontend-fetch-trace-top-xcheck/report` records
`status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
`run_chisel_frontend_alu_trace_top_xcheck.sh` drives the same reduced frontend
smoke through `LinxCoreFrontendAluTraceTop`, replaces the completion surrogate
with `ReducedScalarAluExecute`, and compares three rows with nonzero source,
destination, and writeback data against QEMU-shaped reference rows with zero
mismatches. `run_chisel_frontend_rf_alu_trace_top_xcheck.sh` drives dependent
scalar rows through `LinxCoreFrontendRfAluTraceTop`, preloads only identity RF
registers, enqueues rows through the reduced issue queue before draining
commits, reads later sources from Chisel physical RF writeback state, and
compares three rows with zero mismatches. Its report directory now includes a
manifest with `status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
The QEMU trace replay bridge now has bounded live-ELF prefix evidence using
`tests/benchmarks/build/coremark_real.elf` with explicit `-m 1280M`; the
default 128 MiB QEMU run fails fast with an empty-trace error because the ELF
segment at `0x40000000` does not fit.
Full-core QEMU comparison remains blocked until the Chisel top emits live
architectural commit rows from real fetch, full issue, LSU, and recovery paths.
