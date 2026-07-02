# ReducedStoreMemoryOverlay

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreMemoryOverlay.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreMemoryOverlaySpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Harness switch:
  - `rtl/LinxCore/tools/chisel/frontend_fetch_rf_alu_trace_top_tb.cpp`
  - `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
  - `rtl/LinxCore/tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::commit`
    - `STQ::lookupForLoad`
    - `STQ::free`
  - `tools/LinxCoreModel/model/lsu/store_unit/store_unit.cpp`
    - `StoreUnit::checkCommit`
    - `StoreUnit::GetCommitID`

## Purpose

`ReducedStoreMemoryOverlay` is the reduced-top memory visibility bridge for
`LinxCoreFrontendFetchRfAluTraceTop(useReducedStoreDispatchStq=true)`. It
records committed store fragments from ROB commit rows and from the reduced
`SCBRowBank`, then overlays their bytes onto later 64-bit scalar load lookup
data from the sparse ELF base image. The ROB commit-row feed mirrors the
model's `storeCommitQ` load visibility before SCB acceptance; the SCB accepted
feed keeps the bridge aligned with the downstream memory-side drain. Together
they let the reduced-store CoreMark gate prove store-visible loads without
relying on the C++ harness to mutate its sparse memory image after matched
store rows.

The module is intentionally a reduced trace-top bridge, not a replacement for
the full LSU memory system. It does not own cache state, TSO/fence completion,
load replay, MDB conflict publication, or the complete model `lookupForLoad`
wait/conflict policy. It makes committed store bytes visible to the reduced
scalar load lookup path; R265 then applies ready resident STQ forwarding after
this committed overlay.

## Interface

Inputs:

- `flush`: clears all overlay lines. The top asserts this on test/run start,
  restart, or when the optional reduced-store STQ path is disabled. It is not
  tied to ordinary backend redirects because accepted SCB fragments are already
  committed memory-side state.
- `storeReqs`: `STQCommitDrainRequest` lanes produced by the reduced top. Each
  lane carries valid, address, size, data, and `last` sidecars. The parent must
  present same-cycle accepted lanes in old-to-young program order because later
  lanes overwrite overlapping bytes in the overlay line image.
- `storeAcceptedMask`: one bit per request lane. Commit-row bypass lanes are
  accepted when the monitored commit row is a valid store; SCB lanes are
  accepted from `SCBRowBank.acceptedMask`. Only valid requests whose lane bit
  is set update the overlay.
- `loadValid`, `loadAddr`: reduced scalar load lookup request from
  `ReducedScalarAluExecute`.
- `baseLoadData`: immutable 64-bit little-endian data read by the harness from
  the sparse ELF image.

Outputs:

- `loadData`: `baseLoadData` with any matching overlay bytes substituted.
- `loadForwardMask`: one bit per returned byte indicating overlay hit.
- `lines`: diagnostic image of each overlay line.
- `validMask`, `lineCount`: diagnostic occupancy.
- `storeDroppedMask`: one bit per accepted request that could not find a
  matching or free overlay line.

## Logic Design

The overlay stores fixed 64-byte line entries. An accepted store request first
matches an existing valid line by aligned 64-byte address. If no line matches,
the request allocates the lowest free line. If neither a matching line nor a
free line exists, `storeDroppedMask` pulses for that request and the overlay
state is unchanged.

For an accepted update, the module builds a byte mask from the request address
low bits and request size, then merges the little-endian request data into the
target line. Multiple accepted lanes are applied in lane order through a
combinational ingress chain before the line registers update. The parent must
therefore route older store fragments first and younger fragments later. In the
current reduced top, `STQCommitDrain`/`SCBRowBank` accepted lanes are older
resident `storeCommitQ` work selected from the registered commit queue, so they
are applied before current ROB commit-row bypass lanes.

For a load lookup, each of the eight returned bytes independently checks the
registered overlay lines by the byte's 64-byte line address and byte offset.
This per-byte selection lets a 64-bit load span two overlay lines. A byte with
no overlay hit falls back to the harness-supplied sparse-image `baseLoadData`.

## Model Alignment

The C++ `STQ::retire` path marks ready stores as committed and pushes their
indices into `storeCommitQ`. `STQ::commit` then drains that queue toward SCB in
program order and frees each STQ row only after the downstream request is
accepted. A wide or cross-cacheline store may produce more than one downstream
memory request. The reduced top still wires accepted SCB `last` fragments as
the STQ free source through `SCBRowBank`; this overlay also consumes those
fragments as an idempotent downstream memory-side data source.

The model `STQ::lookupForLoad` can see committed store state before SCB accepts
the drain request: it checks resident older/equal stores and the `storeCommitQ`
when assembling load bytes. R259 therefore feeds ROB committed store rows into
the overlay without waiting for SCB acceptance. R262 preserves that visibility
while ordering same-cycle overlay updates old-to-young: current SCB accepted
fragments from the registered commit queue are older than current ROB commit
rows and must not overwrite a younger commit-row byte. `ReducedStoreMemoryOverlay`
implements only that committed-byte visibility subset. It does not model load
wait/replay, younger-store conflicts, MDB recovery publication, or cache
coherence.

## Deferred Owners

- Replace the overlay plus ready-only resident forwarding with the full
  load/store forwarding and replay owner.
- Publish MDB conflicts and recovery cleanup for precise exceptions.
- Connect real cache/SCB memory state instead of a trace-top sparse-image base.
- Add capacity or eviction policy if a longer reduced-store proof exceeds the
  fixed overlay line budget.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreMemoryOverlay
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

R258 generated-RTL evidence:

```bash
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh \
  --build-dir generated/r258-store-memory-overlay-1024-qemu-elf-xcheck \
  --elf tests/benchmarks/build/coremark_real.elf \
  --expected-rows 0 --capture-rows 1024 \
  --allow-block-markers --reduced-store-dispatch-stq \
  --disable-store-memory-mutation \
  --max-seconds 8 \
  -- -nographic -monitor none -machine virt -m 1280M \
     -kernel tests/benchmarks/build/coremark_real.elf
```

The live wrapper captured and built the reduced top. The generated binary was
then rerun directly with `--disable-store-memory-mutation`, and the neutral
QEMU/DUT comparator passed at
`generated/r258-store-memory-overlay-1024-qemu-elf-xcheck/report-no-harness-store/crosscheck_manifest.json`
with 665 compared rows and zero mismatches.

R259 2048-row no-harness-mutation evidence:

```bash
FETCH_REDUCED_STORE_DISPATCH_STQ=1 \
FETCH_DISABLE_STORE_MEMORY_MUTATION=1 \
FETCH_EXPECTED_ROWS=generated/r259-reduced-store-overlay-commit-bypass-2048-qemu-elf-xcheck/qemu.expected.jsonl \
FETCH_ELF=tests/benchmarks/build/coremark_real.elf \
BUILD_DIR=generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck \
  bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

The first 2048-row no-mutation probe with SCB-only overlay data failed at a
load from a committed store that had not yet reached SCB acceptance. The R259
commit-row bypass feed passes at
`generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/report/crosscheck_manifest.json`
with 1467 compared rows and zero mismatches.

R260 scale evidence reuses the R259 generated binary against the R256 4096-row
loop-reentry expected stream with `--disable-store-memory-mutation`, then runs
the common comparator:

```bash
generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/obj_dir/linxcore_frontend_fetch_rf_alu_trace_top_tb \
  --dut-trace generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/traces/dut.no-harness-store.jsonl \
  --qemu-trace generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/traces/qemu.no-harness-store.jsonl \
  --expected-rows generated/r256-reduced-store-scale-4096-reuse-r254-bin-qemu-elf-xcheck/qemu.expected.loop-reentry.jsonl \
  --memory-hex generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/elf.fetch.mem \
  --disable-store-memory-mutation
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/traces/qemu.no-harness-store.jsonl \
  --dut-trace generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/traces/dut.no-harness-store.jsonl \
  --report-dir generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/report \
  --max-commits 3370 --mode failfast
```

The comparator passes at
`generated/r260-reduced-store-overlay-commit-row-4096-reuse-r259-bin-xcheck/report/crosscheck_manifest.json`
with 3369 compared rows and zero mismatches.

R261 repeats the same no-harness-mutation direct replay pattern at the existing
8192-row expected-stream depth:

```bash
generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/obj_dir/linxcore_frontend_fetch_rf_alu_trace_top_tb \
  --dut-trace generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/traces/dut.no-harness-store.jsonl \
  --qemu-trace generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/traces/qemu.no-harness-store.jsonl \
  --expected-rows generated/r257-reduced-store-scale-8192-reuse-r254-bin-qemu-elf-xcheck/qemu.expected.loop-reentry.jsonl \
  --memory-hex generated/r259-reduced-store-overlay-commit-row-2048-trace-xcheck/elf.fetch.mem \
  --disable-store-memory-mutation
bash tools/chisel/run_chisel_qemu_crosscheck.sh \
  --qemu-trace generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/traces/qemu.no-harness-store.jsonl \
  --dut-trace generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/traces/dut.no-harness-store.jsonl \
  --report-dir generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/report \
  --max-commits 7173 --mode failfast
```

The comparator passes at
`generated/r261-reduced-store-overlay-commit-row-8192-reuse-r259-bin-xcheck/report/crosscheck_manifest.json`
with 7172 compared rows and zero mismatches.

R263 scale evidence reruns the reduced-store live QEMU wrapper with
`--disable-store-memory-mutation`, `--reduced-store-dispatch-stq`, and a fresh
16384-row CoreMark capture after the R262 old-to-young lane ordering change.
The run reduces 16250 expected rows, normalizes 14780 QEMU/DUT rows, compares
14779 rows, and passes with zero mismatches in
`generated/r263-reduced-store-overlay-old-to-young-16384-qemu-elf-xcheck/report/crosscheck_manifest.json`.
