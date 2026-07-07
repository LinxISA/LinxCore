# Replay-LIQ QEMU Candidate Locator

Date: 2026-07-07

## Purpose

`tools/chisel/find_replay_liq_qemu_candidates.py` scans QEMU-shaped commit
JSONL and reports memory-row clusters that may be useful for later natural
replay-LIQ probes. It is intentionally a locator only: it does not prove
replay-LIQ behavior, MDB learning, STQ wait-store state, or DUT equivalence.

The tool exists because R607-R611 showed that early CoreMark prefixes can pass
the generated-RTL/QEMU comparator with zero natural replay-LIQ counters. Before
spending another Verilator build on a wider window, agents should first use
QEMU-only capture plus this locator to identify concrete store/load PC windows.

## Model Evidence

The locator mirrors only address-cluster preconditions from LinxCoreModel LSU
ownership:

- `model/lsu/store_unit/stq.cpp:STQ::lookupForLoad` checks STQ rows for
  address overlap, older-or-equal `(bid, lsID)`, and store-data readiness before
  forwarding or setting wait-store state.
- `model/lsu/store_unit/store_unit.cpp:StoreUnit::insertStq` feeds
  `detect_su_lu_q` on store arrival and wakes load-side waiters when address
  and data are ready.
- `model/lsu/load_unit/ldq.h:ResolveQ` and `model/lsu/load_unit/ldq.cpp`
  connect resolved load records, store-arrival conflict detection, MDB lookup,
  and MDB record/delete queues.

QEMU commit rows do not carry the required model timing or live queue state.
They can identify address reuse, same-cacheline reuse, and approximate PC
windows; generated RTL sideband counters remain the acceptance surface.

## Interface

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/<run>/traces/qemu.live.expected.preview.jsonl \
  --output generated/<run>/report/replay_liq_qemu_candidates.json \
  --top 20 \
  --lookback-rows 1024
```

For later raw intervals that do not form a strict reduced-row prefix, capture
raw QEMU rows only and run the locator directly on `qemu.live.raw.jsonl`:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/<run> \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 512 \
  --qemu-skip-rows 4096 \
  --qemu-raw-only \
  --max-seconds 45 -- \
  -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf

python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/<run>/traces/qemu.live.raw.jsonl \
  --output generated/<run>/report/replay_liq_qemu_candidates.json
```

Useful filters:

- `--min-second-row <n>` ignores early candidates whose later memory row is
  before `n`.
- `--max-second-row <n>` caps the later memory row.
- `--exact-overlap-only` drops same-line-only candidates.
- `--no-dedupe-pairs` shows repeated dynamic instances of the same PC/address
  pair.
- `--self-test` runs the built-in synthetic store/load overlap check.

Wrapper sampling knobs:

- `--qemu-skip-rows <n>` discards filtered QEMU rows before writing the bounded
  capture. It is allowed only with `--qemu-only`.
- `--qemu-raw-only` exits after raw QEMU capture and skips the reduced-row
  extractor. It is for arbitrary skipped intervals whose first row may not be a
  strict sequential reduced prefix.

For repeated later-window probes, use the interval scanner instead of
hand-written shell loops:

```bash
python3 tools/chisel/scan_replay_liq_qemu_intervals.py \
  --elf tests/benchmarks/build/coremark_real.elf \
  --build-dir generated/<run> \
  --skips 4096,16384,65536,262144 \
  --capture-rows 2048 \
  --max-seconds 60
```

The scanner writes one subdirectory per skip interval, preserves each wrapper
stdout/stderr log, runs the locator on `qemu.live.raw.jsonl`, and emits
`report/interval_scan_summary.json` with schema
`linxcore.replay_liq_qemu_interval_scan.v1`. Its output has the same claim
boundary as the locator: skipped raw QEMU intervals are candidate hints only.
If the wrapped QEMU command leaves a complete raw trace but does not return,
the scanner records `wrapper_timed_out=true`, terminates the wrapper process
group, and still runs the locator on the complete bounded trace.

The JSON report schema is
`linxcore.replay_liq_qemu_candidate_locator.v1`. Its `claim_boundary` field is
part of the contract: candidate output is not QEMU/DUT proof.

## Logic Design

The locator parses rows with `mem_valid=1` into memory events:

- row index and cycle,
- PC and instruction bits,
- load/store class,
- byte address, size, cacheline, and memory data.

For each later memory event, it scans prior memory events within
`--lookback-rows` and records:

- `store_before_load`: possible STQ lookup/forwarding/wait-store candidate,
- `load_before_store`: possible store-arrival MDB conflict candidate.

Candidates score exact byte overlap highest, then same-cacheline reuse, then
short row distance. Default output deduplicates repeated dynamic instances by
kind, PC pair, and address/cacheline pair so the top list is suitable for
window selection.

## R612 Evidence

R612 ran a QEMU-only CoreMark capture:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r612-coremark-qemu-memory-candidates-16384 \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 16384 \
  --allow-block-markers --allow-block-loop-reentry \
  --qemu-only --max-seconds 45 -- \
  -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The reducer produced 16,250 expected rows. Locator results:

- all-window scan: 1,423 memory events, 1,243 stores, 180 loads, 94 deduped
  candidates;
- top exact store-before-load candidate:
  rows `1585 -> 1589`, PCs `0x4000d7e6 -> 0x4000d7f2`, address `0x4ffefb68`;
- after-row-4096 scan: 868 memory events, all stores, zero candidates.

This is negative later-window evidence for the first 16K-row prefix. The next
natural CoreMark replay-LIQ search should not simply widen the same early
prefix by a small factor; it needs a way to skip into a later load-bearing
phase, locate a different direct-boot interval, or return to focused replay
fixtures for positive replacement evidence.

## R613 Evidence

R613 adds `--qemu-skip-rows` and `--qemu-raw-only` to
`run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`.

The non-QEMU-only guard rejects skipped captures:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 8 \
  --qemu-skip-rows 1
```

The wrapper exits with:

```text
error: --qemu-skip-rows is allowed only with --qemu-only
```

A raw skipped sample after the R611 boundary:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r613-coremark-qemu-raw-skip4096-sample \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 512 \
  --qemu-skip-rows 4096 \
  --allow-block-markers --allow-block-loop-reentry \
  --qemu-raw-only --max-seconds 45 -- \
  -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

Result: 512 raw rows were captured after 4096 skipped rows. The locator found
37 memory events, all stores, zero loads, and zero candidates. This confirms
the R612 post-4096 store-only shape with a smaller interval artifact and gives
future agents a cheap loop for later interval sampling.

## R614 Evidence

R614 adds `tools/chisel/scan_replay_liq_qemu_intervals.py` and manually samples
larger skipped CoreMark intervals before closing the packet:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r614-coremark-qemu-raw-skip16384-sample \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 2048 \
  --qemu-skip-rows 16384 \
  --allow-block-markers --allow-block-loop-reentry \
  --qemu-raw-only --max-seconds 60 -- \
  -nographic -monitor none -machine virt -m 1280M \
  -kernel tests/benchmarks/build/coremark_real.elf
```

The same command shape was repeated for skip offsets 65,536 and 262,144. Each
interval captured 2,048 raw rows. Locator results:

| Build directory | Memory events | Stores | Loads | Candidates | Dominant memory PC |
|---|---:|---:|---:|---:|---|
| `generated/r614-coremark-qemu-raw-skip16384-sample` | 146 | 146 | 0 | 0 | `0x4000d710` |
| `generated/r614-coremark-qemu-raw-skip65536-sample` | 146 | 146 | 0 | 0 | `0x40006310` |
| `generated/r614-coremark-qemu-raw-skip262144-sample` | 146 | 146 | 0 | 0 | `0x40006310` |

This is additional negative interval-selection evidence. It does not supersede
R611 generated-RTL/QEMU no-regression evidence and does not prove replay-LIQ
behavior. The next agent should either run a broader QEMU-only scanner sweep
with non-adjacent skips to find a load-bearing phase, or return to the focused
replay fixtures that already produce positive retained physical-bundle
sidebands.
