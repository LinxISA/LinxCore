# ScalarRedirectRecoverySource

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/ScalarRedirectRecoverySource.scala`
- Test: `chisel/src/test/scala/linxcore/recovery/ScalarRedirectRecoverySourceSpec.scala`
- Integrated owner: `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Contract ID: `LC-CHISEL-SCALAR-REDIRECT-RECOVERY-001`

## Purpose

`ScalarRedirectRecoverySource` converts an accepted scalar execute or marker
redirect into one retained Linx recovery report. It separates producer capture
from central recovery acceptance so a redirect cannot be lost under cleanup
backpressure.

## Contract

1. The source retains at most one event. Queue depth, ROB entries, full BID,
   scope, and sidecar widths are constructor parameters.
2. Publication requires an authoritative full block BID, a valid source RID,
   and a valid ring BID equal to that full BID's ring projection. Missing or
   inconsistent identity remains resident and raises
   `blockedByMissingIdentity`; no fallback BID is fabricated.
3. A valid event publishes exactly once to `RecoveryBackendControl`. The event
   remains resident after source acceptance so cleanup order and load-ID
   sidecars remain stable until `intentConsumed`.
4. Accepted cleanup may consume the resident event and capture the next event
   in the same cycle. Explicit cancellation dominates simultaneous capture
   and suppresses same-cycle publication of an older retained event.
5. The source emits Linx `InnerFlush` semantics and scalar execution identity.
   It imports no ARM exception class, condition flags, power state, exclusive
   monitor, or architectural register behavior.

## Integration Status

R646 connects source index zero in the full fetch/RF/ALU composition. Execute
redirects carry their resident ROB full BID, RID, STID, order, and LSID.
External backend, issue, store, replay-LIQ, and ResolveQ cleanup consumes the
same accepted recovery intent as the ROB. Marker-only redirects remain
frontend-local because their incremented cleanup ring BID does not yet have an
authoritative matching full BID; the source never substitutes the retiring
marker's prior full BID. Other BCC/IEX/PE and scalar-LSU sources remain
separate promotion points.
R647 replaces generic intent consumption with two matched inputs. `sourceResolved`
releases residency after cleanup, cancellation, or drop; `payloadIntentConsumed`
authorizes order/LSID sidecars only when the accepted intent retained this
source's exact request payload. Consume-and-replace remains atomic.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarRedirectRecoverySourceSpec`
- `bash tools/chisel/run_chisel_scalar_redirect_recovery_source_probe.sh`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTopSpec`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`

The generated-RTL probe proves cycle-level publish-once retention,
missing-identity and invalid-RID blocking, backpressure, consume-and-replace,
cancellation priority, same-cycle publication suppression, and sidecar
retention. The integrated tests and cross-check prove unchanged architectural
commit behavior.
