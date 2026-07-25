# IFU Shared Bundles

## Status

`IfuBundles.scala` is the production Chisel contract shared by the decoupled
I-SIDE and B-SIDE. It does not by itself implement either engine.

The production contract uses instruction-owned state throughout:

- every instruction is represented as one fixed 64-bit
  `InstructionBufferEntry`;
- the original byte length remains explicit as `lenBytes`;
- I-F4 boundary-only predecode is represented by `isBlockStart` and
  `isBlockStop`;
- fetch identity contains PE, STID, packet UID, monotonic fetch sequence, slot,
  checkpoint, and epoch;
- every instruction owns an immutable `BranchPredictionRecord`;
- IFU-local correction uses `IfuInnerFlush` and is separate from backend
  architectural recovery.

## Prediction Record

`BranchPredictionRecord` carries the exact comparison tuple:

```text
{taken, branchPc, target, kind}
```

It also carries a monotonic prediction tag, fallthrough PC, provider, B-SIDE
stage, confidence, checkpoint, and effective epoch. The stage enum is
`Sequential, BF0, BF1, BF2, BF3, BF4`; the provider enum distinguishes
NanoBTB, uBTB, fast/final RAS, PBTB, BIM, short/medium/long TAGE, static,
indirect BTB, and loop sources.

The record is carried inside each Instruction Buffer row so D1 receives one
prediction record per lane. `D1InstructionDecodeStage` copies it into the
common `BranchPredictionSidecar`, and scalar rename copies that sidecar into
`RenamedUop`. Dispatch, ROB/issue consumers, and BRU validation must continue
to use the retained record instead of reconstructing prediction from global
state.

## Inner Flush

`IfuInnerFlush` contains:

- PE and STID;
- trigger transaction ID and fetch sequence;
- trigger packet UID and optional prediction tag;
- old epoch;
- restart PC;
- checkpoint ID;
- new fetch epoch;
- typed reason and prune scope;
- typed GHR recovery action (`None`, `Reset`, or `RestoreTrigger`);
- optional corrected/actual conditional direction to append after restore.
- typed RAS recovery action (`None`, `Reset`, or `RestoreTrigger`);
- typed RAS delta (`None`, `Push`, or `Pop`) and Call return address.

The current reasons cover ITLB miss, prediction correction, fetch replay, and
stale response. A correction proposal carries an exact history key, but GHR is
not changed until the proposal returns from `IfuRedirectArbiter` as the
canonical prune. The same event restores the request-owned RAS image and applies
its Call/Return delta. ITLB may request unkeyed oldest-killed recovery, and start
uses explicit GHR/RAS reset. No other producer may issue an unkeyed
`RestoreTrigger`; prediction and backend recovery must identify an exact live
checkpoint. This transport is scoped to I-SIDE/B-SIDE speculative state.
It must not be connected to ROB, rename, LSU, or other backend cleanup owners.

## Verification

The bundles are exercised through the real Chisel simulations in
`BSideHistoryQueueSpec`, `BSidePredictionPipelineSpec`,
`InstructionBufferSpec`, `D1DecodeGroupGatherSpec`, and
`D1InstructionDecodeStageSpec`. Those tests prove that prediction tag, exact
tuple, fallthrough, confidence, provider, stage, checkpoint, and epoch survive
B-SIDE response retention plus four-wide queueing and D1 backpressure.

## Open Work

- Carry the same record atomically through four-lane dispatch, issue/ROB, and
  BRU resolution; `DecodedUop` and `RenamedUop` transport is already present.
- Add resolved provider/alternate indices and usefulness metadata to the
  training-only payload; they do not belong in the immutable forward record.

`skill-evolve: no-update` — the reusable prediction/inner-flush rules are
already normative in the IFU design and `linx-core` skill.
