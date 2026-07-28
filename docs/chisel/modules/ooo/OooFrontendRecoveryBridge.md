# OooFrontendRecoveryBridge

## Purpose

`OooFrontendRecoveryBridge` closes recovery R4 between the exact O3 physical
transaction and `LinxCoreIfu`. It does not derive a restart PC from ROB state.
The recovery producer supplies one composite command containing the exact OOO
suffix authority and the matching full IFU redirect metadata.

## State and ownership

```text
Idle -> RequestO3 -> WaitApply -> WaitTerminal -> Idle
                        |              |
                        `-- abort -----'-- O3 rebuild + canonical IFU echo
```

- command offer/capture fences only the selected STID;
- fence prevents selection and intake but never clears retained state;
- exact O3 apply emits the sole D1/D2/S1 `stageCancel` pulse;
- IFU redirect is retained through arbitrary backpressure;
- IFU owns `newEpoch` and returns it through `canonicalFlush`;
- R4 completes after exact O3 rebuild and canonical flush, in either order;
- pre-apply O3 abort emits no frontend mutation.

The command is rejected before O3 admission if group/native-BID identity is
invalid, trigger extent is zero, PE/STID/epoch disagree, prune scope disagrees
with `killTrigger`, or branch/non-branch cause disagrees with `BruRecovery` /
`OooRecovery`.

## Stage integration

`fence` connects to `OooIfuD1Ingress`, `OooD2ProductionStage`, and every
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
bash tools/chisel/run_chisel_tests.sh --only OooIfuRawIngress
bash tools/chisel/run_chisel_tests.sh --only OooD2ProductionStage
bash tools/chisel/run_chisel_tests.sh --only OooThreadStageBuffer
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
```

The tests cover retained request/redirect backpressure, both R4 terminal
orders, unrelated canonical flush rejection, malformed composite authority,
exact O3 abort, target-row preservation, unrelated-STID progress, typed O3
complete/abort publication, and real IFU canonical epoch allocation.
