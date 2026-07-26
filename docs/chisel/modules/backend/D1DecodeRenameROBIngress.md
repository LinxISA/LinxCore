# D1DecodeRenameROBIngress

## Purpose

`D1DecodeRenameROBIngress` is the fixed-width production handoff from the
four-wide D1 decoder to the existing rename/ROB owner:

```text
D1DecodedInstructionGroup
        |
        v
D1DecodedLaneQueue -> DecodeRenameROBPath -> ScalarTURenameBridge
                                      `----> DispatchROBAllocator
```

The ingress accepts all valid lanes of one D1 group atomically. It retains the
already decoded uop, opcode metadata, original lane, and complete B-F4
prediction sidecar, then presents lanes in program order as backend capacity
becomes available. It does not recreate a fetch packet, byte window, or
`F4Slot`.

`DecodeRenameROBPath` has a parameterized verification packet adapter for the
reduced regression shells. The production ingress elaborates that path with
the adapter disabled. Its generated RTL therefore contains neither
`FrontendDecodeStage`, `F4DecodeWindow`, nor `F4DenseSlotQueue`.

## Queue and recovery contract

- Queue depth is a power of two and at least four entries.
- A group is accepted only when every valid lane fits; dequeue credit is
  registered and does not form a ready loop.
- Input masks must be a dense prefix. Invalid-opcode rows remain resident and
  are identified by `invalidOpcodeMask`, even though their decoded-uop valid
  bit is false.
- Backpressure preserves the head uop and prediction identity exactly.
- `KillTriggerAndYounger` removes the trigger and younger queued lanes.
- `PreserveTriggerKillYounger` keeps the trigger, removes younger lanes, and
  rebases the surviving prediction epoch to the canonical new epoch.
- Rows that already crossed into ROB are not invalidated by an IFU inner
  flush. They are owned by full-BID backend recovery.

The queue keeps a private group ID so ACRC can classify an immediately
following same-group `C.BSTOP` without reconstructing instruction bytes.

## Current scope

This wrapper proves the production D1 transport and a real rename/ROB
consumer. It still serializes the four decoded lanes into the current
single-row backend admission point. Four-row atomic D2/D3 reservation,
dispatch/issue composition, authoritative BRU/full-BID recovery sources, and
natural CoreMark/Dhrystone top promotion remain separate milestones.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only D1DecodedLaneQueue
bash tools/chisel/run_chisel_tests.sh --only D1DecodeRenameROBIngress
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_d1_decode_rename_rob_ingress_probe.sh
```

The focused tests cover atomic four-lane intake, program order, registered
capacity, backpressure, malformed/invalid-opcode handling, precise prune,
prediction-correction epoch rebase, direct predecoded selection, prediction
sidecar preservation through rename, ROB allocation, completion, and retire.
The generated gate elaborates and Verilator-lints the real queue/rename/ROB
graph and rejects packet/window decoder modules.
