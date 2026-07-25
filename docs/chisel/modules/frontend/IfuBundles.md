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
prediction record per lane. Later packets must preserve it through decode,
rename, dispatch, and BRU validation instead of reconstructing prediction from
global state.

## Inner Flush

`IfuInnerFlush` contains:

- PE and STID;
- trigger transaction ID and fetch sequence;
- old epoch;
- restart PC;
- checkpoint ID;
- new fetch epoch;
- typed reason.

The current reasons cover ITLB miss, prediction correction, fetch replay, and
stale response. This transport is scoped to I-SIDE/B-SIDE speculative state.
It must not be connected to ROB, rename, LSU, or other backend cleanup owners.

## Verification

The bundles are exercised through the real Chisel simulations in
`BSidePredictionPipelineSpec`, `InstructionBufferSpec`, and
`D1DecodeGroupGatherSpec`. Those tests prove that prediction tag, exact tuple,
fallthrough, confidence, provider, stage, checkpoint, and epoch survive
B-SIDE response retention plus four-wide queueing and D1 backpressure.

## Open Work

- Carry the same record through `DecodedUop`/`RenamedUop` when the four-wide D1
  production path is composed with the backend.
- Add resolved provider/alternate indices and usefulness metadata to the
  training-only payload; they do not belong in the immutable forward record.

`skill-evolve: no-update` — the reusable prediction/inner-flush rules are
already normative in the IFU design and `linx-core` skill.
