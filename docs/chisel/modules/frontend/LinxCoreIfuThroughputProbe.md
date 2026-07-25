# LinxCoreIfuThroughputProbe

## Purpose

`LinxCoreIfuThroughputProbe` is the generated-RTL throughput proof shell for
the canonical `LinxCoreIfu`. It instantiates the real I-F0–I-F4 and B-F0–B-F4
composition, final-prediction join, Instruction Buffer, and four-wide D1 path.
It is verification infrastructure, not a second IFU architecture owner.

## Configuration

- one active STID;
- architectural 64-byte cachelines and 4-KiB pages;
- eight prediction-join rows and eight miss rows;
- eight L1I sets and a 64-entry Instruction Buffer;
- identity-mapped executable ITLB preload on `start`;
- a synthesizable one-request line responder whose line contains thirty-two
  dense 16-bit instructions.

A first start warms four lines. A second start clears speculative frontend
work while preserving physical ITLB/L1I state, then restarts at `0x1000`.

## Required Result

For thirty-two consecutive accepted cycles, the probe requires:

- `d1.valid && d1.ready` with `validMask == 0xf`;
- PCs `0x1000..0x10fe` in two-byte increments without gaps or repeats;
- final B-F4 prediction metadata on all four lanes;
- no canonical redirect in the dense sequential window;
- prediction-join and line-context high-watermarks of at least two.

The current Verilator run observes join/context peaks of eight/six. The probe
exports a semantic `d1PredictionFinal` signal so the C++ harness does not
depend on Chisel Enum encodings.

## Regression Found

An eight-group cacheline originally replayed its first group. The join row used
a narrow `emitIndex + 1` expression; at the maximum group index, the addition
wrapped before comparison with `groupCount`. `IfuPredictionJoin` now widens the
addition explicitly, and its focused suite includes an exact eight-group
retirement regression.

## Run

```bash
bash tools/chisel/run_chisel_tests.sh --only IfuPredictionJoin
bash tools/chisel/run_chisel_tests.sh --only LinxCoreIfuThroughput
bash tools/chisel/run_chisel_ifu_throughput_gate.sh
```

## Claim Boundary

This gate proves that an eligible, warmed, dense sequential canonical IFU can
supply four instructions per cycle while multiple line transactions are in
flight. It does not prove mixed instruction lengths, correction/recovery under
predictor stress, production D1 decode/rename/dispatch acceptance, or
CoreMark/Dhrystone throughput.
