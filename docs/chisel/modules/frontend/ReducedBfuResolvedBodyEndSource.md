# ReducedBfuResolvedBodyEndSource

## Purpose

`ReducedBfuResolvedBodyEndSource` is the source arbiter in front of
`ReducedBfuResolvedBodyEndOwner`. It gives the reduced top a single resolved
body-end event interface while the implementation transitions away from the
temporary replay sideband.

R154 keeps replay geometry as the cold same-cycle fallback, but adds a
registered runtime source from local body-cut feedback. When a learned local
body window cuts a packet, the top records `headerPc`, `hsize`, and the cut PC;
on the next cycle this module can provide that RTL-owned body-end event to the
existing owner. Replay remains an oracle and fallback until the full branch/BFU
resolver exists.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `runtimeValid`, `runtimeHeaderPc`, `runtimeHSizeBytes`, `runtimeBodyEndPc` | mixed | Registered RTL-owned body-end event, currently produced by local body-cut feedback in the reduced top. |
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

Runtime priority is a forward-compatibility choice. Once the real branch/BFU
resolver owns a body-end event, replay should remain only a comparison source.
R154 does not remove the replay cold fallback because the first CoreMark FALL
re-entry body end is still supplied by QEMU metadata before a local learned
window can cut on its own.

## Model Evidence

- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(header)` uses `headerPc + 2`,
  and `SetBsize` records body size from a resolved body end.
- `model/bctrl/bfu/bfu.cpp`: F4/local prediction cuts a fetch bundle at a
  known `end_pc` once the header has size evidence.
- `model/bctrl/bfu/bfu_sp.cpp`: static prediction can close a header on later
  block events, but dynamic FALL re-entry eligibility must come from resolved
  prediction/runtime evidence rather than static boundary geometry alone.

## Verification

R154 adds focused reference/elaboration tests:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedBfuResolvedBodyEndSource
```

The integrated top exposes source-selection and runtime-feedback diagnostics so
the generated-RTL replay can report how often body-end ownership came from the
runtime feedback path versus the replay fallback.
