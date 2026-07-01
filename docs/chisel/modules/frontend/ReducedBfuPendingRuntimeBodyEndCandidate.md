# ReducedBfuPendingRuntimeBodyEndCandidate

## Purpose

`ReducedBfuPendingRuntimeBodyEndCandidate` is the R156 diagnostic owner for
pending BFU runtime body-end feedback. It answers a narrower question than the
source arbiter: if replay timing were removed, would the retained runtime
payload be acceptable against the current active header?

The module does not drive control in R156. `ReducedBfuResolvedBodyEndPending`
still requires the replay-qualified candidate before the runtime event reaches
`ReducedBfuResolvedBodyEndSource`. This checker only exposes the replay-free
candidate condition and compares it with replay when both are visible.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `pendingValid`, `pendingHeaderPc`, `pendingHSizeBytes`, `pendingBodyEndPc` | mixed | Retained local body-window cut feedback from `ReducedBfuResolvedBodyEndPending`. |
| input | `headerActive`, `activeHeaderPc` | mixed | Current static-predictor active-header state. |
| input | `replayValid`, `replayHeaderPc`, `replayHSizeBytes`, `replayBSizeBytes` | mixed | Temporary replay/reducer oracle used only for diagnostics. |
| output | `candidateValid`, `candidateHeaderPc`, `candidateHSizeBytes`, `candidateBodyEndPc` | mixed | Replay-free diagnostic candidate when pending feedback matches the active header. |
| output | `pendingWithoutActiveHeader`, `activeHeaderMismatch` | `Bool` | Pending feedback could not be promoted because the active-header state is absent or different. |
| output | `replayComparable`, `replayMatch`, `replay*Mismatch` | `Bool` | Candidate-vs-replay comparison diagnostics. |

## Logic

The candidate is valid only when the pending feedback is still resident and the
static producer reports the same active header:

```text
candidateValid =
  pendingValid &&
  headerActive &&
  pending.headerPc == activeHeaderPc
```

Replay comparison uses the same model-style body-end conversion used by the
source arbiter:

```text
replayBodyEndPc = replayHeaderPc + 2 + replayBSizeBytes
```

R156 keeps this as proof infrastructure. A later packet may promote this
candidate to the source arbiter only after the generated-RTL replay shows
nonzero candidate/replay comparisons with zero mismatches.

## Model Evidence

- `model/bctrl/bfu/bfu.cpp`: `ArbitrateForLocalFB` uses retained local
  `taken_pc/end_pc` when the local fetch sees the matching header.
- `model/bctrl/bfu/bfu.cpp`: `SetLocalPipeFetchSize` records the local
  `end_pc` and marks the local pipe size as available.
- `model/bctrl/bfu/bfu_common.h`: `LocalPipe` keeps `sizeGet`, `taken_pc`,
  and `end_pc` until local-pipe flush/free.
- `model/bctrl/bfu/bfu_utils.h`: `SetBsize` computes body size from
  `headerPc + 2` to `endPc`, saturating underflow to zero.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuPendingRuntimeBodyEndCandidate
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```

R156 generated-RTL replay gate:

```bash
BUILD_DIR=generated/r156-pending-runtime-candidate-4000-rtl-replay \
FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl \
FETCH_QEMU_MAX_ROWS=0 \
FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 \
FETCH_ELF=tests/benchmarks/build/coremark_real.elf \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

This replay compared 3280 normalized QEMU/DUT rows with zero mismatches and
reported `bfu_pending_runtime_candidate_replay_matches=159` with
`bfu_pending_runtime_candidate_replay_mismatches=0`.
