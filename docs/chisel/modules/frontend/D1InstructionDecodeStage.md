# D1InstructionDecodeStage

## Purpose

`D1InstructionDecodeStage` is the promoted four-wide IFU-to-backend
compatibility decode owner.
It consumes `D1InstructionGroup` directly from the canonical IFU. It never
reconstructs an instruction byte window and never converts entries through
`F4Slot`.

The production OOO target moves full D1 decode, ordinary multi-uop expansion,
and boundary fusion behind the raw fixed-64-bit IFU/CTU ingress. The current
module remains the semantic decode oracle until `D1DecodeExpandFuse` and its
IFU integration tests replace it; it must not be interpreted as evidence for
2/4/6 decode width or grouped D2/D3/S1 admission.

## Input and output contract

Each input lane already contains one fixed 64-bit instruction container, its
original 2/4/6/8-byte length, exact fetch identity, dynamic instruction UID,
boundary-only I-F4 metadata, and final B-F4 prediction record.

The output is one atomic `D1DecodedInstructionGroup` containing:

- a dense row-present prefix and four `DecodedUop` lanes;
- opcode/operand/immediate full-decode metadata;
- invalid-opcode, block-boundary, block-stop, load, and store masks;
- a backend-safe prediction sidecar on every valid uop.

The common sidecar preserves prediction validity/tag, transaction ID, packet
UID, fetch sequence, request PC, direction, branch PC, target, fallthrough,
kind, provider, B-SIDE stage, confidence, checkpoint, and epoch. Provider and
stage use fixed-width UInt encodings in the common bundle
so backend types do not depend on frontend enum declarations. Static width
requirements guarantee that all current enum values fit.

## Identity

I-F3 owns a 64-bit monotonic instruction-UID allocator. Every accepted
instruction receives the next UID in program order, independently of fetch
packet identity; a blocked output keeps the same UIDs stable, and an inner
flush does not reuse allocated values. The original fetch packet UID, fetch
slot, checkpoint, epoch, PC, and STID remain separately available.

## Flush and backpressure

Every lane is tested independently against typed `IfuInnerFlush`. Because
canonical trigger-and-younger pruning preserves program order, survivors must
form a dense older prefix. A fully killed group is consumed without publishing
output. A partially killed group publishes only surviving lanes. The input
remains backpressured while a surviving decoded group is blocked downstream.
`validMask` describes row presence, including an invalid opcode; the separate
`invalidOpcodeMask` marks rows that must become an illegal-instruction action
downstream, so one invalid lane cannot create a sparse younger group.

For each surviving lane, assertions require a valid final B-F4 prediction and
exact checkpoint/epoch agreement between prediction and instruction identity.

## Shared decode leaf

`FrontendInstructionDecodeLane` owns the opcode table, operand decode,
boundary target, and `DecodedUop` construction for one fixed-width entry. The
production D1 stage and the verification packet wrapper both instantiate this
leaf, preventing decode semantic drift without putting `F4Slot` on the
production path.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only D1InstructionDecodeStage
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only ISideF3F4
bash tools/chisel/run_chisel_d1_instruction_decode_probe.sh
```

The Chisel tests cover atomic four-wide decode, prediction preservation,
backpressure, precise partial-group flush, invalid-opcode row presence, and
instruction-UID uniqueness across consecutive groups and fetch-packet high-bit
changes. The emitted-RTL Verilator probe repeats four-wide decode, two-cycle
blocking stability, UID preservation, prediction sidecars, and partial flush.

## Remaining boundary

`DecodedUop` and `RenamedUop` carry the complete sidecar, scalar rename copies
it unchanged, and `IfuBackendFeedbackBridge` implements type-specific
Dispatch/BRU comparison plus exact IFU training/restart transport. Production
multi-uop expansion/fusion, grouped ROB publication, four-thread
rename/dispatch, event-producer wiring, and backend exact BID-generation
cleanup remain open and are not implied by this module.
