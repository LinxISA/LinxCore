# LoadReplayReturnDataExtract

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnDataExtract.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadReplayReturnDataExtractSpec.scala`
- Packet baseline:
  - LinxCore: `50f1228e88ac2e43d1ac43600a28d71178525dd5`
  - LinxCoreModel: `793722e85c62eade9ab4e8481c9577dc5b9c98f7`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::returnData`
    - `LDQInfo::processCrossRtn`
    - `LDQInfo::sendCrossRtn`
  - `model/LinxCoreModel/model/ModelCommon/LSUUtils.cpp`
    - `ExtractData(uint512_t &, uint64_t, uint32_t)`
    - `SignExtend(uint64_t, Opcode)`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemReqBus.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadForwardPipeline.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayReturnConsumerReady.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala`
- Contract IDs: `LC-CHISEL-LSU-REPLAY-RTN-003`

## Purpose

`LoadReplayReturnDataExtract` owns the scalar data transform used by the model
after a replayed load has complete line data and before the result can enter
the IEX load-return path. It mirrors scalar extraction plus the architectural
merge in `LDQInfo::processCrossRtn`/`sendCrossRtn`:

1. select bytes from the 512-bit line with `addr[5:0]` and `size`,
2. split crossing requests into first- and second-line byte masks,
3. require every requested byte in both lines to be valid,
4. zero-extend or sign-extend byte, halfword, and word forms into a 64-bit
   scalar result.

The module is intentionally separate from `LoadForwardPipeline` integration.
R307 carries the derived `returnSignExtend` bit through the reduced replay
candidate, queue, LIQ row, and launch selector, but the current row still does
not carry the full model opcode/destination payload needed to publish a real
LRET entry. This owner therefore exposes an explicit `signExtend` input and a
standalone data-valid contract for the future LRET payload owner.
R308 wires this owner into the opt-in replay-LIQ top as a dormant diagnostic:
the selected replay row supplies address, size, and `returnSignExtend`, while
the grant-gated replay base-data aligner supplies line data and valid bytes.
The extracted data is exposed only on `reducedLoadReplayLiqReturnData*`
diagnostics; it does not enqueue LRET, drive mem wakeup, or arm replay launch.

R541 extends the generated-RTL sideband report to persist this owner's existing
top outputs. The replay-loop probe observed `return_data_candidate_valid=0`,
`return_data_valid=0`, `return_data_blocked_by_no_candidate=109`, and
`return_data_blocked_by_incomplete_bytes=0`. Combined with
`liq_base_lookup_granted=4` and `liq_base_data_returned=4`, this proves the
current publish blocker is not byte extraction itself; no source-returned row
reaches the extractor candidate input.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `enable` | Replay-return extraction is active. |
| `returnValid` | A selected replay return has reached the data-extraction boundary. |
| `lineData` | 64-byte final line image after baseline data and store-forward merge. |
| `lineValidMask` | Byte-valid mask for `lineData`. |
| `secondLineData` | Next 64-byte line image for a crossing scalar request. |
| `secondLineValidMask` | Byte-valid mask for `secondLineData`. |
| `addr` | Load byte address. `addr[5:0]` selects the byte offset within the 64-byte line. |
| `size` | Load size in bytes. Supported scalar sizes are 1, 2, 4, and 8. |
| `signExtend` | Extends 1-, 2-, or 4-byte results as signed values when asserted; 8-byte results pass through. |

### Outputs

| Signal | Description |
|---|---|
| `candidateValid` | `enable && returnValid`. |
| `requestByteMask` | Requested byte lanes in the first 64-byte line. |
| `secondRequestByteMask` | Requested byte lanes in the second line; zero for a non-crossing request. |
| `bytesComplete` | Every requested byte is present across the required line masks. |
| `crossLine` | The request crosses the 64-byte line. |
| `sizeSupported` | Candidate size is one of 1, 2, 4, or 8 bytes. |
| `rawData` | Little-endian extracted scalar bytes before extension. |
| `data` | Extended 64-bit scalar return data. Valid only with `dataValid`. |
| `dataValid` | Candidate has supported nonzero size and every requested byte is complete. |
| `blockedByDisabled` | A return candidate is present while extraction is disabled. |
| `blockedByNoCandidate` | Extraction is enabled but no return candidate is present. |
| `blockedByZeroSize` | Candidate carries size zero. |
| `blockedByUnsupportedSize` | Candidate size is not 1, 2, 4, or 8 bytes. |
| `blockedByCrossLine` | Crossing candidate lacks complete requested bytes in one or both lines. |
| `blockedByIncompleteBytes` | Candidate is otherwise extractable but not every requested byte is valid. |

## State

The module is combinational and owns no state.

## Logic Design

The extractor treats each 512-bit line as a little-endian byte array, computes
`off = addr & 0x3f`, and supports scalar sizes through eight bytes. For each
output byte it selects the first line when `off + i < 64`, otherwise byte
`off + i - 64` from the second line:

```text
result = sum(select(line0, line1, off + i) << (8 * i)) for i in 0 until size
```

`SignExtend(data, opcode)` then sign-extends only signed byte, halfword, and
word load opcodes. Unsigned byte/halfword/word opcodes are masked to their
natural width, and 64-bit load forms are left unchanged.

The Chisel owner follows that split without embedding opcode values:

```text
candidate = enable && returnValid
crossLine = candidate && size != 0 && addr[5:0] + size > 64
dataValid = candidate && supportedSize && bytesComplete
data = signExtend ? signed(rawData, size) : zeroed(rawData, size)
```

The LIQ is the stateful pairing owner: it retains the first completed line and
supplies that line beside the second E4 line. The extractor performs only the
combinational byte assembly. This preserves the model rule that no LRET or
mem-wakeup is visible until both halves are complete.

## Deferred Owners

- Connecting the carried replay-row `returnSignExtend` sideband to live
  extraction once real LRET payload ownership exists.
- Destination physical tag, IEX pipe, and full LRET payload formatting.
- Real IEX load-return queue enqueue/backpressure.
- Real mem-wakeup payload and ready-table/issue wakeup fanout.
- Parallel cross-line half launch; canonical Chisel currently launches phases sequentially.
- Tile-transfer load return data.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadReplayReturnDataExtract
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r306-replay-return-data-extract-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
FETCH_REDUCED_STORE_REPLAY_LIQ=1 BUILD_DIR=generated/r308-replay-return-data-diagnostic-xcheck bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh
```

Reference tests cover little-endian byte extraction, byte/halfword/word
sign-extension, unsigned zero-extension, incomplete byte masks, complete and
incomplete cross-line assembly, unsupported sizes, empty/disabled candidates, zero-size blocking,
and Chisel elaboration.
