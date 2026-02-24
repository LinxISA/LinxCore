# Pipeline stages (bring-up spec)

This document records key stage-level decisions.

## F4 → Decode interface

- F4 provides a 4-slot window per cycle.
- Each slot is `(pc, raw_bits[64], valid)`.
- `raw_bits[64]` must be a **continuous 64-bit view from pc**.
  - Decode must NOT concatenate across slots to form 48/64-bit instructions.
- Slot consumption is **strictly in-order** (slot0 → slot3).
  - Decode stops at first invalid/kill; no slot compaction / skipping.

## D1 / D2 / D3 (decode→uop→rename)

- D1 outputs **4 uops/cycle**.
- D1 performs **split/fuse**.
  - Split can be partially emitted (e.g. STA emitted, STD pending).
  - Pending split uops must be emitted before consuming newer instructions.
- D2 outputs **4 uops/cycle**.
- D2 performs **rename** using typed tags (multiple namespaces).
  - Stall policy: if any uop in the 4-uop group cannot allocate required rename resources, **stall the whole group**.
- D3 outputs **4 uops/cycle** and exists primarily for timing closure.

## BBD handling

- BBD does not have an IQ.
- BBD must be resolved before uops enter IQs.

## IQ plan (current)

- `alu_iq0`: ALU-only
- `shared_iq1`: ALU + SYS + FSU (single physical FIFO)
- `bru_iq`: BRU
- `agu_iq`: AGU
- `std_iq`: STD
- `cmd_iq`: CMD

## VEC decoupled block

- VEC block body is executed by `eng_vec`.
- Core does not dispatch `V.*` body instructions as core uops.
- Core issues a launch command; the vec engine fetches the body using its **own I$**.
- Launch includes **BodyTPC only**; termination is determined by body terminator semantics.

## CMD IQ / command assembly

- CMD IQ + CMD pipe read descriptor arrays and assemble a command.
- Assembled commands are enqueued into BIQ.
- Block command lifetime is tracked in BROB via BID.

## Block structured BROB model

- Blocks may execute out-of-order and complete out-of-order.
- Only the oldest block may retire.
- Flush can occur for any block; exceptions are precise and are reported only when the oldest block retires.
