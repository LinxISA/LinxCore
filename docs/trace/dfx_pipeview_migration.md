# DFX Pipeview Migration

## Goal

Move LinxCore Konata generation from legacy synthetic `trace_evt_*` aggregation to pyCircuit DFX occupancy probes (`dbg__occ_*`) that expose per-stage, per-lane uop residency.

## Legacy vs DFX

- Legacy path:
  - Single-event arbitration (`trace_evt_valid_top` + `trace_evt_*` bundle)
  - Could hide concurrent stage activity in the same cycle
  - Keying by `(rob, seq)` in TB
- DFX path:
  - Per-cycle occupancy sampling via `dbg__occ_<stage>_*` probe family
  - One dynamic uop line keyed by `uop_uid`
  - Supports explicit template-uop flow and replay lineage (`parent_uid`)

## Canonical DFX probe contract

For each stage/lane probe set:

- `dbg__occ_<stage>_valid_lane<k>_<stage>`
- `dbg__occ_<stage>_uop_uid_lane<k>_<stage>`
- `dbg__occ_<stage>_pc_lane<k>_<stage>`
- `dbg__occ_<stage>_rob_lane<k>_<stage>`
- `dbg__occ_<stage>_kind_lane<k>_<stage>`
- `dbg__occ_<stage>_parent_uid_lane<k>_<stage>`
- `dbg__occ_<stage>_stall_lane<k>_<stage>`
- `dbg__occ_<stage>_stall_cause_lane<k>_<stage>`

Kinds:

- `0`: normal
- `1`: flush
- `2`: trap
- `3`: replay
- `4`: template

## UID rules

- Fetch packet UID is allocated at `F0` and threaded through `F1..F4`.
- Decode derives per-slot dynamic uop IDs as `(pkt_uid << 3) | slot`.
- Replay must allocate a new `uop_uid`.
- `parent_uid` links replay/template descendants to the originating dynamic uop.
- Template micro-uops emitted by `CodeTemplateUnit` carry distinct `uop_uid`
  in class id `4` (`uid = (base_uid << 3) | 4`).

## Cutover gates

1. `tb_linxcore_top.cpp` consumes DFX probes as the primary Konata source.
2. Konata checker passes without duplicate instruction IDs.
3. Stage checker confirms required stages are present in CoreMark trace.
4. Labels include `pc + disassembly + uid/op/src/dst summary`.
5. Co-sim/commit JSON schema remains unchanged.

## Current status

- Konata TB consumes DFX `dbg__occ_*` probes as the canonical source.
- `IQ` is a first-class stage in the emitted probe/trace order.
- Validation scripts force `PYC_KONATA_SYNTHETIC=0`.
- Konata writer emits `Kanata 0005` with `P` occupancy records.
