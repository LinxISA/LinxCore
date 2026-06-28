# CommitTrace Packet 0B

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitIdentity.scala`
- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTrace.scala`
- Previous pyCircuit owner: `src/probes/*`, `src/bcc/backend/commit.py`
- LinxCoreModel evidence: `model/LinxCoreModel/model/interface/CommitInfo.h`
- Contract IDs: `LC-IF-CHISEL-XCHK-001`

## Purpose

`CommitTraceRow` is the typed Chisel retire-event payload for the Phase 0B
ROB/QEMU cross-check substrate. It carries the architectural commit fields
checked by `tools/trace/crosscheck_qemu_linxcore.py`, the LinxCoreModel
`CommitInfo` identity tuple, ROB debug identity, and a separate 64-bit hardware
block BID sideband.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| payload | `valid` | `Bool` | slot valid | Retiring slot marker. Invalid slots are filtered by the adapter. |
| payload | `seq` | `UInt(64.W)` | valid | Monotonic trace sequence when supplied by the producer. |
| payload | `cycle` | `UInt(64.W)` | valid | Producer cycle for debug and cross-check triage. |
| payload | `slot` | `UInt(log2Ceil(commitWidth).W)` | valid | Commit slot index inside the cycle. |
| payload | `identity.bid` | `UInt(32.W)` | valid | Model `CommitInfo` block identity component. |
| payload | `identity.gid` | `UInt(32.W)` | valid | Model `CommitInfo` group identity component. |
| payload | `identity.rid` | `UInt(32.W)` | valid | Model `CommitInfo` row/reorder identity component. |
| payload | `rob.valid` | `Bool` | valid | ROBID debug sideband is meaningful. |
| payload | `rob.wrap` | `Bool` | `rob.valid` | ROBID wrap bit from the ROB domain. |
| payload | `rob.value` | `UInt(robValueWidth.W)` | `rob.valid` | ROBID entry value from the ROB domain. |
| payload | `blockBidValid` | `Bool` | valid | 64-bit block BID sideband is meaningful. |
| payload | `blockBid` | `UInt(64.W)` | `blockBidValid` | Hardware BROB/BCTRL BID; do not truncate into `identity.bid`. |
| payload | `pc` | `UInt(64.W)` | valid | Architectural commit PC. |
| payload | `insn` | `UInt(64.W)` | valid | Raw instruction bits; enough width for 48-bit Linx encodings. |
| payload | `len` | `UInt(3.W)` | valid | Instruction length in bytes: 0, 2, 4, or 6. |
| payload | `wb.{valid,reg,data}` | bundle | valid | Architectural writeback payload. |
| payload | `src0.{valid,reg,data}` | bundle | valid | Optional source observation for mismatch diagnosis. |
| payload | `src1.{valid,reg,data}` | bundle | valid | Optional source observation for mismatch diagnosis. |
| payload | `dst.{valid,reg,data}` | bundle | valid | Destination payload; adapter mirrors WB when omitted. |
| payload | `mem.{valid,isStore,addr,wdata,rdata,size}` | bundle | valid | Architectural memory side effect. |
| payload | `trap.{valid,cause,arg0}` | bundle | valid | Precise trap envelope. |
| payload | `nextPc` | `UInt(64.W)` | valid | Architectural next PC after the row retires. |
| output | `CommitTraceWindow.validMask` | `UInt(commitWidth.W)` | combinational | One bit per valid retiring slot. |
| output | `CommitTraceWindow.validCount` | `UInt` | combinational | Number of valid retiring slots in the cycle. |

## State

`CommitTraceRow`, envelopes, and `CommitTraceWindow` are state-free. ROB,
commit control, BROB, LSU, and trap/recovery owners produce the fields.

## Logic Design

`CommitTraceWindow` only computes a valid mask and valid count from a fixed
commit-width vector. It does not compact, reorder, or serialize rows. The trace
writer or testbench must emit valid rows in commit order and must not infer
ordering from the model `CommitInfo` hash.

The normalized cross-check row must include these comparator fields:

```text
pc insn len wb_valid wb_rd wb_data
src0_valid src0_reg src0_data
src1_valid src1_reg src1_data
dst_valid dst_reg dst_data
mem_valid mem_is_store mem_addr mem_wdata mem_rdata mem_size
trap_valid trap_cause traparg0 next_pc
```

## Timing

Rows are sampled at the CMT/retire boundary. A cycle may expose up to
`commitWidth` rows. Invalid slots are legal in the raw fixed-width Chisel vector
and must be dropped before QEMU comparison; an internal valid-after-invalid
hole remains a reduced ROB harness error.

## Flush/Recovery

Flush metadata must carry enough identity to distinguish surviving and killed
rows. `identity.(bid,gid,rid)` is the LinxCoreModel `CommitInfo` identity.
`blockBid` is the hardware BROB/BCTRL identity and remains 64 bits so BID
wrap/slot information is not lost.

## Trace/Observability

`tools/chisel/trace_schema_adapter.py` accepts either flattened rows or nested
Chisel rows using names such as `identity.bid`, `mem.isStore`, `trap.arg0`, and
`nextPc`. The adapter emits the snake-case normalized schema consumed by the
QEMU comparator and preserves sideband fields for mismatch triage.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only CommitTrace`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- Future reduced ROB harness: duplicate `(bid,gid,rid)`, skipped slot, memory
  side-effect ownership, and trap-envelope tests.
