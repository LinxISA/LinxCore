# ReducedBfuPendingRuntimeBodyEndCandidate

## Purpose

`ReducedBfuPendingRuntimeBodyEndCandidate` is the R157 active-header eligibility
owner for pending BFU runtime body-end feedback. It answers the control
question that R156 first measured diagnostically: if replay timing is removed,
is the retained runtime payload acceptable against the current active header?

In R157 this candidate drives `ReducedBfuResolvedBodyEndSource`. Replay remains
only as an oracle: same-cycle candidate/replay comparisons are still exposed
here, and delayed comparisons after promotion are owned by
`ReducedBfuPromotedRuntimeBodyEndOracle`.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `pendingValid`, `pendingHeaderPc`, `pendingHSizeBytes`, `pendingBodyEndPc` | mixed | Retained local body-window cut feedback from `ReducedBfuResolvedBodyEndPending`. |
| input | `headerActive`, `activeHeaderPc` | mixed | Current static-predictor active-header state. |
| input | `replayValid`, `replayHeaderPc`, `replayHSizeBytes`, `replayBSizeBytes` | mixed | Temporary replay/reducer oracle used only for diagnostics. |
| output | `candidateValid`, `candidateHeaderPc`, `candidateHSizeBytes`, `candidateBodyEndPc` | mixed | Replay-free source candidate when pending feedback matches the active header. |
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

R157 promotes this candidate into the source arbiter. Replay comparisons in
this module are now same-cycle diagnostics only; once source selection consumes
the pending event before replay arrives, `ReducedBfuPromotedRuntimeBodyEndOracle`
retains the promoted payload until replay can check it.

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

R157 generated-RTL replay gate:

```bash
BUILD_DIR=generated/r157-promoted-runtime-source-4000-rtl-replay \
FETCH_QEMU_TRACE=generated/r153-next-frontier-4000-qemu-probe/traces/qemu.live.raw.jsonl \
FETCH_QEMU_MAX_ROWS=0 \
FETCH_QEMU_ALLOW_BLOCK_MARKERS=1 \
FETCH_QEMU_ALLOW_BLOCK_LOOP_REENTRY=1 \
FETCH_ELF=tests/benchmarks/build/coremark_real.elf \
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

The R157 replay compared 3280 normalized QEMU/DUT rows with zero mismatches and
reported zero pending-candidate replay mismatches. Delayed promoted-runtime
replay proof is reported by the promoted oracle counters.
