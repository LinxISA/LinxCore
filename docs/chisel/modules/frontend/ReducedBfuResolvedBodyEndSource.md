# ReducedBfuResolvedBodyEndSource

## Purpose

`ReducedBfuResolvedBodyEndSource` is the source arbiter in front of
`ReducedBfuResolvedBodyEndOwner`. It gives the reduced top a single resolved
body-end event interface while the implementation transitions away from the
temporary replay sideband.

R154 kept replay geometry as the cold same-cycle fallback, but added a runtime
source from local body-cut feedback. R155 moved the feedback lifetime into
`ReducedBfuResolvedBodyEndPending`. R157 now drives this arbiter from
`ReducedBfuPendingRuntimeBodyEndCandidate`, so a retained runtime event can be
selected as soon as it matches the active header. Replay remains the cold
fallback when no runtime candidate exists and remains the oracle through
same-cycle comparison here plus delayed comparison in
`ReducedBfuPromotedRuntimeBodyEndOracle`.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `runtimeValid`, `runtimeHeaderPc`, `runtimeHSizeBytes`, `runtimeBodyEndPc` | mixed | RTL-owned body-end event, currently produced by `ReducedBfuPendingRuntimeBodyEndCandidate` from pending local body-cut feedback and active-header state. |
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

Runtime priority is now the promoted R157 behavior. The runtime event is
qualified by active-header state before it reaches this arbiter, so it no
longer waits for replay timing. Once selected, it consumes the pending event;
`ReducedBfuPromotedRuntimeBodyEndOracle` retains a diagnostic copy until a
later replay row can prove the selected header, `hsize`, and body-end PC. Replay
is still the cold fallback because the first CoreMark FALL re-entry body end is
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
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuPendingRuntimeBodyEndCandidate
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuPromotedRuntimeBodyEndOracle
```

The integrated top exposes source-selection and runtime-feedback diagnostics so
the generated-RTL replay can report how often body-end ownership came from the
runtime feedback path versus the replay fallback.
