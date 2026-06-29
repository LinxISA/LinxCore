# LinxCoreFrontendTraceTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreFrontendTraceTopSpec.scala`
- Child owners:
  `rtl/LinxCore/chisel/src/main/scala/linxcore/frontend/F4DecodeWindow.scala`,
  `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- LinxCoreModel evidence:
  `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`,
  `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`,
  `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`,
  `model/LinxCoreModel/model/interface/CommitInfo.h`
- Previous pyCircuit owner: `rtl/LinxCore/src/linxcore_top.py`,
  `rtl/LinxCore/src/top/top.py`, `rtl/LinxCore/src/top/modules/xchk.py`
- Contract IDs: `LC-IF-CHISEL-TOP-002`, `LC-IF-CHISEL-XCHK-004`

## Purpose

`LinxCoreFrontendTraceTop` is the first Chisel top boundary that feeds a raw
frontend decode packet through `F4DecodeWindow` and `DecodeRenameROBPath`
before emitting monitored commit rows. It is a bring-up trace top, not the
default bootable core. The purpose is to move the Verilator/QEMU cross-check
path from replaying prebuilt commit rows toward driving frontend packets and
observing commit rows produced by Chisel-owned decode, rename, BROB/ROB, and
commit state.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `in` | `FrontendDecodePacket` | `in.valid` | Host/testbench supplied 8-byte frontend window, PC, PE/STID, packet UID, and checkpoint ID. |
| input | `frontendFlushValid` | `Bool` | valid | Clears the F4/decode-to-rename frontend boundary in this reduced trace top. Backend recovery cleanup remains tied off. |
| input | `completeValid` | `Bool` | valid | External execution surrogate that marks one ROB row complete until real execute owners are wired. |
| input | `completeRobValue` | `UInt(log2Ceil(robEntries).W)` | `completeValid` | ROB row completed by the surrogate. |
| input | `deallocReady` | `Bool` | ready | Allows retired rows to deallocate through the integrated ROB/T/U retire path. |
| output | `f4ValidMask` | `UInt(decodeWidth.W)` | combinational | F4 slot-valid mask generated from `in.window`. |
| output | `f4SlotCount` | `UInt` | combinational | Number of valid F4 slots in the input window. |
| output | `decodeReady` | `Bool` | ready | The wrapped decode/rename/ROB path can accept the next frontend packet. |
| output | `selectedValid` | `Bool` | combinational | At least one decoded slot is selected for the reduced backend path. |
| output | `selectedRobValue` | `UInt` | combinational | Allocator ROB slot for the selected row; useful for the temporary completion surrogate. |
| output | `selectedBlockBid` | `UInt(64.W)` | combinational | Full hardware block BID allocated for the selected row. |
| output | `decRenPushFire` / `decRenPopFire` | `Bool` | pulse | Decode-to-rename queue acceptance and rename consumption pulses. |
| output | `renamedOutValid` / `renamedAccepted` | `Bool` | valid/pulse | Reduced rename output visibility. The wrapper ties downstream renamed output ready high. |
| output | `robAllocFire` / `robRenameUpdateFire` | `Bool` | pulse | BROB/ROB reservation and post-rename sidecar update pulses. |
| output | `completeAccepted` / `completeIgnored` | `Bool` | pulse | Completion surrogate result from the integrated allocator/ROB path. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Monitored commit rows produced by the integrated decode/rename/ROB path. |
| output | `commitValidMask`, `commitCount` | `UInt` | combinational | Commit row mask and count. |
| output | `commitMonitor*`, `commit*Error` | monitor flags | combinational | Commit-trace structural contract diagnostics. |
| output | `deallocValidMask`, `deallocCount` | `UInt` | combinational | Deallocation-source window from the integrated ROB/T/U retire path. |
| output | `empty`, `full`, `size`, `outstandingCount` | state | combinational | Integrated ROB occupancy state. |
| output | `commitHead*` | state | combinational | Current commit head status and ROB slot. |
| output | `occupiedMask`, `completedMask`, `retiredMask` | `UInt(robEntries.W)` | combinational | Integrated ROB state masks for debug and future Verilator drivers. |
| output | `idle` | `Bool` | combinational | Alias for integrated ROB `empty`. |

## State

The wrapper owns no persistent architectural state. State remains in the child
owners:

- `F4DecodeWindow` owns only combinational window slicing.
- `DecodeRenameROBPath` owns the reduced decode-to-rename queue, scalar/T-U
  rename composition, store-dispatch bring-up queues, BROB/ROB allocation,
  post-rename ROB sidecar update, commit, and deallocation state.

## Logic Design

The wrapper drives `F4DecodeWindow` from the external frontend packet, then
passes the generated D1 packet, F4 slots, and slot-valid mask into
`DecodeRenameROBPath`. It ties `renamedOutReady` high because no issue queue is
composed yet, and it ties backend cleanup, checkpoint, commit-map update, and
store-execute inputs inactive.

The temporary `completeValid/completeRobValue` input is deliberately explicit:
until execute/LSU owners are live behind this top, a Verilator harness must
mark rows complete before they can retire. This preserves the model-derived
ROB rule that only completed head rows retire while keeping completion
ownership outside generic top glue.

`LinxCoreFrontendAluTraceTop` is the first sibling wrapper that replaces this
surrogate for the reduced scalar ALU smoke. Keep this wrapper as the R80
frontend-to-ROB regression gate; add live execute behavior to the ALU wrapper
or a later bootable top.

## Model Alignment

`DCTop::Work` selects decoded scalar instructions, assigns `inst->rid` from
`SPEROB::getAllocPtr`, calls `SPEROB::allocROB`, assigns load/store IDs, then
writes `dec_ren_q`. `SPEROB::allocROB` stores the `(bid,gid,rid)` identity and
the `SimInst` pointer in the ROB row. `SPERename::Rename` later consumes
`dec_ren_q` and mutates the same instruction with renamed GPR and local T/U
sidecars.

The Chisel wrapper preserves that boundary by using `DecodeRenameROBPath`,
which reserves BROB/ROB before decode-to-rename queue entry and later applies
post-rename sidecar updates to the ROB row. `CommitInfo` identity remains
`bid/gid/rid` while the full hardware block identity remains the separate
64-bit `blockBid` sideband.

## Timing

F4 slot generation and decode selection are combinational within the cycle.
Decode-to-rename queueing, rename state, ROB reservation, completion, commit,
and deallocation timing are inherited from `DecodeRenameROBPath` and its child
owners. Completion is externally supplied and is not a same-cycle substitute
for a real execute stage.

## Flush/Recovery

`frontendFlushValid` flushes F4/decode queue state in this reduced trace top.
Full backend `RecoveryCleanupIntent`, checkpoint restore, ROB prune, LSU/STQ
cleanup, frontend restart, and precise trap ownership are tied inactive until a
later top integrates the recovery owner.

## Trace/Observability

`commit.rows` is the first top-level Chisel commit stream produced from
frontend packet decode rather than direct commit-row replay. It still is not a
CoreMark-capable execution stream because fetch, issue, execute, load/store
execution, memory, and recovery are not live in this wrapper.

Future Verilator drivers should use `decodeReady`, `selectedRobValue`,
`commitHead*`, and the ROB masks to drive a bounded frontend-window smoke before
promoting this path into full QEMU/CoreMark comparison.

`tools/chisel/run_chisel_frontend_trace_top_xcheck.sh` is the first such
driver. It emits the trace top, builds a Verilator harness, drives three scalar
frontend packets (`ADD`, `ADDI`, and compressed move), uses `selectedRobValue`
as the temporary completion surrogate, dumps fixed-width DUT commit JSONL, and
compares the normalized DUT stream against a QEMU-shaped reference stream.
This proves the generated-RTL frontend packet to commit-row path, but still
does not prove real execution because writeback, memory, trap, and recovery
payloads remain tied to current reduced owners.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendTraceTop`
- `bash tools/chisel/run_chisel_frontend_trace_top_lint.sh`
- `bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath`
- `bash tools/chisel/run_chisel_trace_replay_xcheck.sh`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`
