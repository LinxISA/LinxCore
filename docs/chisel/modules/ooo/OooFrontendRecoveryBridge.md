# OooFrontendRecoveryBridge

## Purpose

`OooFrontendRecoveryBridge` closes recovery R4 across the external CTU, exact
O3 physical transaction, and `LinxCoreIfu`. It does not derive a restart PC
from ROB state. The recovery producer supplies one composite command containing
the exact OOO suffix authority and matching full IFU redirect metadata.

## State and ownership

```text
Idle -> RequestCtu -> WaitCtu -> RequestO3 -> WaitApply -> WaitTerminal -> Idle
             |           |                    |              |
             `-- reject -'                    `-- abort -----'-- rebuild + IFU echo
```

- command offer/capture fences only the selected STID;
- fence prevents selection and intake but never clears retained state;
- CTU must echo the exact request before O3 admission;
- exact O3 apply emits both CTU cancellation and the sole D1/D2/S1
  `stageCancel` pulse;
- IFU redirect is retained through arbitrary backpressure;
- IFU owns `newEpoch` and returns it through `canonicalFlush`;
- R4 completes after exact O3 rebuild and canonical flush, in either order;
- CTU reject or pre-apply O3 abort emits no frontend mutation and releases the
  retained CTU prepare transaction.

The command is rejected before O3 admission if group/native-BID identity is
invalid, trigger extent is zero, PE/STID/epoch disagree, prune scope disagrees
with `killTrigger`, or branch/non-branch cause disagrees with `BruRecovery` /
`OooRecovery`.

## CTU and stage integration

`ctuPrepare` carries the complete `OooGlobalRecoveryRequest` to
`OooCtuIngressBridge`. `ctuPrepared` must echo that exact request and may report
retained packet/claim/expansion state plus the active lease. No CTU state is
mutated during this phase. `ctuApply` is asserted only with exact O3 common
apply; `ctuAbort` is asserted only for the exact pre-apply O3 abort. A CTU
prepare reject prevents O3 admission and reports `CtuRejected`.

`fence` connects to `OooIfuD1Ingress`, `OooD2Stage`, and every
retained D2/D3/S1 stage owner. `stageCancel` clears fusion history and the
post-D1 retained stages only after O3 apply. `canonicalFlush` performs exact
raw-reservoir pruning and epoch rebasing after IFU accepts the redirect.

Unrelated STIDs remain eligible throughout. A fence superseding a retained
grant falls forward to the next eligible STID without a bubble, while the
target payload remains resident for abort recovery or later apply-time clear.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooFrontendRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only OooFrontendIfuRecoveryIntegration
bash tools/chisel/run_chisel_tests.sh --only OooFrontendCtuRecoveryIntegration
bash tools/chisel/run_chisel_tests.sh --only OooCtuIngressBridge
bash tools/chisel/run_chisel_tests.sh --only OooIfuRawIngress
bash tools/chisel/run_chisel_tests.sh --only OooD2Stage
bash tools/chisel/run_chisel_tests.sh --only OooThreadStageBuffer
bash tools/chisel/run_chisel_tests.sh --only OOORecoverySpec
```

The tests cover retained request/redirect backpressure, both R4 terminal
orders, unrelated canonical flush rejection, malformed composite authority,
CTU-before-O3 admission, CTU reject/abort/apply phases, exact O3 abort,
target-row preservation, unrelated-STID progress, typed O3 complete/abort
publication, and real IFU canonical epoch allocation.
