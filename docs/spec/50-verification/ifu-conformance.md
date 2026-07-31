# IFU conformance

## Fixed delivery and backpressure conformance {#VER-IFU-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFU-001,IFU-003 -->

`IFUISideSpec` shall elaborate W2, W4, W6, and W8 IFU profiles, preserve
2-, 4-, 6-, and 8-byte instruction lengths, accept partial packets, and hold
the complete visible packet stable while CTU is stalled.

## I-SIDE miss and cross-line conformance {#VER-IFU-002}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFU-002 -->

`IFUISideSpec` shall observe an ITLB miss, translation refill, I-cache miss,
ordered line-beat refill, and a cross-line instruction assembled into one
64-bit container. It shall inject a mismatched generation-qualified memory
response and prove that the live request remains unchanged. It shall also
inject a denied instruction-line response, prove that no line refill is
installed, and observe one fetch-fault packet with the original error cause.

## Recovery and ownership conformance {#VER-IFU-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFU-004,IFU-005,IFU-006 -->

`IFUISideSpec` shall check exact prepare echo, a fenced matching apply, scoped
STID pruning, preservation of unrelated retained instructions, redirected
nonzero-STID progress, prediction metadata retention, and the absence of ROB
or BROB identity allocation from the IFU-to-CTU payload.

## Observational trace conformance {#VER-IFU-004}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFU-007 -->

`IFUISideSpec` shall hold the trace consumer not ready across multiple
IFU-to-CTU transfers, prove that instruction delivery continues, and prove
that the already-presented trace packet remains stable.

## B-SIDE and recovery conformance {#VER-IFU-005}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFU-008,MEC-IFU-006,MEC-IFU-004 -->

`IFUPredictionSpec` shall check public B-SIDE provider rank behavior,
checkpoint-owned stale training rejection, and elaboration under the
`linxcore.ifu` package. `IFURecoverySpec` shall check backend-over-prediction
redirect priority, retained redirect hold under backpressure, atomic
mispredict training plus recovery, and the absence of a direct IEX control
port. `IFUCTUIntegrationSpec` shall check retained IFU-to-CTU backpressure,
scoped recovery fencing, and W2/W4/W6/W8 public IFU elaboration with explicit
B-SIDE and redirect-recovery boundaries.
