# Replay-LIQ QEMU Candidate Locator

Date: 2026-07-07

Status: historical archive. Task 15 deleted the replay-LIQ locator, scanners,
report builders, and specialized RF/ALU QEMU wrappers described on this page.
No supported current equivalent exists for those workflows. Their commands,
schemas, and results remain here only as historical evidence and are not
runnable from the current tree. The surviving
`tools/chisel/build_frontend_fetch_rf_seed.py` helper does not restore the
deleted downstream replay flows.

## Purpose

`tools/chisel/find_replay_liq_qemu_candidates.py` scanned QEMU-shaped commit
JSONL and reported memory-row clusters that could be useful for later natural
replay-LIQ probes. It was intentionally a locator only: it did not prove
replay-LIQ behavior, MDB learning, STQ wait-store state, or DUT equivalence.

The tool existed because R607-R611 showed that early CoreMark prefixes could
pass the generated-RTL/QEMU comparator with zero natural replay-LIQ counters.
The historical workflow used QEMU-only capture plus this locator to identify
concrete store/load PC windows before spending another Verilator build on a
wider window.

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

## Historical Interface

The following locator invocation was valid before Task 15 deleted the script.
It has no supported current equivalent:

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/<run>/traces/qemu.live.expected.preview.jsonl \
  --raw-input generated/<run>/traces/qemu.live.raw.jsonl \
  --output generated/<run>/report/replay_liq_qemu_candidates.json \
  --top 20 \
  --lookback-rows 1024
```

Historical command provenance: source commit
`7e2931bc6550ccf344b6d367e672f978381dca58`.

The historical workflow used `--raw-input` when scanning a reduced preview and
the matching raw trace was available. The reduced preview could filter or skip
rows relative to the raw QEMU stream; its candidate row numbers therefore were
not safe `--qemu-skip-rows` arguments by themselves. With `--raw-input`, each
top candidate included a
`probe_hint.raw_dynamic_window` with absolute raw `--qemu-skip-rows` and
`--capture-rows` arguments for QEMU-only reproduction.

For later raw intervals that did not form a strict reduced-row prefix, the
historical workflow captured raw QEMU rows and ran the locator directly on
`qemu.live.raw.jsonl`. Both programs in this preserved command are absent, and
there is no supported current procedure:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

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
Provenance: source commit 39902e672a8c1fb75a0eeb02b5280c12a89935b3.
<!-- task15-historical-specialized-evidence:end -->

The deleted locator accepted these filters:

- `--min-second-row <n>` ignored early candidates whose later memory row was
  before `n`.
- `--max-second-row <n>` capped the later memory row.
- `--exact-overlap-only` dropped same-line-only candidates.
- `--no-dedupe-pairs` showed repeated dynamic instances of the same PC/address
  pair.
- `--self-test` ran the built-in synthetic store/load overlap check.

The deleted wrapper accepted these sampling knobs:

- `--qemu-skip-rows <n>` discarded filtered QEMU rows before writing the bounded
  capture. It was allowed only with `--qemu-only`.
- `--qemu-raw-only` exited after raw QEMU capture and skipped the reduced-row
  extractor. It supported arbitrary skipped intervals whose first row might
  not be a strict sequential reduced prefix.

For repeated later-window probes, the historical workflow used the interval
scanner instead of hand-written shell loops. The scanner is absent and has no
supported current equivalent:

```bash
python3 tools/chisel/scan_replay_liq_qemu_intervals.py \
  --elf tests/benchmarks/build/coremark_real.elf \
  --build-dir generated/<run> \
  --skips 4096,16384,65536,262144 \
  --skip-range 524288:2097152:524288 \
  --capture-rows 2048 \
  --max-seconds 60
```

Historical command provenance: source commit
`462b359a5bd6620585ea14d7706e70345345bc74`.

The scanner wrote one subdirectory per skip interval, preserved each wrapper
stdout/stderr log, ran the locator on `qemu.live.raw.jsonl`, and emitted
`report/interval_scan_summary.json` with schema
`linxcore.replay_liq_qemu_interval_scan.v1`. Its output had the same claim
boundary as the locator: skipped raw QEMU intervals were candidate hints only.
If the wrapped QEMU command left a complete raw trace but did not return, the
scanner recorded `wrapper_timed_out=true`, terminated the wrapper process
group, and still ran the locator on the complete bounded trace.
`--skip-range START:STOP:STEP` represented inclusive non-adjacent sweeps;
repeated ranges were appended to explicit `--skips`, and duplicate offsets were
removed while preserving first-occurrence order. The summary included aggregate
`load_interval_count`, `candidate_interval_count`, `first_load_interval`, and
`first_candidate_interval` fields so agents could tell whether a sweep found a
usable phase without hand-inspecting every per-interval report.

The JSON report used schema
`linxcore.replay_liq_qemu_candidate_locator.v1`. Its `claim_boundary` field was
part of the contract: candidate output was not QEMU/DUT proof. R617 added
`row_space` and per-candidate `probe_hint` fields. `probe_hint.pc_filter`
contained a PC-range preflight fragment, but PC filtering could select an
earlier dynamic occurrence of the same PC range. The historical workflow ran
the QEMU-only wrapper with the candidate's `expected_memory_pcs.args` before
spending generated-RTL time on that range. `probe_hint.raw_dynamic_window` was
only QEMU-only dynamic-window reproduction; skipped raw rows were not
generated-RTL replacement evidence. That preflight workflow has no supported
current equivalent.

## Logic Design

The locator parsed rows with `mem_valid=1` into memory events:

- row index and cycle,
- PC and instruction bits,
- load/store class,
- byte address, size, cacheline, and memory data.

For each later memory event, it scanned prior memory events within
`--lookback-rows` and recorded:

- `store_before_load`: possible STQ lookup/forwarding/wait-store candidate,
- `load_before_store`: possible store-arrival MDB conflict candidate.

Candidates scored exact byte overlap highest, then same-cacheline reuse, then
short row distance. Default output deduplicated repeated dynamic instances by
kind, PC pair, and address/cacheline pair so the top list was suitable for
window selection.

## R612 Evidence

R612 ran a QEMU-only CoreMark capture:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

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
Provenance: source commit 7e2931bc6550ccf344b6d367e672f978381dca58.
<!-- task15-historical-specialized-evidence:end -->

The reducer produced 16,250 expected rows. Locator results:

- all-window scan: 1,423 memory events, 1,243 stores, 180 loads, 94 deduped
  candidates;
- top exact store-before-load candidate:
  rows `1585 -> 1589`, PCs `0x4000d7e6 -> 0x4000d7f2`, address `0x4ffefb68`;
- after-row-4096 scan: 868 memory events, all stores, zero candidates.

This was negative later-window evidence for the first 16K-row prefix. The
historical conclusion was that the next natural CoreMark replay-LIQ search
should not simply widen the same early prefix by a small factor; it needed a
way to skip into a later load-bearing phase, locate a different direct-boot
interval, or return to focused replay fixtures. The deleted search workflow
has no supported current equivalent.

## R613 Evidence

R613 added `--qemu-skip-rows` and `--qemu-raw-only` to the now-deleted
<!-- task15-historical-specialized-evidence:start -->historical evidence only (no current runnable equivalent): `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh` Provenance: source commit 39902e672a8c1fb75a0eeb02b5280c12a89935b3. <!-- task15-historical-specialized-evidence:end -->.

The non-QEMU-only guard rejected skipped captures:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 8 \
  --qemu-skip-rows 1
```
Provenance: source commit 39902e672a8c1fb75a0eeb02b5280c12a89935b3.
<!-- task15-historical-specialized-evidence:end -->

The wrapper exited with:

```text
error: --qemu-skip-rows is allowed only with --qemu-only
```

A raw skipped sample after the R611 boundary:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

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
Provenance: source commit 39902e672a8c1fb75a0eeb02b5280c12a89935b3.
<!-- task15-historical-specialized-evidence:end -->

Result: 512 raw rows were captured after 4096 skipped rows. The locator found
37 memory events, all stores, zero loads, and zero candidates. This confirmed
the R612 post-4096 store-only shape with a smaller interval artifact and gave
later work a cheap loop for interval sampling while the wrapper still existed.
There is no supported current equivalent for that loop.

## R614 Evidence

R614 added the now-deleted `tools/chisel/scan_replay_liq_qemu_intervals.py` and
manually sampled larger skipped CoreMark intervals before closing the packet:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

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
Provenance: source commit 462b359a5bd6620585ea14d7706e70345345bc74.
<!-- task15-historical-specialized-evidence:end -->

The same command shape was repeated for skip offsets 65,536 and 262,144. Each
interval captured 2,048 raw rows. Locator results:

| Build directory | Memory events | Stores | Loads | Candidates | Dominant memory PC |
|---|---:|---:|---:|---:|---|
| `generated/r614-coremark-qemu-raw-skip16384-sample` | 146 | 146 | 0 | 0 | `0x4000d710` |
| `generated/r614-coremark-qemu-raw-skip65536-sample` | 146 | 146 | 0 | 0 | `0x40006310` |
| `generated/r614-coremark-qemu-raw-skip262144-sample` | 146 | 146 | 0 | 0 | `0x40006310` |

This was additional negative interval-selection evidence. It did not supersede
R611 generated-RTL/QEMU no-regression evidence and did not prove replay-LIQ
behavior. The historical follow-up proposed either a broader QEMU-only scanner
sweep with non-adjacent skips or a return to focused replay fixtures. The
scanner and specialized fixture wrapper are now absent, with no supported
current equivalent.

## R615 Evidence

R615 extended the now-deleted scanner interface with inclusive skip ranges and
aggregate summary fields, then ran a bounded non-adjacent sweep. The preserved
command is historical and is not runnable on the current tree:

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

Historical command provenance: source commit
`fd79c40af09fbc60848800e7ebd978ed0a511e36`.

The summary artifact was
`generated/r615-coremark-qemu-interval-scan/report/interval_scan_summary.json`.
It reported schema `linxcore.replay_liq_qemu_interval_scan.v1`,
`scanned_interval_count=4`, `load_interval_count=0`, and
`candidate_interval_count=0`. Each interval completed the bounded raw trace and
timed out only at the wrapper process boundary after capture:

| Skip rows | Raw rows | Memory events | Stores | Loads | Candidates |
|---:|---:|---:|---:|---:|---:|
| 524,288 | 256 | 18 | 18 | 0 | 0 |
| 1,048,576 | 256 | 18 | 18 | 0 | 0 |
| 1,572,864 | 256 | 18 | 18 | 0 | 0 |
| 2,097,152 | 256 | 18 | 18 | 0 | 0 |

This was still candidate-location evidence only. The broader sampled CoreMark
steady-state intervals did not expose natural replay-LIQ load clusters. The
historical recommendation was to return to focused replay fixtures unless a
new QEMU-only interval-selection hypothesis was being tested; those deleted
flows have no supported current equivalent.

## R617 Evidence

R617 annotated locator candidates with raw-row probe hints. It reran the R612
reduced-preview candidate scan with the matching raw trace using this now-
deleted command:

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/r612-coremark-qemu-memory-candidates-16384/traces/qemu.live.expected.preview.jsonl \
  --raw-input generated/r612-coremark-qemu-memory-candidates-16384/traces/qemu.live.raw.jsonl \
  --output generated/r617-coremark-qemu-memory-candidate-hints/replay_liq_qemu_candidates_with_raw_hints.json \
  --top 5 \
  --lookback-rows 1024
```

Historical command provenance: source commit
`e6830114f23594e1883479ec9377e93e0ce0068e`.

The top R612 reduced-preview candidate remained the exact store-before-load pair
at PCs `0x4000d7e6 -> 0x4000d7f2`, address `0x4ffefb68`, and it carried
`probe_hint.raw_dynamic_window.args = ["--qemu-skip-rows", "1715",
"--capture-rows", "6"]`. This mattered because the candidate's reduced rows
`1585 -> 1589` were not raw row offsets.

The matching QEMU-only scanner gate used this now-deleted command:

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

Historical command provenance: source commit
`e6830114f23594e1883479ec9377e93e0ce0068e`.

It reported one interval, one load-bearing interval, and one candidate-bearing
interval. The per-interval candidate report repeated the absolute raw dynamic
window `--qemu-skip-rows 1715 --capture-rows 6`.

R617 also ran a PC-filter preflight on `0x4000d7e6..0x4000d7f3` with expected
store PC `0x4000d7e6` and expected load PC `0x4000d7f2`. That preflight failed
because the first dynamic occurrence of the PC range did not include the load.
The historical conclusion did not authorize converting this candidate into a
generated-RTL CoreMark run by PC filter alone; it required a stateful unskipped
capture strategy or a QEMU-only expected-memory-PC preflight for the exact
generated-RTL command shape. No supported current equivalent implements that
preflight.

## R618 Context Pack

R618 added a separate context-pack validator rather than changing the locator
claim. The following deleted-tool commands were the historical invocation:

```bash
python3 tools/chisel/build_replay_liq_selector_context_pack.py
python3 tools/chisel/build_replay_liq_selector_context_pack.py \
  --validate-only generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json
```

Historical command provenance: source commit
`8f7892f827cbb39a7b2938ec5e2af52b2b005747`.

The manifest combined R611 zero-natural CoreMark no-regression evidence, R616
positive focused selector-origin proof, and the R617 raw-window hint. It was
valid only if those remained distinct: the R617 raw dynamic window was an
address-cluster hint, not generated-RTL/DUT replay-LIQ proof.

## R619 Probe Plan

R619 added a command planner on top of the R618 context pack. The planner is
now absent, and this command remains historical evidence only:

```bash
python3 tools/chisel/plan_replay_liq_selector_probe.py --print-commands
```

Historical command provenance: source commit
`aa56ffe1089eae8bd4a148012fd5fe017b700863`.

The planner emitted QEMU-only preflight commands for the raw-window and
PC-filter forms, each guarded by the expected store/load PCs. It also emitted
`generated_rtl.status = "blocked"`. This was the required handoff shape until a
future packet proved that the exact generated-RTL command shape had a passing
QEMU-only expected-memory-PC preflight. No supported current planner replaces
it.

## R620 Preflight Evidence

R620 ran the safe preflights and recorded the results with this now-deleted
report builder:

```bash
python3 tools/chisel/build_replay_liq_selector_preflight_report.py
python3 tools/chisel/build_replay_liq_selector_preflight_report.py \
  --validate-only generated/r620-replay-liq-selector-preflight-report/report/replay_liq_selector_preflight_report.json
```

Historical command provenance: source commit
`7db7cb38e7b965ccff1e5341a6c4440373a4b91b`.

The raw skipped-window QEMU-only preflight passed with 6 raw rows, 5 reduced
preview rows, store PC `0x4000d7e6`, load PC `0x4000d7f2`, and memory address
`0x4ffefb68`. The PC-filter form captured zero rows in the fresh bounded run,
so the report kept generated RTL blocked. This reinforced the same boundary:
the raw window was a reproducible QEMU candidate hint, not DUT replay-LIQ proof.

## R621 Unskipped Prefix Evidence

R621 proved that the R617 top candidate could be reached without skipped QEMU
rows and recorded the generated-RTL boundary with now-deleted tools:

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

Historical command provenance: source commit
`9061ca3a8e8810938b25c054a94b19ef0349dcef`.

The unskipped QEMU-only preflight captured 1721 raw rows and reduced 1590
preview rows. The top candidate remained the exact store-before-load pair
`0x4000d7e6 -> 0x4000d7f2` at address `0x4ffefb68`, rows `1585 -> 1589`,
score 1186. The matching unskipped generated-RTL/QEMU CoreMark prefix passed
with 1169 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows, and
the generated-RTL preview contained the same target pair.

This was the first safe generated-RTL command shape for the candidate, but the
sideband counters still reported zero natural replay-LIQ/MDB activity:
`liq_alloc_accepted=0`, `replay_queue_out_fire=0`, `lret_w2_slot_accepted=0`,
`w2_promotion_live=0`, selector-from-promotion/probe counters were zero, and
MDB fanout/record counters were zero. R621 therefore remained candidate-present
no-regression coverage, not replay-LIQ replacement proof.

## R622 Activation Gap Report

R622 recorded why R621 did not activate replay-LIQ with this now-deleted report
builder:

```bash
python3 tools/chisel/build_replay_liq_activation_gap_report.py
python3 tools/chisel/build_replay_liq_activation_gap_report.py \
  --validate-only generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json
```

Historical command provenance: source commit
`1ef0a899cbadc187292014804aea2e7df6f29524`.

The report consumed the R621 unskipped-prefix report plus the R621 generated-RTL
sideband stats. It classified the run as memory-path active but pre-ResolveQ:
`load_lookup_valid=180`, `store_stq_resident=512`, and store dequeue counters
were nonzero, while `resident_store_eligible=0`,
`load_lookup_execute_with_eligible_store=0`,
`load_lookup_execute_with_wait_store=0`, `resolve_queue_push_accepted=0`,
`resolve_queue_valid=0`, `mdb_conflict_valid=0`,
`wait_replay_capture_accepted=0`, and `liq_alloc_accepted=0`.

The historical conclusion required a later generated-RTL replay-LIQ proof
attempt to find or construct a run where
`load_lookup_execute_with_eligible_store > 0`; otherwise QEMU store/load
address clusters remained commit-stream hints rather than live resident-store
overlap stimuli. The deleted report flow has no supported current equivalent.

## R623 Focused Eligible-Store Proof

R623 constructed the missing eligible-store stimulus on the focused replay
fixture and recorded the proof boundary:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

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
Provenance: source commit 7f2e4bccd85a69a2d432a02107f0c5af36c27dcd.
<!-- task15-historical-specialized-evidence:end -->

The generated-RTL/QEMU run passed with 18 compared rows, zero mismatches, and
zero QEMU/DUT CBSTOP rows. Sideband counters proved the focused activation
chain: `load_lookup_execute_with_eligible_store=18`,
`load_lookup_execute_with_wait_store=12`, `resident_store_eligible=18`,
`resolve_queue_push_accepted=8`, `resolve_queue_valid=66`,
`mdb_conflict_valid=6`, `mdb_fanout_record_valid=6`,
`wait_replay_capture_accepted=12`, `liq_alloc_accepted=6`,
`replay_queue_out_fire=6`, `lret_w2_slot_accepted=6`, and
`w2_promotion_live=5`.

R623 was focused-fixture replay-LIQ activation proof at its cited source commit.
It did not replace the R621/R622 CoreMark boundary: natural CoreMark replacement
still required the same nonzero activation counters in a CoreMark or natural
workload generated-RTL run. The specialized proof command is not supported on
the current tree.

## R624 Activation Artifact Scan

R624 added a cheap scanner for existing generated sideband artifacts. The
scanner is now absent, and these commands are historical only:

```bash
python3 tools/chisel/scan_replay_liq_activation_artifacts.py
python3 tools/chisel/scan_replay_liq_activation_artifacts.py \
  --validate-only generated/r624-replay-liq-activation-artifact-scan/report/replay_liq_activation_artifact_scan.json
```

Historical command provenance: source commit
`ce89429592645e6160530a72e3a213e1a07e7206`.

The R624 report scanned 34 local sideband artifacts. It found 17 artifacts with
the full activation counter chain positive, but all 17 were classified as
focused/synthetic; `coremark_positive_count=0`. This was triage, not new proof.
The historical procedure reran the scanner after each CoreMark or natural
generated-RTL run and promoted only artifacts with the required nonzero
counters. No supported current scanner performs that triage.

## R625 Natural Activation Probe Plan

R625 expanded the unskipped CoreMark candidate handoff and recorded why its
PC-filter surfaces were excluded from Verilator. The following locator and
planner commands are no longer runnable:

```bash
python3 tools/chisel/find_replay_liq_qemu_candidates.py \
  --input generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.expected.preview.jsonl \
  --raw-input generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.raw.jsonl \
  --output generated/r625-coremark-unskipped-1721-qemu-candidates-top100/report/replay_liq_qemu_candidates.json \
  --top 100 \
  --lookback-rows 1024
python3 tools/chisel/build_replay_liq_natural_activation_probe_plan.py
python3 tools/chisel/build_replay_liq_natural_activation_probe_plan.py \
  --validate-only generated/r625-replay-liq-natural-activation-probe-plan/report/replay_liq_natural_activation_probe_plan.json
```

Historical command provenance: source commit
`fc4bd24bab84273139a4be417f025aef2febbc7b`.

The expanded report had 91 candidates and 12 narrow exact store-before-load
candidates. The known top candidate PC filter remained blocked by R620
(`pc_filter_status=empty`). R625 sampled three more narrow PC filters:
`0x400055f2..0x40005645` was empty, while `0x40005682..0x40005701` and
`0x400055b6..0x40005601` each captured 32 QEMU rows but failed reduced-row
extraction before a legal expected preview was produced. Those PC filters were
blocked launch shapes.

The historical conclusion prohibited rerunning Verilator on the blocked PC
filters. It required checkpoint/state replay for skipped raw windows, a legal
natural workload shard whose unskipped prefix reached eligible-store overlap
from reset, or a QEMU-only PC-filter preflight that passed both reduced-row
extraction and exact memory-PC guards. No supported current tool supplies that
workflow.

## R626 PC-Filter Preflight Search and Failed RTL Attempt

R626 added an automated QEMU-only search over narrow exact store-before-load
CoreMark candidates. Its search and report-builder scripts are now absent:

```bash
python3 tools/chisel/search_replay_liq_pc_filter_preflights.py \
  --build-dir generated/r626-replay-liq-pc-filter-preflight-search-v2 \
  --max-trials 12 \
  --pc-span-limit 256 \
  --capture-rows 32 \
  --max-seconds 20 \
  --stop-on-pass
python3 tools/chisel/build_replay_liq_pc_filter_activation_report.py
python3 tools/chisel/build_replay_liq_pc_filter_activation_report.py \
  --validate-only generated/r626-replay-liq-pc-filter-activation-report/report/replay_liq_pc_filter_activation_report.json
```

Historical command provenance: source commit
`c4f05d865e8185cbaed47fe3c981b674da2be883`.

The first scanned candidate passed the QEMU-only guard:
`0x4000d7e6..0x4000d7f3` captured 6 raw rows, reduced 5 preview rows, and
contained the expected store/load PCs `0x4000d7e6 -> 0x4000d7f2`. This only
authorized spending Verilator time on the same shape; it was not replay-LIQ
proof.

The matching generated-RTL attempt reached the guarded row and failed before
`crosscheck_manifest.json` or sideband stats were emitted. The saved stderr at
`generated/r626-coremark-pc-filter-4000d7e6-4000d7f2-rtl-xcheck-abs/report/generated-rtl-attempt.stderr.txt`
recorded a first-row dst/wb mismatch: PC and instruction matched
(`0x4000d7e6`, `0x02a50041`), QEMU expected `(rd=1, value=1342110568)`, and the
DUT reported `(rd=1, value=18446744073709551608)`. This was a generated-RTL
failure classification packet, not natural CoreMark replay-LIQ activation
proof.

## R627 PC-Filter State-Seed Audit

R627 turned the R626 failure into a pre-Verilator audit in the now-deleted
`tools/chisel/search_replay_liq_pc_filter_preflights.py` schema v2. The scanner
kept two decisions separate:

- QEMU-only trial status: did the PC filter produce a legal reduced preview
  with the expected memory PCs?
- generated-RTL readiness: does the first non-skipped reduced row expose enough
  source state for the Verilator harness to preload architectural RF state?

The same top candidate was run with this historical command:

```bash
python3 tools/chisel/search_replay_liq_pc_filter_preflights.py \
  --build-dir generated/r627-replay-liq-pc-filter-state-seed-search \
  --max-trials 12 \
  --pc-span-limit 256 \
  --capture-rows 32 \
  --max-seconds 20 \
  --stop-on-pass
```

Historical command provenance: source commit
`fbf25256828963960e14744a04214f53dbc44a43`.

That run produced `status=pass` for the QEMU-only trial but
`generated_rtl.status="blocked"`. The first reduced row was
`pc=0x4000d7e6`, `insn=0x02a50041`, `mem_valid=1`, `dst_valid=1`,
`src0_valid=0`, and `src1_valid=0`, so the harness could not reconstruct the
hidden register state needed to start the reduced top at that PC. The
historical promotion rule required `state_seed_audit.status="ready"` before a
PC-filter preflight could advance to generated RTL.

## R628 Full Narrow PC-Filter State-Seed Scan

R628 ran the v2 scanner over all 12 narrow exact store-before-load candidates
from the R625 expanded CoreMark report. The scanner is absent today:

```bash
python3 tools/chisel/search_replay_liq_pc_filter_preflights.py \
  --build-dir generated/r628-replay-liq-pc-filter-state-seed-scan12 \
  --max-trials 12 \
  --pc-span-limit 256 \
  --capture-rows 32 \
  --max-seconds 20
```

Historical command provenance: source commit
`c5537a72e2f0a1ba2d10b21a4c6558f3ea0bcfb6`.

The report recorded `trial_count=12`, `pass_count=1`,
`state_seed_ready_count=0`, and `generated_rtl.status="blocked"`. The top
candidate was QEMU-pass but RF-state insufficient; the other 11 candidates
captured QEMU rows but produced `preview_rows=0`, so reduced-row extraction
failed before memory-PC or RF-state readiness could be proven. This closed the
12-candidate narrow PC-filter path for generated RTL.

The scanner supported `--stop-on-generated-ready`, which stopped only when a
passing QEMU-only trial also had `state_seed_audit.status="ready"`. That was the
historical mode for broader PC-filter searches; a plain QEMU pass did not
authorize a generated-RTL launch. There is no supported current equivalent.

## R629 RF-Seeded PC-Filter Replay

R629 added an explicit RF seed artifact path for PC-filter launches that passed
QEMU memory-PC guards but started after hidden predecessor state. It built the
seed for the top R617/R621 candidate from the unfiltered R621 raw prefix:

```bash
python3 tools/chisel/build_frontend_fetch_rf_seed.py \
  --input generated/r621-coremark-unskipped-1721-qemu-preflight/traces/qemu.live.raw.jsonl \
  --before-row 1715 \
  --output generated/r629-coremark-pc-filter-rf-seed/rf_seed.jsonl
python3 tools/chisel/build_frontend_fetch_rf_seed.py \
  --validate-only generated/r629-coremark-pc-filter-rf-seed/rf_seed.jsonl
```

Historical command provenance: source commit
`dc38d2b8dd6e07338517543d306c2da5aae14feb`.

The seed contained 17 reduced GPR rows and recorded
`source_corrections=1`; the launch-critical row was `x1=0x4ffefb70`, which let
the first PC-filtered store produce QEMU's expected `rd1/mem_addr=0x4ffefb68`.

The direct live-QEMU wrapper was flaky for this narrow PC filter during R629
and sometimes timed out with zero captured rows. The historical workflow used
the scanner-owned passing raw trace as the replay source for generated RTL.
Both the scanner and downstream replay wrapper are absent today, and there is
no supported current equivalent:

<!-- task15-historical-specialized-evidence:start -->
> Historical evidence only; this preserved pre-cutover command has no current runnable equivalent. Its cited artifact and commit provenance remain the evidence; do not use it as a current procedure.

```bash
python3 tools/chisel/search_replay_liq_pc_filter_preflights.py \
  --build-dir generated/r629-replay-liq-pc-filter-seed-preflight-repeat \
  --max-trials 1 \
  --pc-span-limit 256 \
  --capture-rows 32 \
  --max-seconds 60 \
  --stop-on-generated-ready

LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1 \
BUILD_DIR=generated/r629-coremark-pc-filter-seeded-trace-replay \
FETCH_ELF=tests/benchmarks/build/coremark_real.elf \
FETCH_QEMU_TRACE=generated/r629-replay-liq-pc-filter-seed-preflight-repeat/candidate00-pc4000d7e6-4000d7f3/traces/qemu.live.raw.jsonl \
FETCH_QEMU_MAX_ROWS=0 \
FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 \
FETCH_REDUCED_STORE_REPLAY_LIQ=1 \
FETCH_DISABLE_STORE_MEMORY_MUTATION=1 \
FETCH_RF_SEED=generated/r629-coremark-pc-filter-rf-seed/rf_seed.jsonl \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```
Provenance: source commit dc38d2b8dd6e07338517543d306c2da5aae14feb.
<!-- task15-historical-specialized-evidence:end -->

The generated-RTL replay passed the neutral comparator:
`generated/r629-coremark-pc-filter-seeded-trace-replay/report/crosscheck_manifest.json`
reported `compared_rows=3`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows.
This proved that the R626 first-row RF launch-state mismatch was fixed for the
top PC filter at the cited source commit. It was not replay-LIQ activation
proof: sideband stats in the same report still had zero eligible-store,
ResolveQ, MDB, LIQ allocation,
replay-output, and row-mutation counters. The next natural CoreMark packet
was required to use RF seeding only as launch infrastructure and still require
positive replay-LIQ sideband counters.

## R630 Seeded Raw-Window Scanner

R630 added an end-to-end seeded generated-RTL scanner for candidate windows.
The scanner is now absent, and the following invocation is historical only:

```bash
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --build-dir generated/r630-replay-liq-qemu-seeded-window-scan \
  --max-trials 1 \
  --max-capture-rows 32 \
  --wrapper-timeout-seconds 420
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --validate-only generated/r630-replay-liq-qemu-seeded-window-scan/report/seeded_window_scan.json
```

Historical command provenance: source commit
`1a6f9f893de0ecece52fe3fa54eb29dccd47ab66`.

The scanner consumed the R625 candidate report and the R621 unfiltered raw
trace by default. For each raw dynamic-window hint, it built an RF seed before
`qemu_skip_rows`, sliced the raw window, ran
<!-- task15-historical-specialized-evidence:start -->historical evidence only (no current runnable equivalent): `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` Provenance: source commit 1a6f9f893de0ecece52fe3fa54eb29dccd47ab66. <!-- task15-historical-specialized-evidence:end --> with
`FETCH_REDUCED_STORE_REPLAY_LIQ=1`, `FETCH_DISABLE_STORE_MEMORY_MUTATION=1`,
and `FETCH_RF_SEED=<seed>`, then recorded both
`crosscheck_manifest.json` and `frontend_fetch_rf_alu_sideband_stats.json`.

The first R630 trial replayed the top R617/R621 candidate
(`skip=1715`, `rows=6`). It produced a 17-register RF seed with one source
correction and passed the generated-RTL comparator with `compared_rows=3`,
`mismatch_count=0`, and zero CBSTOP rows. The report classified the trial
as `compare_pass_no_activation` because every required replay-LIQ activation
counter remained zero, including eligible-store overlap, ResolveQ, MDB fanout,
LIQ allocation, replay output, and W2 promotion. The historical workflow used
this scanner for broader seeded-window searches and did not treat a seeded
comparator pass as replay-LIQ replacement evidence unless
`activation_positive_count > 0`. No supported current scanner replaces it.

## R631 Resumed Seeded-Window Sweep

R631 added `--skip-windows <n>` to the now-deleted
`tools/chisel/scan_replay_liq_qemu_seeded_windows.py` so a scan could resume
after already-classified eligible windows. This command is historical only:

```bash
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --build-dir generated/r631-replay-liq-qemu-seeded-window-scan-next3 \
  --skip-windows 1 \
  --max-trials 3 \
  --max-capture-rows 32 \
  --wrapper-timeout-seconds 420
```

Historical command provenance: source commit
`f0b1369128ea386fce8bf9d6c79addcb6b5381f0`.

The resumed sweep covered the next three small R625 windows after the R630 top
case. `generated/r631-replay-liq-qemu-seeded-window-scan-next3/report/seeded_window_scan.json`
recorded `activation_positive_count=0` and `compare_pass_count=2`. Window 1
(`skip=1591`, `rows=29`) failed before manifest generation because the expected
rows required conflicting initial RF data for `reg=1`. A focused rerun used
the updated classifier through this historical command:

```bash
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --build-dir generated/r631-replay-liq-qemu-seeded-window-rf-conflict \
  --skip-windows 1 \
  --max-trials 1 \
  --max-capture-rows 32 \
  --wrapper-timeout-seconds 420
```

Historical command provenance: source commit
`f0b1369128ea386fce8bf9d6c79addcb6b5381f0`.

The rerun reported `status="rf_source_conflict"` for that window. Windows 2
and 3
(`skip=1659`, `rows=14`; `skip=1660`, `rows=19`) passed the generated-RTL
comparator with 12 and 15 compared rows, zero mismatches, and zero CBSTOP rows,
but all required replay-LIQ activation counters remained zero. The historical
follow-up was to start at `--skip-windows 4` or deliberately include larger
capture windows; the first four eligible R625 windows were not replay-LIQ
activation proof. That follow-up is unsupported on the current tree.

## R632 Larger Seeded-Window Sweep

R632 resumed from the next two larger R625 windows after the first four
classified windows. The preserved scanner commands are not currently runnable:

```bash
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --build-dir generated/r632-replay-liq-qemu-seeded-window-scan-next2-large \
  --skip-windows 4 \
  --max-trials 2 \
  --max-capture-rows 160 \
  --wrapper-timeout-seconds 900
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --validate-only generated/r632-replay-liq-qemu-seeded-window-scan-next2-large/report/seeded_window_scan.json
```

Historical command provenance: source commit
`4f5461cea238ee5d47a482c6d39431add7463f50`.

The report recorded zero compare-passing or activation-positive trials. Window
4 (`skip=1525`, `rows=109`) was classified as `rf_source_conflict`: the reduced
expected rows required conflicting initial RF data for `reg=1`, so one scalar
RF seed could not describe the skipped launch state. Window 5 (`skip=1286`,
`rows=124`) initially failed before manifest generation because `C.SDI` read an
empty U0 local source. A focused classifier rerun used this historical command:

```bash
python3 tools/chisel/scan_replay_liq_qemu_seeded_windows.py \
  --build-dir generated/r632-replay-liq-qemu-seeded-window-local-source-missing \
  --skip-windows 5 \
  --max-trials 1 \
  --max-capture-rows 160 \
  --wrapper-timeout-seconds 900
```

Historical command provenance: source commit
`4f5461cea238ee5d47a482c6d39431add7463f50`.

The rerun recorded `status="local_source_missing"` in
`generated/r632-replay-liq-qemu-seeded-window-local-source-missing/report/seeded_window_scan.json`.
This proved that the scalar RF seed path at the time was insufficient for
skipped windows that consumed hidden T/U local source state. The first six
eligible R625 windows provided no replay-LIQ activation proof. The historical
next step was to resume at `--skip-windows 6`, while larger windows with local-
source dependencies awaited an explicit local T/U checkpoint or seed boundary.
No supported current seeded scanner or local-state replay equivalent exists.
