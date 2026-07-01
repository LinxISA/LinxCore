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
- `tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
- `tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`
- `tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh`
- `tools/chisel/frontend_fetch_rf_alu_qemu_rows.py`
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
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r100-live-qemu-fixture
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 3 --capture-rows 3 --pc-lo 0x10002 --pc-hi 0x1000b \
  --max-seconds 5
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh --out-dir generated/r101-live-qemu-fixture
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 --capture-rows 5 --allow-block-markers \
  --max-seconds 5
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
Metadata filtering must not drop non-sequential control rows. Side-effect-free
macro/template rows are treated as metadata only when `next_pc == pc + len`;
rows such as `FRET.STK` carry architectural redirect evidence through
`next_pc` and must remain in the compare stream.

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

For bounded windows, comparator length handling is metadata-aware. If QEMU and
DUT both terminate after the same metadata-filtered architectural prefix before
`--max-commits`, the compare passes; this is the expected shape for finite
expected-row files that still contain side-effect-free marker/sysreg metadata.
The comparator still fails if the DUT has extra architectural rows or if QEMU
has remaining architectural rows while the DUT ended before the requested
bounded window.

QEMU-row replay gate:

```bash
bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh \
  --elf <direct-boot-smoke.elf> \
  --max-commits 32 \
  --max-seconds 30
```

Reduced live-QEMU ELF gate:

```bash
bash tools/chisel/build_frontend_fetch_rf_alu_qemu_fixture_elf.sh \
  --out-dir generated/r100-live-qemu-fixture
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r100-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 3 \
  --capture-rows 3 \
  --pc-lo 0x10002 \
  --pc-hi 0x1000b \
  --max-seconds 5
```

This gate captures a bounded QEMU JSONL prefix through a FIFO, validates the
selected rows with `frontend_fetch_rf_alu_qemu_rows.py`, extracts the same ELF
PT_LOAD bytes with `FETCH_ELF`, and compares the live fetch RF/ALU DUT rows
through the common manifest-producing comparator. The PC range skips the legal
entry `BSTART` for now; block-header execution is a later DUT feature. QEMU
termination after the bounded rows are captured is expected only when the
manifest reports `status: "pass"`.

R101 adds the reduced block-marker form:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf generated/r101-live-qemu-fixture/frontend_fetch_rf_alu_qemu_fixture.elf \
  --expected-rows 0 \
  --capture-rows 5 \
  --allow-block-markers \
  --max-seconds 5
```

`--allow-block-markers` passes legal `BSTART`/`BSTOP` rows through the
expected-row stream as `skip` entries. The Verilator harness fetches and
decodes them, asserts the reduced marker diagnostics, and requires no ROB
allocation or issue enqueue for those rows. The comparator receives only the
non-skip scalar architectural rows. R101 evidence captured five raw QEMU rows
for `C.BSTART; ADD; ADDI; C.MOVR; C.BSTOP`, compared three scalar commits,
and produced a manifest with `status: "pass"`, `compared_rows: 3`, and
`mismatch_count: 0`.

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
trace-top Verilator xcheck, RF-backed ALU trace-top Verilator xcheck, live
frontend fetch RF-backed ALU trace-top Verilator xcheck, and reduced live
QEMU ELF fetch RF/ALU xcheck are ready. The common
wrapper emits a machine-readable `crosscheck_manifest.json` for generated-RTL
comparisons and future full QEMU-vs-DUT windows.
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
`run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` drives the same
dependent scalar smoke through `LinxCoreFrontendFetchRfAluTraceTop`, replaces
testbench-supplied frontend packets with a live PC request/response source,
serves instruction bytes from a `FETCH_MEMORY_BIN`, `FETCH_MEMORY_HEX`, or
`FETCH_ELF` image, reads expected PC/length/source/writeback rows from
QEMU-shaped JSONL selected by `FETCH_EXPECTED_ROWS`, and preserves RF-backed
reduced issue and ALU completion. When `FETCH_EXPECTED_ROWS` is unset, the
wrapper emits `fixture.expected.jsonl` and sizes `--max-commits` from the
expected row count. It can also derive that expected-row stream from an
existing QEMU commit JSONL with `FETCH_QEMU_TRACE`; the helper writes
`qemu.expected.jsonl` only after validating a strict sequential reduced-scalar
prefix (`ADD`, `ADDI`, `C.MOVI`, or `C.MOVR`, scalar GPRs only, no memory or
trap side effects).
Its manifest under
`generated/chisel-frontend-fetch-rf-alu-trace-top-xcheck/report` records
`status: "pass"`, `compared_rows: 3`, and `mismatch_count: 0`.
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh` captures the legal-entry
fixture ELF through live QEMU, either filters the scalar prefix after
`BSTART` or preserves `BSTART`/`BSTOP` as reduced skip rows, runs the R99/R101
row reducer plus R97 sparse ELF memory path, and passes with `status: "pass"`,
`compared_rows: 3`, and `mismatch_count: 0` under
`generated/chisel-frontend-fetch-rf-alu-qemu-elf-xcheck/report`.
Dense packets and non-ALU row enrichment remain later packets.
The QEMU trace replay bridge now has bounded live-ELF prefix evidence using
`tests/benchmarks/build/coremark_real.elf` with explicit `-m 1280M`; the
default 128 MiB QEMU run fails fast with an empty-trace error because the ELF
segment at `0x40000000` does not fit.
Full-core QEMU comparison remains blocked until the Chisel top emits live
architectural commit rows from real fetch, full issue, LSU, and recovery paths.
