# ReducedStoreStaAddressExecBridge

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreStaAddressExecBridge.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreStaAddressExecBridgeSpec.scala`
- Related Chisel:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rename/StoreSplitPayload.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/StoreDispatchToSTQ.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreExecResultBridge.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
    - `SPERename::InsertToStoreIEX`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
    - store-address dispatch handling for `OpcodeIsStore && type == ST_ADDR`
  - `model/LinxCoreModel/model/iex/iex_iq.cpp`
    - AgeQueue insertion of loads and `ST_ADDR` store work
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQueueEntryInfo::init`
    - `STQ::mergeStore`

## Purpose

`ReducedStoreStaAddressExecBridge` is the reduced-top owner for early split
store-address execution. It converts a visible STA queue head plus externally
supplied address-source read data into the `StoreDispatchExecResult` consumed
by `StoreDispatchToSTQ`.

This is intentionally separate from `ReducedStoreExecResultBridge`. The older
bridge buffers full reduced-ALU store completions, so STA cannot reach the STQ
until the full store instruction has issued with both address and data operands
ready. The C++ model does not require that coupling: rename splits a store into
STA and STD work, the issue side can dispatch `ST_ADDR`, and STQ merge records
`addrRdy=true, dataRdy=false` for an address-only half.

## Interface

Inputs:

- `enable`: enables the reduced STA bridge.
- `queueValid`, `queue`: current STA queue head. The bridge only emits when
  `queue.valid` and `queue.storeType == Addr`.
- `srcReadReady`: readiness for the original source lanes required by the
  address formula.
- `srcReadData`: read data for the source lanes used by the address formula.

Outputs:

- `exec`: `StoreDispatchExecResult` for the STA half. Data is zero because STQ
  address-only insertion does not need store data.
- `candidate`: the current queue head is a valid address-half candidate.
- `supportedOpcode`: the queue-head opcode has a known reduced-store address
  formula.
- `addrSourceMask`: source-lane mask required by the formula.
- `addrSourceReady`: every required source lane is still present in the payload
  and externally reported ready.
- `blockedBySource`: candidate is supported but an address source is not ready
  or was removed from the payload.
- `blockedByUnsupported`: candidate opcode is not covered by this reduced
  bridge.

## Logic Design

The bridge supports the reduced scalar store address formulas already used by
`ReducedScalarAluExecute`:

- PCR stores: `pc + imm`, no address RF source required.
- `OP_SDI`: `src1 + (imm << 3)`, size 8.
- `OP_SWI`: `src1 + (imm << 2)`, size 4.
- `OP_SBI`: `src1 + imm`, size 1.
- `OP_SD`: `src1 + (src2 << 3)`, size 8.
- `OP_C_SDI`: `src0 + (imm << 3)`, size 8.
- `OP_C_SWI`: `src0 + (imm << 2)`, size 4.

For every required source lane, the bridge requires both the payload source
valid bit and the external read-ready bit. This is deliberate: R475 proved the
live `LDI`/`SDI`/`LDI` stimulus already exposes a STA queue head, but the
current reduced top has no spare RF/local read owner for that queue head.
Adding this bridge first creates the exact next interface boundary: a top-level
owner must provide read data/readiness for the address lanes without waiting
for full scalar-store issue.

The compressed store cases are marked supported but still require source 0.
Current non-PCR `StoreSplitPayload` zeroes STA source 0, so the bridge reports
`blockedBySource` for those rows. That preserves the existing payload contract
and makes the compact-store source-loss visible instead of silently computing
from a zeroed operand.

## Model Alignment

The model path splits store address and store data before execution:

1. `SPERename::InsertToStoreIEX` clones a store into `ST_ADDR` and `ST_DATA`
   work for split stores.
2. IEX dispatch treats store-address work as the row that can enter the LSU
   address path.
3. `STQueueEntryInfo::init` records address-only rows with address ready and
   data not ready.
4. `STQ::mergeStore` later merges the STD half by identity.

`ReducedStoreStaAddressExecBridge` follows the same ownership boundary for the
reduced Chisel bring-up path: STA address calculation is a first-class producer
of `StoreDispatchExecResult`, while STD/data can remain on the existing
full-completion bridge until a dedicated STD owner is introduced.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreStaAddressExecBridge
```

R476 covered:

- `OP_SDI` computes address from source 1 and ignores store-data source 0.
- `OP_SDI` blocks when source 1 is not ready.
- PCR store STA computes from `pc + imm` without RF source readiness.
- `OP_C_SDI` exposes the current STA source-0 payload loss as a source block.
- The Chisel module elaborates with the reduced top interface parameters.

## Deferred Owners

- Default-on live promotion. R479 wires top-level RF/local read data and
  opt-in STA selection for replay-LIQ trials, but the switch remains disabled
  by default because the enabled fixture still fails final idle drain.
- Arbitration between the existing scalar issue RF reads and STA address reads.
- Replay-LIQ allocation-row lifecycle after early STA. R480 proves the enabled
  early-STA trial leaves `replayLiqResidentCount=1` while ResolveQ and all
  reduced-store commit/drain/STQ state are empty at timeout; the next owner
  must expose or clear that resident LIQ row before changing the idle
  predicate.
- STD/data execution ownership beyond the existing buffered full-store bridge.
