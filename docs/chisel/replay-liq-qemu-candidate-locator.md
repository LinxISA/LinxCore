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
  --raw-input generated/<run>/traces/qemu.live.raw.jsonl \
  --output generated/<run>/report/replay_liq_qemu_candidates.json \
  --top 20 \
  --lookback-rows 1024
```

Use `--raw-input` when scanning a reduced preview and the matching raw trace is
available. The reduced preview can filter or skip rows relative to the raw QEMU
stream; its candidate row numbers are therefore not safe `--qemu-skip-rows`
arguments by themselves. With `--raw-input`, each top candidate includes a
`probe_hint.raw_dynamic_window` with absolute raw `--qemu-skip-rows` and
`--capture-rows` arguments for QEMU-only reproduction.

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
  --skip-range 524288:2097152:524288 \
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
Use `--skip-range START:STOP:STEP` for inclusive non-adjacent sweeps; repeated
ranges are appended to explicit `--skips` and duplicate offsets are removed
while preserving first occurrence order. The summary includes aggregate
`load_interval_count`, `candidate_interval_count`, `first_load_interval`, and
`first_candidate_interval` fields so agents can tell whether a sweep found a
usable phase without hand-inspecting every per-interval report.

The JSON report schema is
`linxcore.replay_liq_qemu_candidate_locator.v1`. Its `claim_boundary` field is
part of the contract: candidate output is not QEMU/DUT proof. R617 adds
`row_space` and per-candidate `probe_hint` fields. `probe_hint.pc_filter`
contains a PC-range preflight fragment, but PC filtering can select an earlier
dynamic occurrence of the same PC range. Run the QEMU-only wrapper with the
candidate's `expected_memory_pcs.args` before spending generated-RTL time on
that range. `probe_hint.raw_dynamic_window` is only QEMU-only dynamic-window
reproduction; skipped raw rows are not generated-RTL replacement evidence.

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

## R615 Evidence

R615 extends the scanner interface with inclusive skip ranges and aggregate
summary fields, then runs a bounded non-adjacent sweep:

```bash
python3 tools/chisel/scan_replay_liq_qemu_intervals.py \
  --elf tests/benchmarks/build/coremark_real.elf \
  --build-dir generated/r615-coremark-qemu-interval-scan \
  --skips 524288 \
  --skip-range 1048576:2097152:524288 \
  --capture-rows 256 \
  --max-seconds 45 \
  --wrapper-timeout-seconds 20 \
  --stop-on-load
```

The summary artifact is
`generated/r615-coremark-qemu-interval-scan/report/interval_scan_summary.json`.
It reports schema `linxcore.replay_liq_qemu_interval_scan.v1`,
`scanned_interval_count=4`, `load_interval_count=0`, and
`candidate_interval_count=0`. Each interval completed the bounded raw trace and
timed out only at the wrapper process boundary after capture:

| Skip rows | Raw rows | Memory events | Stores | Loads | Candidates |
|---:|---:|---:|---:|---:|---:|
| 524,288 | 256 | 18 | 18 | 0 | 0 |
| 1,048,576 | 256 | 18 | 18 | 0 | 0 |
| 1,572,864 | 256 | 18 | 18 | 0 | 0 |
| 2,097,152 | 256 | 18 | 18 | 0 | 0 |

This is still candidate-location evidence only. The broader sampled CoreMark
steady-state intervals do not expose natural replay-LIQ load clusters, so the
next positive-proof packet should return to focused replay fixtures unless a
new QEMU-only interval-selection hypothesis is being tested.

## R617 Evidence

R617 annotates locator candidates with raw-row probe hints. Re-running the R612
reduced-preview candidate scan with the matching raw trace:

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/r612-coremark-qemu-memory-candidates-16384/traces/qemu.live.expected.preview.jsonl \
  --raw-input generated/r612-coremark-qemu-memory-candidates-16384/traces/qemu.live.raw.jsonl \
  --output generated/r617-coremark-qemu-memory-candidate-hints/replay_liq_qemu_candidates_with_raw_hints.json \
  --top 5 \
  --lookback-rows 1024
```

The top R612 reduced-preview candidate remains the exact store-before-load pair
at PCs `0x4000d7e6 -> 0x4000d7f2`, address `0x4ffefb68`, but it now carries
`probe_hint.raw_dynamic_window.args = ["--qemu-skip-rows", "1715",
"--capture-rows", "6"]`. This matters because the candidate's reduced rows
`1585 -> 1589` are not raw row offsets.

The matching QEMU-only scanner gate:

```bash
python3 tools/chisel/scan_replay_liq_qemu_intervals.py \
  --elf tests/benchmarks/build/coremark_real.elf \
  --build-dir generated/r617-coremark-qemu-candidate-hint-scan \
  --skips 1715 \
  --capture-rows 6 \
  --lookback-rows 8 \
  --exact-overlap-only \
  --no-dedupe-pairs \
  --top 3 \
  --max-seconds 45 \
  --wrapper-timeout-seconds 20 \
  --stop-on-candidate
```

It reports one interval, one load-bearing interval, and one candidate-bearing
interval. The per-interval candidate report repeats the absolute raw dynamic
window `--qemu-skip-rows 1715 --capture-rows 6`.

R617 also ran a PC-filter preflight on `0x4000d7e6..0x4000d7f3` with expected
store PC `0x4000d7e6` and expected load PC `0x4000d7f2`. That preflight failed
because the first dynamic occurrence of the PC range does not include the load.
Do not convert this candidate into a generated-RTL CoreMark run by PC filter
alone; first find a stateful unskipped capture strategy or prove a QEMU-only
expected-memory-PC preflight for the exact generated-RTL command shape.

## R618 Context Pack

R618 adds a separate context-pack validator rather than changing the locator
claim. Run:

```bash
python3 tools/chisel/build_replay_liq_selector_context_pack.py
python3 tools/chisel/build_replay_liq_selector_context_pack.py \
  --validate-only generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json
```

The manifest combines R611 zero-natural CoreMark no-regression evidence, R616
positive focused selector-origin proof, and the R617 raw-window hint. It is
valid only if those remain distinct: the R617 raw dynamic window is an
address-cluster hint, not generated-RTL/DUT replay-LIQ proof.

## R619 Probe Plan

R619 adds a command planner on top of the R618 context pack:

```bash
python3 tools/chisel/plan_replay_liq_selector_probe.py --print-commands
```

The planner emits QEMU-only preflight commands for the raw window and PC-filter
forms, each guarded by the expected store/load PCs. It also emits
`generated_rtl.status = "blocked"`. This is the required handoff shape until a
future packet proves that the exact generated-RTL command shape has a passing
QEMU-only expected-memory-PC preflight.

## R620 Preflight Evidence

R620 runs the safe preflights and records the results:

```bash
python3 tools/chisel/build_replay_liq_selector_preflight_report.py
python3 tools/chisel/build_replay_liq_selector_preflight_report.py \
  --validate-only generated/r620-replay-liq-selector-preflight-report/report/replay_liq_selector_preflight_report.json
```

The raw skipped-window QEMU-only preflight passes with 6 raw rows, 5 reduced
preview rows, store PC `0x4000d7e6`, load PC `0x4000d7f2`, and memory address
`0x4ffefb68`. The PC-filter form captures zero rows in the fresh bounded run,
so the report keeps generated RTL blocked. This reinforces the same boundary:
the raw window is a reproducible QEMU candidate hint, not DUT replay-LIQ proof.

## R621 Unskipped Prefix Evidence

R621 proves that the R617 top candidate can be reached without skipped QEMU
rows and records the generated-RTL boundary:

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.expected.preview.jsonl \
  --raw-input generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.raw.jsonl \
  --output generated/r621-coremark-unskipped-1721-qemu-preflight/report/replay_liq_qemu_candidates.json \
  --top 5 \
  --lookback-rows 1024
python3 tools/chisel/build_replay_liq_selector_unskipped_prefix_report.py
python3 tools/chisel/build_replay_liq_selector_unskipped_prefix_report.py \
  --validate-only generated/r621-replay-liq-selector-unskipped-prefix-report/report/replay_liq_selector_unskipped_prefix_report.json
```

The unskipped QEMU-only preflight captures 1721 raw rows and reduces 1590
preview rows. The top candidate remains the exact store-before-load pair
`0x4000d7e6 -> 0x4000d7f2` at address `0x4ffefb68`, rows `1585 -> 1589`,
score 1186. The matching unskipped generated-RTL/QEMU CoreMark prefix passes
with 1169 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows, and
the generated-RTL preview contains the same target pair.

This is the first safe generated-RTL command shape for the candidate, but the
sideband counters still report zero natural replay-LIQ/MDB activity:
`liq_alloc_accepted=0`, `replay_queue_out_fire=0`, `lret_w2_slot_accepted=0`,
`w2_promotion_live=0`, selector-from-promotion/probe counters are zero, and
MDB fanout/record counters are zero. Treat R621 as candidate-present
no-regression coverage, not replay-LIQ replacement proof.

## R622 Activation Gap Report

R622 records why R621 does not activate replay-LIQ:

```bash
python3 tools/chisel/build_replay_liq_activation_gap_report.py
python3 tools/chisel/build_replay_liq_activation_gap_report.py \
  --validate-only generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json
```

The report consumes the R621 unskipped-prefix report plus the R621 generated-RTL
sideband stats. It classifies the run as memory-path active but pre-ResolveQ:
`load_lookup_valid=180`, `store_stq_resident=512`, and store dequeue counters
are nonzero, while `resident_store_eligible=0`,
`load_lookup_execute_with_eligible_store=0`,
`load_lookup_execute_with_wait_store=0`, `resolve_queue_push_accepted=0`,
`resolve_queue_valid=0`, `mdb_conflict_valid=0`,
`wait_replay_capture_accepted=0`, and `liq_alloc_accepted=0`.

The next generated-RTL replay-LIQ proof attempt must first find or construct a
run where `load_lookup_execute_with_eligible_store > 0`; otherwise QEMU
store/load address clusters remain commit-stream hints rather than live
resident-store overlap stimuli.

## R623 Focused Eligible-Store Proof

R623 constructs the missing eligible-store stimulus on the focused replay
fixture and records the proof boundary:

```bash
LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_PROMOTE=1 \
LINXCORE_REPLAY_LIQ_RETAINED_OWNER_PHYSICAL_SUPPRESS_LIVE_MASK=1 \
FETCH_REPLAY_LIQ_REQUIRE_PRESET=replay-physical-suppress-selector-origin \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --fixture replay-ldi-sdi-ldi-sdi-ldi-ldi-loop \
  --build-dir generated/r623-replay-eligible-store-focused-xcheck \
  --expected-rows 18 \
  --capture-rows 32 \
  --max-seconds 10 \
  --reduced-store-replay-liq \
  --disable-store-memory-mutation \
  --allow-residual-replay-liq-wait
python3 tools/chisel/build_replay_liq_eligible_store_proof_report.py
python3 tools/chisel/build_replay_liq_eligible_store_proof_report.py \
  --validate-only generated/r623-replay-liq-eligible-store-proof-report/report/replay_liq_eligible_store_proof_report.json
```

The generated-RTL/QEMU run passes with 18 compared rows, zero mismatches, and
zero QEMU/DUT CBSTOP rows. Sideband counters prove the focused activation
chain: `load_lookup_execute_with_eligible_store=18`,
`load_lookup_execute_with_wait_store=12`, `resident_store_eligible=18`,
`resolve_queue_push_accepted=8`, `resolve_queue_valid=66`,
`mdb_conflict_valid=6`, `mdb_fanout_record_valid=6`,
`wait_replay_capture_accepted=12`, `liq_alloc_accepted=6`,
`replay_queue_out_fire=6`, `lret_w2_slot_accepted=6`, and
`w2_promotion_live=5`.

Treat R623 as current-head focused-fixture replay-LIQ activation proof. It does
not replace the R621/R622 CoreMark boundary: natural CoreMark replacement still
requires the same nonzero activation counters in a CoreMark or natural workload
generated-RTL run.
