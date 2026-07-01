# ReducedBfuResolvedBodyEndSource

## Purpose

`ReducedBfuResolvedBodyEndSource` is the source arbiter in front of
`ReducedBfuResolvedBodyEndOwner`. It gives the reduced top a single resolved
body-end event interface while the implementation transitions away from the
temporary replay sideband.

R154 kept replay geometry as the cold same-cycle fallback, but added a runtime
source from local body-cut feedback. R155 moves the feedback lifetime into
`ReducedBfuResolvedBodyEndPending`: when a learned local body window cuts a
packet, the top records `headerPc`, `hsize`, and the cut PC, then emits that
RTL-owned body-end event only when the next replay-qualified candidate matches.
Replay remains an oracle and timing fallback until the full branch/BFU resolver
exists.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `runtimeValid`, `runtimeHeaderPc`, `runtimeHSizeBytes`, `runtimeBodyEndPc` | mixed | RTL-owned body-end event, currently produced by `ReducedBfuResolvedBodyEndPending` from local body-cut feedback. |
| input | `replayValid`, `replayHeaderPc`, `replayHSizeBytes`, `replayBSizeBytes` | mixed | Temporary replay/QEMU geometry sideband. |
| output | `resolvedValid`, `resolvedHeaderPc`, `resolvedHSizeBytes`, `resolvedBodyEndPc` | mixed | Selected normalized event forwarded to `ReducedBfuResolvedBodyEndOwner`. |
| output | `replayBodyEndPc` | `UInt(pcWidth.W)` | Replay `headerPc + 2 + bsize`, exposed for diagnostics. |
| output | `selectedRuntime`, `selectedReplay` | `Bool` | Source-selection diagnostics. Runtime has priority when valid. |
| output | `runtimeReplayComparable`, `runtimeReplayMatch`, mismatch flags | `Bool` | Oracle comparison when both runtime and replay events are present in the same cycle. |

## Logic

The replay input still uses the model body-size encoding:

```text
replayBodyEndPc = replayHeaderPc + 2 + replayBSizeBytes
```

The runtime input already carries a body-end PC. Selection is intentionally
simple:

```text
if runtimeValid:
  use runtime event
else if replayValid:
  use replay event converted to body-end PC
```

Runtime priority is a forward-compatibility choice. R155 qualifies the runtime
event before it reaches this arbiter, so a stale pending event cannot override
the replay candidate. Once the real branch/BFU resolver owns a body-end event,
replay should remain only a comparison source. R155 still does not remove the
replay cold fallback because the first CoreMark FALL re-entry body end is
supplied by QEMU metadata before a local learned window can cut on its own.

## Model Evidence

- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(header)` uses `headerPc + 2`,
  and `SetBsize` records body size from a resolved body end.
- `model/bctrl/bfu/bfu.cpp`: F4/local prediction cuts a fetch bundle at a
  known `end_pc` once the header has size evidence.
- `model/bctrl/bfu/bfu_sp.cpp`: static prediction can close a header on later
  block events, but dynamic FALL re-entry eligibility must come from resolved
  prediction/runtime evidence rather than static boundary geometry alone.

## Verification

Focused reference/elaboration tests:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuResolvedBodyEndSource
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuResolvedBodyEndPending
```

The integrated top exposes source-selection and runtime-feedback diagnostics so
the generated-RTL replay can report how often body-end ownership came from the
runtime feedback path versus the replay fallback.
