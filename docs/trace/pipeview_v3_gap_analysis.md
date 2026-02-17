# Pipeview V3 Gap Analysis

## Baseline gaps

1. Frontend used synthetic trace IDs not tied to backend dynamic uop IDs.
2. `IQ` waiting residency was not represented as an explicit stage.
3. Konata TB kept a synthetic retire fallback path, which could hide DFX issues.
4. Stage order was inconsistent between trace writer/checker (`IQ` missing).

## V3 closures implemented

1. Fetch-born packet UID added at `F0` and threaded to `F4`.
2. Decode derives per-slot uop UID from fetch packet UID.
3. `IQ` stage probes exported from backend and consumed by TB.
4. TB uses DFX stage probes as canonical path and emits `L type=1` detail metadata.
5. Stage/check scripts and docs now include canonical `IQ` and `CMT`.

## Remaining non-goals

1. Legacy `trace_evt_*` bus still exists for compatibility, but is non-canonical for pipeview.
2. Stage-authoritative `m.debug` calls in every leaf module are incremental and can be expanded further without changing the TB contract.
