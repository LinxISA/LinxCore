# ROBID

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBID.scala`
- Previous pyCircuit owner: none as a standalone module; ROB/BROB owners embed
  age identities today.
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/ROBID.h`
  - `model/LinxCoreModel/model/ModelCommon/ROBID.cpp`
- Contract IDs: `LC-CHISEL-ROBID-001`

## Purpose

`ROBID` is the common circular age identity for reorder structures. It preserves
the model's slot plus wrap-bit ordering so ROB, BROB, and future memory-order
queues can compare identities without losing wrap information.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| payload | `valid` | `Bool` | none | Identity is usable when asserted. |
| payload | `wrap` | `Bool` | none | Age epoch bit toggled on circular wrap. |
| payload | `value` | `UInt(log2Ceil(entries).W)` | none | Slot index inside the reorder structure. |

## State

`ROBID` has no internal registers. State owners store it in their own arrays,
pointers, or queue entries.

## Logic Design

The helper object implements:

- `zero(entries)`: valid identity at slot zero, wrap clear;
- `disabled(entries)`: same encoding with `valid` clear;
- `add(id, offset)`: add an offset smaller than `entries`, toggling wrap on
  boundary crossing;
- `inc(id)`: one-entry add;
- `sub(id, offset)`: subtract an offset smaller than `entries`, toggling wrap
  on boundary crossing;
- `less`, `lessEqual`, `greater`, `greaterEqual`: wrap-aware ordering;
- `gap(newer, older)`: unsigned forward distance following model `CalGap`.

## Timing

All helpers are combinational. A state owner must register pointer updates at
the owner pipeline boundary.

## Flush/Recovery

`ROBID` only provides identity comparison. Flush and recovery modules decide
which identity is the boundary and whether equal identities survive.

## Trace/Observability

Commit and cross-check traces should emit the raw `wrap` and `value` fields
when debugging age-order failures. User-facing commit payloads may continue to
publish flattened integer IDs where the contract requires them.

## Verification

- `tools/chisel/robid_semantics_check.py` runs model-derived test vectors.
- `tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` runs the semantic
  checker and runs the Scala ROBID test when a local JDK and `sbt` exist.
