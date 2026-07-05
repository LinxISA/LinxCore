# LoadReplayWakeMatchDiagnostics

## Purpose

`LoadReplayWakeMatchDiagnostics` is a combinational diagnostic sidecar for
replay-LIQ store-unit wake bring-up. It mirrors the wait-store identity terms
used by `LoadReplayWakeup` and exposes which predicate is present in the live
top before relying on the child LIQ clear mask.

It does not mutate LIQ rows, arbitrate wakeups, merge data, or change launch
eligibility.

## Interface

| Direction | Signal | Description |
| --- | --- | --- |
| input | `wakeValid` | Qualifies the sampled replay wake. |
| input | `wake` | `LoadReplayWakeupRequest` under diagnosis. |
| input | `rows` | Current `LoadInflightQueue` row image. |
| output | `waitStoreCandidateMask` | Rows that are valid, waiting on a store, and have valid wait-store identity while the wake is valid. |
| output | `bidMatchMask` | Candidate rows whose wait-store BID matches the wake store BID. |
| output | `lsIdMatchMask` | BID-matched rows whose wait-store LSID is either invalid or matches the wake store LSID. |
| output | `pcMatchMask` | Candidate rows whose wait-store PC matches the wake PC. |
| output | `fullMatchMask` | Rows satisfying BID, optional LSID, and PC match. |
| output | `storeUnit` | Wake source is `LoadReplayWakeSource.StoreUnit`. |
| output | `storeUnitFullMatchMask` | Full-match rows under a store-unit wake source. |

## Logic

For each LIQ row, the helper evaluates the same wait-store identity chain that
`LoadReplayWakeup` uses before clearing a row:

1. wake valid and row has valid wait-store state;
2. wait-store BID matches the wake store BID;
3. wait-store LSID is invalid or matches the wake store LSID;
4. wait-store PC matches the wake PC;
5. wake source is `StoreUnit`.

The helper intentionally keeps each term visible as a mask so a failed live
gate can distinguish timing, identity, source, and downstream clear-mask
failures.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`
- R509 live gate:
  `generated/r509-replay-liq-wake-source-diagnostics/report/frontend_fetch_rf_alu_sideband_stats.json`
