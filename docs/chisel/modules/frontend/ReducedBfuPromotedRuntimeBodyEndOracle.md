# ReducedBfuPromotedRuntimeBodyEndOracle

## Purpose

`ReducedBfuPromotedRuntimeBodyEndOracle` is the R157 proof owner for pending
BFU runtime body-end feedback after it drives source selection without waiting
for replay timing. It keeps the replay comparison surface alive after
`ReducedBfuResolvedBodyEndSource` consumes the pending event from
`ReducedBfuResolvedBodyEndPending`.

The module is diagnostic only. It must not gate body-end source selection or
body-cut control. Its job is to prove that each promoted RTL-owned body-end
event still matches the later replay oracle, or to report that a single pending
oracle slot was overwritten before replay could check it.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears any stored promoted runtime event. |
| input | `promoteValid`, `promoteHeaderPc`, `promoteHSizeBytes`, `promoteBodyEndPc` | mixed | Runtime body-end event selected by `ReducedBfuResolvedBodyEndSource`. |
| input | `replayValid`, `replayHeaderPc`, `replayHSizeBytes`, `replayBSizeBytes` | mixed | Temporary QEMU/replay oracle payload. |
| output | `pending` | `Bool` | A promoted event is waiting for a replay comparison. |
| output | `captureFire` | `Bool` | A promoted event was retained because replay was not comparable in the same cycle. |
| output | `overwritePending` | `Bool` | A second promotion arrived before the previous promoted event was compared. |
| output | `replayBodyEndPc` | `UInt(pcWidth.W)` | Replay body-end PC computed as `replayHeaderPc + 2 + replayBSizeBytes`. |
| output | `replayComparable`, `replayMatch`, `replay*Mismatch` | `Bool` | Promoted-event versus replay comparison diagnostics. |

## Logic

The oracle compares immediately when no promoted event is pending and replay is
visible in the same cycle:

```text
immediateComparable = !pending && promoteValid && replayValid
```

Otherwise it stores one promoted event and waits for a later replay row:

```text
captureFire = promoteValid && !pending && !immediateComparable
pendingComparable = pending && replayValid
```

When a stored event is comparable, the module compares header PC, `hsize`, and
body-end PC against the replay body-end conversion:

```text
replayBodyEndPc = replayHeaderPc + 2 + replayBSizeBytes
```

If a new promotion arrives while an older promoted event is still pending and
no replay comparison is happening, `overwritePending` is asserted. The current
generated-RTL harness treats that as fatal because it would require a deeper
oracle queue to preserve proof for every promoted event.

## Model Evidence

- `model/bctrl/bfu/bfu.cpp`: `FlushForF4` records local-pipe `taken_pc` and
  calls `SetLocalPipeFetchSize` when the model discovers a local fetch end PC.
- `model/bctrl/bfu/bfu.cpp`: `ArbitrateForLocalFB` consumes `local.sizeGet`,
  matches the local header PC, and uses `local.end_pc` as the body-end source.
- `model/bctrl/bfu/bfu.cpp`: `SetLocalPipeFetchSize` keeps local-pipe
  `sizeGet` and `end_pc` resident.
- `model/bctrl/bfu/bfu_utils.h`: `SetBsize` computes `bsize` from
  `headerPc + 2` to the retained body-end PC.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuPromotedRuntimeBodyEndOracle
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
reported:

```text
bfu_promoted_runtime_body_end_oracle_pending=2082
bfu_promoted_runtime_body_end_oracle_replay_comparable=159
bfu_promoted_runtime_body_end_oracle_replay_matches=159
bfu_promoted_runtime_body_end_oracle_replay_mismatches=0
bfu_promoted_runtime_body_end_oracle_overwrites=0
```

The generated-RTL harness fails on any promoted oracle replay mismatch or
overwrite.
