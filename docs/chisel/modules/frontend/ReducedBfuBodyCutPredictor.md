# ReducedBfuBodyCutPredictor

## Purpose

`ReducedBfuBodyCutPredictor` is the reduced Chisel boundary for the
LinxCoreModel BFU block-body geometry contract. It converts model-style
`headerPc`/`hsize`/`bsize` metadata into the live fetch/F4 actions needed by
the reduced CoreMark loop replay:

- `restartPc = headerPc + hsize`
- `cutPc = headerPc + 2 + bsize`

The `+2` body-base step follows LinxCoreModel `BFUUtils::NextBlockPC`, which
advances by one 16-bit bundle slot from the header bundle position. The module
does not predict `hsize` or `bsize`; R152 receives those payload values from
the latched reduced static-geometry path after `ReducedBfuBodyCutArm` accepts
the temporary external cut-arm. The loop-aware harness remains the temporary
arm, resolved-event source, and oracle.

## Interface

| Direction | Signal | Type | Description |
|---|---|---|---|
| input | `geometryValid` | `Bool` | Metadata for the current reduced block body is valid. |
| input | `headerPc` | `UInt(pcWidth.W)` | BFU header bundle PC. |
| input | `hsizeBytes` | `UInt(pcWidth.W)` | Header/fall restart offset from `headerPc`; compressed FALL loop replay uses `0`. |
| input | `bsizeBytes` | `UInt(pcWidth.W)` | Body size from `headerPc + 2` to the static continuation cut PC. |
| input | `f4Valid`, `f4Pc`, `f4Slots`, `f4ValidMask`, `f4TotalLenBytes` | mixed | Current F4 window and decoded slot shape. |
| output | `cutActive` | `Bool` | The computed body end is inside the current F4 window and masks this packet. |
| output | `cutPc`, `restartPc` | `UInt(pcWidth.W)` | Computed static continuation cut PC and FALL restart PC. |
| output | `advanceBytes` | `UInt(4.W)` | Source advance for this F4 packet; reduced to `cutPc - f4Pc` when active. |
| output | `validMask`, `slotCount` | mixed | F4 slot mask/count after body-boundary clipping. |

## Logic

The module is combinational. When `geometryValid` is false, or the computed
`cutPc` is outside the current 8-byte F4 window, it passes through
`f4ValidMask`, `f4TotalLenBytes`, and the original slot count. When the cut is
inside the window, each F4 slot at or after `cutPc` is masked off and
`advanceBytes` is reduced to the cut-local byte offset.

## Model Evidence

The contract is derived from LinxCoreModel:

- `model/bctrl/bfu/bfu_utils.h`: `NextBlockPC(addr)` adds `MIN_BUNDLE_SIZE`,
  and `NextBlockPC(header)` adds `spInfo->bsize` to that body base.
- `model/bctrl/bfu/bfu_sp.cpp`: `StaticPredictor::Predict` creates
  `SPMInstInfo` for block headers and calls `SetBsize` when it learns the next
  body boundary.
- `model/bctrl/BCtrl.cpp`: `ConvertToBHeader` resolves FALL blocks to
  `inst.pc + spInfo->hsize` when BFU metadata is present.

## Verification

R144 focused tests elaborate the module and check a reference CoreMark FALL
geometry case: header `0x4000630c`, `hsize=0`, `bsize=0x20`, F4 window
`0x4000632a..0x40006331`, clipped mask `0x3`, restart `0x4000630c`, and
advance `4`.

The affected top-level replay also passes with this module in the path. R150
routes its geometry payload through `ReducedBfuGeometryPredictionLatch`, and
R152 factors the external loop-reentry oracle acceptance into
`ReducedBfuBodyCutArm`. This proves that learned body-end events can feed later
cuts without allowing same-cycle or stale sequential-packet clipping:
`BUILD_DIR=generated/r144-bfu-geometry-loop-replay
FETCH_EXPECTED_ROWS=/tmp/r142-loop.expected.jsonl
FETCH_ELF=tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`
compares 1330 normalized QEMU/DUT rows with zero mismatches.
