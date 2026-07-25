# IFU Shared Bundles

## Status

`IfuBundles.scala` is the first production-shaped Chisel contract shared by the
decoupled I-SIDE and B-SIDE migration. It does not by itself implement either
engine.

The contract removes the legacy packet-only handoff from the new path:

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

It also carries provider, B-SIDE stage, checkpoint, and epoch. The stage enum is
`Sequential, BF0, BF1, BF2, BF3, BF4`; the provider enum distinguishes the
NanoBTB, uBTB, fast/final RAS, PBTB, BIM, short/medium/long TAGE, static,
indirect BTB, and loop sources.

The record is carried inside each Instruction Buffer row so D1 receives one
prediction record per lane. Later packets must preserve it through decode,
rename, dispatch, and BRU validation instead of reconstructing prediction from
global state.

## Inner Flush

`IfuInnerFlush` contains:

- STID;
- restart PC;
- checkpoint ID;
- new fetch epoch;
- typed reason.

The current reasons cover ITLB miss, prediction correction, fetch replay, and
stale response. This transport is scoped to I-SIDE/B-SIDE speculative state.
It must not be connected to ROB, rename, LSU, or other backend cleanup owners.

## Verification

The bundles are exercised through the real Chisel simulations in
`InstructionBufferSpec` and `D1DecodeGroupGatherSpec`. Those tests prove that
all prediction fields survive four-wide queueing, backpressure, and a
per-STID inner flush.

## Open Work

- Add transaction identity for I-F1 lookup responses and I-F2 miss/refill
  ownership.
- Carry the same record through `DecodedUop`/`RenamedUop` when the four-wide D1
  production path replaces the migration path.
- Add retained B-SIDE training identity; training is not part of the immutable
  forward prediction record.

`skill-evolve: no-update` — the reusable prediction/inner-flush rules are
already normative in the IFU design and `linx-core` skill.
