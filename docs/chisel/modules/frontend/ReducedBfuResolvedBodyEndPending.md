# ReducedBfuResolvedBodyEndPending

## Purpose

`ReducedBfuResolvedBodyEndPending` is the R155 lifetime owner for reduced BFU
runtime body-end feedback. R154 captured local body-cut feedback for only one
cycle, so a valid local cut could be counted without ever becoming the selected
resolved body-end source. This module keeps that runtime event pending until a
matching candidate geometry row appears and the source arbiter consumes it.

This packet still uses replay geometry as the candidate/oracle. It does not
remove replay timing. The improvement is that the selected geometry can now
come from retained RTL runtime state once the next candidate proves the
`headerPc`/`hsize`/`bodyEndPc` fields match.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `flushValid` | `Bool` | Clears pending feedback on frontend flush, start, restart, or marker redirect. |
| input | `captureValid`, `captureHeaderPc`, `captureHSizeBytes`, `captureBodyEndPc` | mixed | Captures the local body-window cut feedback event. |
| input | `candidateValid`, `candidateHeaderPc`, `candidateHSizeBytes`, `candidateBSizeBytes` | mixed | Replay-qualified candidate used to decide whether the pending runtime event is eligible this cycle. |
| input | `consumeValid` | `Bool` | Source-arbiter consume pulse for a selected runtime event. |
| output | `runtimeValid`, `runtimeHeaderPc`, `runtimeHSizeBytes`, `runtimeBodyEndPc` | mixed | Runtime event forwarded to `ReducedBfuResolvedBodyEndSource` only when pending feedback matches the candidate. |
| output | `pending`, `captureFire`, `consumeFire` | `Bool` | Pending-store lifecycle diagnostics. |
| output | `dropMismatch`, `candidateComparable`, `candidateMatch`, `candidateMismatch` | `Bool` | Replay-oracle diagnostics for stale or mismatched pending feedback. |

## Logic

The candidate replay body end uses the same model-style conversion as the
source arbiter:

```text
candidateBodyEndPc = candidateHeaderPc + 2 + candidateBSizeBytes
```

When pending feedback and a candidate are both present, the event is eligible
only if all fields match:

```text
candidateMatch =
  pending &&
  candidateValid &&
  pending.headerPc == candidate.headerPc &&
  pending.hsizeBytes == candidate.hsizeBytes &&
  pending.bodyEndPc == candidateBodyEndPc
```

On a match, `runtimeValid` is asserted and the downstream arbiter can select
the runtime event. On a mismatch, the stale pending event is dropped and replay
remains the only usable source for that candidate. New captures have priority
over consume/drop so back-to-back local cuts do not lose the newest event.

## Model Evidence

- `model/bctrl/bfu/bfu_common.h`: `LocalPipe` stores `sizeGet` and `end_pc`.
- `model/bctrl/bfu/bfu.cpp`: `SetLocalPipeFetchSize` records `end_pc`, and
  `ArbitrateForLocalFB` later uses that retained value when the local fetch
  reaches the matching header/body end.
- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(headerPc)` is `headerPc + 2`,
  and `SetBsize` saturates `endPc - startPc` at zero.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuResolvedBodyEndPending
```

Affected top gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
```
