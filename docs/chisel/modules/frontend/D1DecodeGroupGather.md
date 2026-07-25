# D1DecodeGroupGather

## Status

`D1DecodeGroupGather` is a one-entry elastic boundary between the independent
Instruction Buffer and four-wide D1 decode. It carries one
`D1InstructionGroup`, not four unrelated scalar handshakes.

The module:

- holds all four 64-bit instruction rows stable under D1 backpressure;
- preserves the complete prediction record on every lane;
- permits consume-and-replace in one cycle;
- drops a resident group only when an IFU inner flush matches its STID;
- may accept a different STID on the same cycle that a matching resident group
  is killed.

This is the production boundary. `F4DenseSlotQueue` remains verification-only;
its serialized slots cannot prove four-wide D1 throughput.

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only D1DecodeGroupGather
```

R677 passed two real Chisel simulation cases:

- a four-lane group remains stable for two stalled cycles and retains each
  lane's B-F4 prediction;
- an inner flush kills only the matching STID, after which a different STID
  can enter normally.

## Open Work

- Compose the gatherer with the Instruction Buffer in the production IFU top.
- Replace the current single-selected-lane backend admission with an atomic
  four-lane D1-to-rename contract.
- Add group-level fault and exception metadata when I-F2 is implemented.

`skill-evolve: no-update` — this module applies the already documented
four-wide ready/valid contract.
