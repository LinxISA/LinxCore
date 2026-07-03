# DecodeRenameROBPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/backend/DecodeRenameROBPathSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/DCTop.cpp`
  - `model/LinxCoreModel/model/bctrl/BCtrl.cpp`
  - `model/LinxCoreModel/model/bctrl/BROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/GPRRename.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/iex/iex_dispatch.cpp`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/frontend/FrontendDecodeStage.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeLoadStoreIdAssign.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameQueue.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkLocalBankArray.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchQueues.scala`
  - `chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala`
  - `chisel/src/main/scala/linxcore/bctrl/BlockMarkerLifecycle.scala`
  - `chisel/src/main/scala/linxcore/bctrl/BlockMarkerRetireSourceSerializer.scala`
  - `chisel/src/main/scala/linxcore/bctrl/BlockScalarDoneSequencer.scala`
  - `chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`

## Purpose

`DecodeRenameROBPath` is the first reduced frontend/backend composition point.
It connects `FrontendDecodeStage`, `DecodeRenameQueue`,
`ScalarTURenameBridge`, and `DispatchROBAllocator` so one decoded scalar/T/U
uop can reserve a real BROB/ROB row before entering the registered D2/D3
queue, produce a renamed payload, and patch rename-produced ROB sidecars when
the queue head is accepted by rename.

The module exists to remove the next layer of fixture wiring. It is still a
bring-up path, not the final dispatch pipe. It selects one decoded slot per
cycle, stamps reduced memory-order identity for every valid scalar row at the
queue acceptance boundary, reserves BROB and ROB identity before enqueue,
enqueues that row into a registered `dec_ren_q` owner, and leaves full SID/LID
carry, STA/STD execution, width-wide rename, block/group commit cleanup event
ownership, and full top-level fetch/commit flow to later owners. It now owns
the queue-backed
store-dispatch to STQ row-owner boundary through `StoreDispatchSTQPath`; STA
address generation and STD data selection remain explicit inputs until the
real execution owners exist. It also composes live T/U rename and recovery
cleanup so the allocator's ROB-side source candidate and the live STQ-bank LSU
source candidate are selected, cross-checked, and published by the same state
owner that produces `tSeq/uSeq` and T/U destination sidecars.
R64 additionally wires the ROB deallocation-row T/U retire-source vector
through `TULinkRetireCommandPath`, feeding live relation-cmap mark/release
commands into `ScalarTURenameBridge.tuRetire*` with actual rename-acceptance
backpressure. R65 drives backend recovery flush into that retire path so
queued source rows and resident relation-cmap entries are pruned with
model-equivalent `FlushRelativeReg` suffix semantics.
R66 preserves the model deallocation boundary by stopping ROB deallocation at
the first block-last row in a window and forwarding that `(bid,gid)` as the
future block-clean source.
R67 lets `TULinkRetireCommandPath` consume that block-last source as a local
scalar `CleanCMAP` scheduling point after relation-cmap mark/release commands
for the block-last row have drained.
R68 exposes the following `ReportLocalRegBlockCommit` event as
`tuRetireLocalBlockCommit*`. R69 consumes that event through
`ScalarTURenameBridge`/`TULinkRecoveryCleanupPath` so the reduced T/U
local-register owner runs `ReportBlockCommit` only after the event is accepted.
R70 carries the block-last source STID through that post-clean event and
surfaces the reduced owner's STID-match diagnostics.
R71 defined `TULinkLocalBlockCommitFanout`. R72 moves that fanout under
`ScalarTURenameBridge` through `TULinkLocalBankArray`, so this backend forwards
the pending BID/STID event directly to the bridge and observes the
bridge-owned fanout diagnostics. The live array is still reduced to PE0/STID0,
but the selected-STID/all-selected-PE boundary is now part of the SGPR bank
hierarchy rather than top-level backend glue.
R73 drives the bridge's active-bank selector explicitly: active STID is taken
from the queued decoded row's `threadId`, the same sidecar already stored into
ROB/STQ rows as STID.
R74 carries the ROB deallocated row's PE/STID through
`TULinkRetireSource`, `TULinkRetireCommandPath`, `TULinkRelationCmap`, and
`ScalarTURenameBridge` so retire mark/release commands target the retired
row's SGPR bank independently of the active rename-head selector.
R75 carries the scalar PE owner as a decoded/renamed uop sidecar. The reduced
path now drives `ScalarTURenameBridge.activePeId` and
`DispatchROBAllocator.allocPeId` from the queued row's `peId`, matching the
model's `inst->peID` ownership while preserving PE0 behavior for packets that
do not yet set a nonzero owner.
R76 moves `DispatchROBAllocator.allocValid` to the decode enqueue boundary and
adds `robRenameUpdate*` observability for the post-rename ROB update. This
matches the model order where `DCTop::Work` calls `SPEROB::allocROB` before
`dec_ren_q->Write`, while `SPERename::Rename` later mutates the same
instruction object's T/U sidecars.
R287 forwards the decode memory-order sidecar into ROB allocation and exposes
`commitMemoryOrder` from `ROBEntryBank`. The sidecar carries the model
pre-increment `lsID` snapshot for every committed row, plus load/store
classification, without adding fields to `CommitTraceRow`.
R321 adds a read-only ROB current-row status query. The path forwards
`robStatusLookupValid/Rid` through `DispatchROBAllocator` to `ROBEntryBank` and
returns the lookup result without changing decode, rename, ROB allocation,
completion, commit, or deallocation behavior.
R373 adds the parallel read-only commit-trace row lookup. The path forwards
`robCommitTraceLookupValid/Rid/sourceTraceEnable` through
`DispatchROBAllocator` to `ROBEntryBank` and returns provider-shaped row
metadata without changing decode, rename, ROB allocation, completion, commit,
or deallocation behavior.
R290 also exports the selected `StoreDispatchSTQPath` `STQStoreRequest` payload
beside the existing insert-valid/accepted/index diagnostics. The reduced
replay-LIQ top consumes the accepted request as the MDB store-arrival probe,
so the payload now forms part of the backend/top observation contract instead
of remaining local to the STQ row owner.
R103 connects the existing block-last deallocation boundary to BROB lifecycle:
`ROBEntryBank` exposes the full block BID for the first deallocated block-last
row, `DecodeRenameROBPath` pulses `blockScalarDone*` for that BID, and it
pulses `blockRetire*` one cycle later so `BrobMetaTracker` observes the
completed state before clearing the entry.
R104 adds reduced marker-owned block lifecycle on top of the R101/R102 marker
consume path. A consumed `BSTART` marker allocates a BROB-only entry, records
that full BID as the active block, and a consumed `BSTOP` pulses scalar done
for the current active BID. If a new `BSTART` arrives while another block is
active, the old active BID receives scalar done before the new BID becomes
active. Scalar rows between those markers reuse the active full BID instead of
allocating another BROB entry.
R107 also records the target carried by target-bearing `BSTART` markers and
publishes a marker-stop redirect when a consumed `BSTOP` closes an active block
whose target differs from the sequential marker PC. The reduced live-fetch
top consumes this as a frontend-only restart so CoreMark direct-call headers
follow QEMU's `BSTART` target instead of executing filler marker bytes.
R119 extends the marker lifecycle to the first reduced conditional block loop.
A consumed conditional `BSTART` records both the active target and that the
active block needs a later branch decision. The following active marker waits
until the execute owner publishes a valid condition decision: fallthrough
allocates the next marker block, while taken redirects to the active block
target, clears the active state, and performs no new marker allocation.
R120 fixes the reduced direct-call target case where QEMU enters a target body
without first exposing a target `BSTART` marker. A scalar row that allocates a
new BROB while no marker block is active now becomes the active block until a
later marker boundary or block-last deallocation closes it. The packet also
adds an explicit constructor-only `reducedStoreDispatchBypass` for reduced
live RF/ALU evidence tops that produce store sidebands from ALU execute but do
not yet provide STA/STD execution plus STQ commit/free feedback. The default
keeps full store-dispatch/STQ backpressure enabled.
R123 records active direct/call marker blocks as unconditional redirects at the
next marker boundary. This closes the CoreMark case where `C.BSTART.DIRECT` at
`pc=0x400055ca` installs target `0x400055e2`, and the later
`C.BSTART.COND` at `pc=0x400055d4` must complete that active block and
redirect without allocating a new marker-owned BROB entry. Conditional active
blocks still use the committed SETC result directly: false allocates
fallthrough, true redirects to the active target.
R126 adds a reduced scalar-redirect lifecycle input for return-like execute
redirects such as `FRET.STK`. The live fetch RF/ALU top drives
`scalarRedirectValid` from `ReducedScalarAluExecute.redirectValid`; this
backend uses the pulse to complete the active full BID through the same
scalar-done path used by marker boundaries, then clears active
BID/target/condition state so the return target's first scalar row can seed a
fresh scalar-created active block.
R127 refines that scalar-redirect lifecycle for the longer CoreMark return
path. Marker-only conditional boundaries now wait only when a branch producer
or scalar ROB work can still supply a decision; zero-target/no-producer
conditional state falls through. A marker allocation that would reuse the
current active slot first pre-retires the active BID, and BROB `Flushed` slots
are treated as reusable by the allocator. Together these rules prevent stale
direct-call/return block state from poisoning later conditional marker
allocation.
R167 extracts the block scalar-done to block-retire sequencing into
`BlockScalarDoneSequencer`. `DecodeRenameROBPath` still owns reduced source
selection from marker lifecycle, scalar redirects, and ROB block-last
deallocation, but the one-cycle delayed retire pulse and retire-pending query now
live in a reusable BCTRL module. This preserves R103/R104 timing while giving
marker-row retirement a single event boundary to feed.
R168 extracts the active marker/scalar-created block lifecycle into
`BlockMarkerLifecycle`. `DecodeRenameROBPath` still detects marker-only packets
and wires allocator/decode/execute inputs, but active full-BID state, conditional
and direct marker decisions, same-slot pre-retire, scalar redirect cleanup,
block-last closure, marker readiness, and scalar-done source selection now live
under BCTRL. This is still the reduced marker-consume path; full marker-row ROB
admission and recovery-exact cleanup remained the next block-control work at
that point.
R169 forwards decoded marker sidecars into the allocation-time ROB row image and
exposes `robDeallocBlockMarkerRetireSource` from `ROBEntryBank` through this
composition boundary. That vector is the first source boundary needed before
the reduced marker-skip path can be replaced by full marker-row ROB admission.
R171 instantiates `BlockMarkerRetireSourceSerializer` behind that vector,
gates ROB deallocation on marker-source full-window queue credit, clears the
serializer on backend/block lifecycle cleanup, and exposes
`robMarkerRetireSource*` diagnostics. This makes marker-source preservation
part of the live deallocation handshake before lifecycle policy is connected.
R172 connects the serializer output to `BlockMarkerLifecycle` as a distinct
retired-marker lane. Serializer `outReady` now comes from lifecycle retired
marker readiness, the backend exposes lifecycle-ready/fire diagnostics, and
retired fallthrough boundaries use the row-owned `blockBid` carried by the ROB
row instead of requesting a retire-time BROB allocation. The reduced
decode-time marker-skip lane remains separate until full marker-row ROB
admission replaces it.
R173 wires backend recovery flush into that marker serializer. Queued marker
sources are pruned by newest flushed suffix and compacted, matching the
existing T/U retire-source cleanup policy instead of clearing all queued marker
sources on every backend cleanup.
R174 wires the backend to the per-STID active-context contract in
`BlockMarkerLifecycle`. Decode-time marker-only packets pass `marker.threadId`
as `markerStid`; scalar rows query active state with `selected.threadId`;
scalar-created active blocks install with that selected STID; and execute
redirect cleanup passes `scalarRedirectStid` so only the redirecting STID lane
is closed. The default composition still instantiates one scalar STID lane, so
existing STID0 live gates preserve their behavior while the interface is ready
for multi-STID block-control work.
R175 adds the first full marker-row ROB-admission shell. When marker rows are
not skipped, rename can accept a `sob`/`eob` row, update its reserved ROB row,
and internally consume the renamed marker instead of presenting it to the
reduced scalar issue/ALU path. A one-entry marker completion buffer reuses the
rename-produced `CommitTraceRow`, waits behind any external execute completion,
then completes the marker ROB row through `DispatchROBAllocator`. This packet
does not switch the live `LinxCoreFrontendFetchRfAluTraceTop` out of
`skipBlockMarkers=true`: the full marker-row active-BID lifecycle still needs a
two-phase decode/retire design before the live CoreMark marker-skip lane can be
removed.
R176/R177 split marker BID ownership into a decode-time context and wire that
context behind an explicit backend opt-in. In the model,
`BCtrlUnit::Work` allocates BROB state for a `BSTART`, stamps the current
block command BID onto the instruction before scalar PE decode, and
`DCTop::Work` allocates the PE ROB row before writing `dec_ren_q`. Chisel now
models that early BID-selection surface with `BlockMarkerDecodeContext`:
decoded `BSTART` rows use the allocator's new full BID even if an older active
block exists, following scalar rows reuse that active BID, decoded `BSTOP`
rows reuse and close the active BID, and accepted scalar rows can seed an
active scalar-created block. The owner keeps `decodeValid` for combinational
candidate selection separate from `decodeFire` for state mutation so allocator
readiness does not feed back into `allocUsesExistingBlock`.
R178 adds the named top-level harness
`LinxCoreFrontendFetchRfAluMarkerRowsTraceTop`, which instantiates this path
with `skipBlockMarkers=false` and `useMarkerDecodeContext=true`. The default
live CoreMark top remains in marker-skip mode until the C++ harness and
QEMU/DUT comparator stop treating legal marker rows as skip entries.
R179 adds `run_chisel_frontend_fetch_rf_alu_marker_rows_smoke.sh` as the first
generated-RTL proof for that opt-in. The smoke drives the first CoreMark dense
window through the marker-row wrapper, asserts the `C.BSTART` row is selected,
allocates ROB state, is not consumed through the skip-marker lane, records its
selected full block BID, and asserts the following scalar row reuses that BID.
This closes the elaboration-only gap for marker-row admission; it still leaves
marker-aware QEMU/DUT comparison and the default live-top switch to later
packets.
R180 extends the generated-RTL QEMU comparator with an explicit marker-row
mode. `run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --marker-rows`
emits the marker-row wrapper, asks the shared driver to admit legal marker
rows, validates their ROB commits as marker-shaped rows, and filters those
validated marker commits before scalar QEMU comparison. The first CoreMark
prefix proof now shows one admitted marker row, one filtered marker commit,
three scalar rows compared, and zero mismatches. The default top still runs the
same prefix in skip mode with zero admitted marker rows. This proves the
marker-row ROB lifecycle can be observed in generated RTL without changing the
neutral scalar comparator stream yet; larger windows and stop/redirect
lifecycle coverage are still required before the default live top can leave
`skipBlockMarkers=true`.
R192 closes the first marker-row BROB drain failure in that filtered path.
Redirecting retired marker boundaries can complete the previous active block
and still own a row-allocated marker-only BROB entry. `BlockMarkerLifecycle`
now emits that marker-owned BID as a later scalar-done source, and this backend
keeps `BlockScalarDoneSequencer` alive across redirect cleanup so the delayed
retire/free pulse for the just-completed block is not cancelled by the same
cleanup event. Non-cleanup global flushes still clear the sequencer.
R101 adds an opt-in reduced block-marker consume path for live fetch RF/ALU
evidence. When `skipBlockMarkers=true`, a packet containing only legal
`BSTART`/`BSTOP` decoded markers asserts `blockMarkerSkipValid`, drives marker
PC/instruction/length diagnostics, and reports `decodeReady` without allocating
BROB/ROB or pushing `dec_ren_q`. Packets that mix marker and scalar slots raise
`blockMarkerMixedPacket` and are not consumed until the dense multi-slot owner
exists. The default constructor keeps the old behavior for non-reduced users.

## Interface

Inputs:

- `d1`, `slots`, `validMask`, `flushValid`: D1/F4 decode inputs consumed by
  `FrontendDecodeStage`.
- `robStatusLookupValid`, `robStatusLookupRid`: read-only native RID status
  query forwarded to the ROB row owner.
- `renamedOutReady`: downstream renamed-uop consumer readiness.
- `storeStaExec`, `storeStdExec`: explicit STA address and STD data execution
  results for the current store-dispatch queue heads.
- `storeMarkCommit*`, `storeCommitFree*`, `storeCommitFreeMask*`: reduced STQ
  commit-mark and committed-row free hooks. The accepted/ignored outputs
  report the corresponding `STQEntryBank` decision.
- `checkpointValid/checkpointBid`, `commitValid/commitBid`, `cleanup`:
  pass-through control for the scalar GPR rename owner and ROB flush path.
- `blockBranchTakenValid`, `blockBranchTaken`: reduced conditional-block
  decision from the execute/top owner. These are consumed only when an active
  conditional marker block reaches its following marker boundary.
- `scalarRedirectValid`: reduced execute-owned redirect pulse used to complete
  and clear active marker context after a scalar return/control row such as
  `FRET.STK`.
- `scalarRedirectStid`: STID sideband for the scalar redirect. It selects the
  active block lane cleared by `BlockMarkerLifecycle`.
- `completeValid/completeRobValue`: reduced ROB completion hook.
- `completeRowValid/completeRow`: optional execute/LSU completion payload
  used when a live owner replaces the allocation/rename placeholder row before
  commit.
- `deallocReady`: reduced ROB deallocation hook.

Outputs:

- `decodedValidMask`, `invalidOpcodeMask`, `blockBoundaryMask`,
  `blockStopMask`, `loadMask`, `storeMask`: decode-stage sidebands.
- `selectedValid`, `selectedSlot`, `selectedRobValue`,
  `selectedBlockBid`: first-valid decoded slot and allocator cursor
  observability.
- `blockMarkerSkipValid`, `blockMarkerMixedPacket`,
  `blockMarkerBoundary`, `blockMarkerStop`, `blockMarkerPc`,
  `blockMarkerInsn`, `blockMarkerLen`, `blockMarkerTarget`: reduced
  marker-consume diagnostics for live fetch RF/ALU gates. These signals are
  meaningful only when the module is constructed with `skipBlockMarkers=true`.
- `blockMarkerAllocReady`, `blockMarkerLifecycleConflict`,
  `blockMarkerAllocFire`, `blockMarkerAllocBid`,
  `blockMarkerActiveValid`, `blockMarkerActiveBid`,
  `blockMarkerActiveTarget`, `blockMarkerStopRedirectValid`,
  `blockMarkerStopRedirectPc`: reduced marker-owned block lifecycle and
  frontend redirect diagnostics. `blockMarkerAllocFire` identifies a consumed
  `BSTART` that allocated a BROB-only entry; `blockMarkerActive*` reports the
  active full BID and target reused by following scalar rows.
  `blockMarkerAllocReady` exposes BROB-only allocation readiness, and
  `blockMarkerLifecycleConflict` exposes the one-cycle conflict guard when
  marker lifecycle and ROB block-last cleanup both want the single scalar-done
  port. A consumed `BSTOP` asserts `blockMarkerStopRedirectValid` when the
  active target is nonzero and non-sequential, with
  `blockMarkerStopRedirectPc` carrying the restart PC.
- `decodeReady`, `decRenPushReady`, `decRenPushFire`, `decRenPopFire`,
  `decRenValid`, `decRenHead`, `decRenTail`, `decRenCount`, `decRenEmpty`,
  `decRenFull`: registered decode-to-rename queue backpressure and occupancy
  observability.
- `gprFreeCount`, `gprMapQFreeCount`: scalar GPR rename capacity diagnostics
  from `ScalarTURenameBridge`/`GPRRenameCheckpoint`. R141 wires these through
  the live top so long CoreMark probes can distinguish physical free-list
  pressure from mapQ pressure.
- `decRenHeadPc`, `decRenHeadRidValid`, `decRenHeadRidValue`: queued-head
  diagnostics used by live CoreMark gates to localize reservation/update
  stalls.
- `robStatusLookup`: current-row status lookup result with row-present,
  need-flush, and invalid/free/stale RID blockers.
- `selectedIsLoad`, `selectedIsStore`, `selectedMemoryValid`,
  `lsidAssignFire`, `selectedLsId`, `selectedLoadId`, `selectedStoreId`,
  `nextLsId`, `nextLoadId`, `nextStoreId`, `storeSplitIntent`: reduced
  memory-order ID and store-split-intent observability from
  `DecodeLoadStoreIdAssign`.
- `renamedOutValid/renamedOut`, `accepted`: renamed-uop output and atomic
  path accept event.
- `storeDispatchReady`, `storeDispatchFire`, `storeDispatchSplit`,
  `storeDispatchBlockedBySta`, `storeDispatchBlockedByStd`, `storeSta`,
  `storeStd`, `storeUnsplit`: reduced store-dispatch observability from the
  accepted renamed row. The payloads include live `tSeq/uSeq` and T/U
  destination sidecars from `ScalarTURenameBridge`.
- `storeStaQueueValid`, `storeStdQueueValid`, `storeStaQueue`,
  `storeStdQueue`, `storeStaEnqueueFire`, `storeStdEnqueueFire`,
  `storeStaDequeueFire`, `storeStdDequeueFire`,
  `storeDispatchInputProtocolError`, `storeStaQueueCount`,
  `storeStdQueueCount`, `storeStaQueueFull`, `storeStdQueueFull`: finite
  STA/STD dispatch queue observability from `StoreDispatchSTQPath`.
- `storeStaInsert*`, `storeStdInsert*`, `storeSelected*`,
  `storeBlockedBy*`, `storeStdBypassStaBlocked`, `storeStqInsert*`,
  `storeMarkCommit*`, `storeCommitFree*`, `storeStqFlush*`,
  `storeStqRows`, `storeStq*Mask`, `storeStq*Count`, `storeStqEmpty`,
  `storeStqFull`, and `storeStqStall`: live STQ path diagnostics and row view
  from the integrated `StoreDispatchSTQPath`. R290 exposes the full
  `storeStqInsert` request payload so top-level MDB diagnostics can consume the
  exact accepted STQ insert identity, PC, address, size, and scalar/tile shape.
- `storeLsuTULinkSource*`: live STQ-bank LSU T/U cleanup source candidate and
  match diagnostics forwarded from `STQEntryBank`.
- `robAllocAttemptValid`, `robAllocReady`, `robAllocFire`,
  `robAllocBlockedBy*`, `robAllocDuplicateIdentity`: allocation handoff
  observability for the decode-time BROB/ROB reservation.
- `robRenameUpdateAttemptValid`, `robRenameUpdateReady`,
  `robRenameUpdateFire`, `robRenameUpdateIgnored`: post-rename ROB row update
  observability for sidecars produced after the queue head accepts.
- `robMarkerRowCompletePending`, `robMarkerRowCompleteFire`: marker-row
  internal completion diagnostics. `Pending` means a renamed marker row was
  consumed internally and is waiting for the single ROB completion port.
  `Fire` means the buffered marker completion was accepted by the ROB bank.
- `blockedBy*`, `blockedByTURename`, `unsupported*`: scalar/T/U rename bridge
  diagnostics.
- `tuRename*`: T/U rename readiness, accepted event, pre-allocation
  `tSeq/uSeq`, destination ownership, pressure, and source-underflow
  observability.
- `tuRenameActivePeId`, `tuRenameActiveStid`,
  `tuRenameActivePeInRange`, `tuRenameActiveStidInRange`,
  `tuRenameActiveBankValid`: active SGPR bank selector and range diagnostics
  driven into `ScalarTURenameBridge`.
- `tuRetireCommandPeId`, `tuRetireCommandStid`, `tuRetirePeInRange`,
  `tuRetireStidInRange`, `tuRetireBankValid`: retired-row SGPR bank selector
  carried by serialized T/U retire commands and observed after
  `ScalarTURenameBridge` forwards it to the bank array.
- `allocBlockBid`, `allocRobValue`, `commit*`, `dealloc*`, `flushApplied`,
  `robTULinkSource*`, `robDeallocTURetireSource`,
  `robDeallocBlockMarkerRetireSource`,
  `robMarkerRetireSource*`,
  `robMarkerRetireSourcePruneCount`,
  `robMarkerRetireSourceLifecycle*`,
  `robDeallocBlockLast*`, `blockScalarDone*`, `blockRetire*`, `size`,
  `outstandingCount`, and occupancy masks: `DispatchROBAllocator`,
  `ROBEntryBank`, and reduced BROB lifecycle observability. R167 routes
  `blockRetire*` through `BlockScalarDoneSequencer`, so retire remains a
  registered next-cycle pulse after the same-cycle `blockScalarDone*` event.
  R168 routes active marker context and marker/scalar/block-last scalar-done
  source selection through `BlockMarkerLifecycle`.
- `tuRetireSource*`, `tuRetireCommand*`, `tuRetireRelation*`,
  `tuRetireAutoCleanBlock*`, `tuRetireLocalBlockCommit*`,
  `tuRetireAccepted/Miss/ReleaseMismatch/Unsupported`,
  `tuRetireCleanupActive`, `tuRetireSourcePruneCount`,
  `tuRetireRelationPruneTCount`, and `tuRetireRelationPruneUCount`: live
  ROB-deallocation to T/U rename retire-command path and cleanup
  observability.
- `tuRetireLocalBlockCommitFanout*`: selected-STID fanout diagnostics for the
  post-clean event, including implemented-STID range, selected-bank readiness,
  and reduced fanout target masks.
- `tuCleanupPublisherFlush*`, `tuCleanupSelectedFlushSource`,
  `tuCleanup*Source*`, `tuCleanupSourceConflict`,
  `tuCleanupSelectedFrom*`, and `tuCleanupFlush*PrevApplied`:
  live T/U cleanup diagnostics for ROB/LSU source selection, missing-source
  blocking, duplicate-source conflict, and selected flush sequence
  publication.

## Logic Design

The path decodes all F4 slots, then selects the lowest-index valid decoded slot
using `PriorityEncoder`. Later slots are not compacted or retried in this
module; a later width owner must provide full decode enqueue.

When block-marker skip is enabled, selection is computed from the non-marker
subset of decoded rows. A marker-only packet is consumed as a frontend marker:
`decodeReady` goes high, marker sidebands are exposed, and the packet advances
without scalar selection, ROB allocation, or decode-to-rename queue mutation.
For a consumed `BSTART`, the path requests a BROB-only allocation from
`DispatchROBAllocator` and stores the returned full BID plus decoded
`boundaryTarget` as the active block.
For a consumed `BSTOP`, the path pulses scalar done for the active full BID
and clears the active state. If the active target is nonzero and differs from
the sequential marker PC, the path also emits a one-cycle
`blockMarkerStopRedirect*` pulse for the reduced frontend source. If a consumed
`BSTART` arrives while another block is active, the old active BID gets scalar
done and the newly allocated BID/target becomes active. A packet that contains
both marker and non-marker slots is deliberately not consumed because
advancing the source PC would otherwise drop a scalar row before dense
multi-slot decode enqueue exists.

For a conditional active block, the next marker boundary waits only when a
branch decision can still arrive: the active target is nonzero and either
`blockBranchTakenValid` is present or scalar ROB work remains pending behind
the marker. A false decision takes fallthrough and allocates the marker's new
BROB-only block. A true decision completes the active block, emits the reduced
redirect to the recorded active target, clears the active marker state, and
suppresses new marker allocation for that boundary. If no producer remains, the
marker falls through rather than deadlocking on a decision that cannot arrive.
Direct and call active blocks do not wait for a branch decision at the next
marker boundary: they complete the active block, redirect to the active target
when it is nonzero, and suppress allocation of the boundary marker itself. This
matches compact `C.BSTART.DIRECT` loop headers such as the CoreMark
`0x00c2 -> 0x0114` case, where the following conditional marker is the
boundary that closes the direct active block.

When execute owns a scalar redirect, the active marker BID is first completed
through `blockScalarDone*`, then active marker state is cleared before the
return target body can allocate. This prevents a target-body scalar-created
block from inheriting the previous marker's target and redirect policy, while
also preventing the old active BROB entry from remaining pending after the
redirect. If a following marker allocation would wrap onto the same active
slot, the path emits a pre-retire scalar-done pulse before allocation.
Marker-only packets therefore drive `decodeReady` from marker lifecycle
readiness, not from scalar decode/rename queue readiness; otherwise a
conditional marker could drain before its branch decision or before BROB
bookkeeping is ready.

The selected decoded row first passes through `DecodeLoadStoreIdAssign`.
Memory-order counters advance only on `decRenPushFire`, so a stalled
decode/rename queue does not consume LSIDs or SID/LID serials. The annotated
row is stamped with the missing backend identity that `DCTop::Work()` and
`SPEROB::allocROB()` supply in the C++ model before it is written into
`DecodeRenameQueue`:

- `bid` comes from the selected full block BID converted to ring `ROBID`;
- `rid.value` comes from the ROB allocation pointer exposed as
  `allocRobValue`;
- `rid.wrap` comes from the ROB allocation pointer wrap exposed as
  `allocRobWrap`;
- `gid` is held at zero in the reduced scalar path;
- `blockBidValid/blockBid` mirrors the selected full hardware BID.

R111 makes that native RID stamping strict. CoreMark's first `OP_SLL` row is
the ninth scalar allocation in the reduced 8-entry top, so its queued RID must
carry `wrap=true`. A queued false-wrap RID names the same slot value but fails
`ROBEntryBank.renameUpdateReady`, leaving the row resident in `dec_ren_q` with
`decodeBlockedByRob` asserted.

When marker lifecycle has an active block, scalar rows instead use that active
full BID for both the row's `bid` sidecar conversion and `blockBid` commit
sideband. The allocator is told to reserve only a ROB row in that case, so the
BROB entry created by `BSTART` remains the single block owner until scalar
done/retire.
R169 also forwards `selectedForQueue.sob`, `selectedForQueue.eob`,
`selectedForQueue.boundaryKind`, and `selectedForQueue.boundaryTarget` into the
allocator's marker sidecar inputs. In the current reduced live path those fields
are mostly inactive because legal marker-only packets are consumed before ROB
reservation; preserving the wiring now fixes the future row image boundary for
dense/full marker-row admission.
If a scalar row allocates while no marker-owned block is active, the reduced
path records that newly allocated full BID as the active block with no marker
target and no conditional redirect state. This covers CoreMark direct-call
targets where the visible QEMU stream starts executing the target body at the
`BSTART` target rather than first retiring a target marker row. The
scalar-created active block is cleared only when the matching ROB block-last
sideband fires or when a later marker boundary/redirect installs or closes the
active marker state.

`DispatchROBAllocator.allocValid` is high when a decoded row exists and the
`dec_ren_q` has push capacity. The row enters the queue only when both
`dec_ren_q.pushReady` and `allocReady` are high, so BROB and ROB reservation
fire atomically before queue visibility. `decodeReady` is the conjunction of
queue capacity and allocator readiness. This matches the C++ model's
`allocROB`-before-`dec_ren_q` order and prevents LSID/SID counters from
advancing when reservation is blocked. The queue and the memory-order counters
are flushed by direct frontend flush input or by backend cleanup intent. The
generated opcode metadata drives the reduced path's load/store-pair,
PCR-store, and cache-maintain split sidebands. DCZVA remains a deferred
explicit classification input.

The reservation row deliberately carries zero T/U sequence and no T/U
destination sidecar. `ScalarTURenameBridge.robAllocAttemptValid` now drives
`DispatchROBAllocator.renameUpdateValid` for the queue head. The rename bridge
only reports `accepted`, pops `DecodeRenameQueue`, and mutates GPR plus T/U
rename state when the ROB bank reports `renameUpdateReady`. On acceptance, the
allocator forwards the bridge's post-rename `CommitTraceRow`, `tSeq/uSeq`, and
T/U destination ownership into `ROBEntryBank.renameUpdate*`. This is the
explicit Chisel value-row replacement for the C++ model's shared `SimInst`
pointer mutation after `SPEROB::allocROB`.

For a queued marker row, rename acceptance is an internal command-path
boundary. The row still performs the same ROB rename update as an ordinary
decoded row, but `DecodeRenameROBPath` masks the renamed marker from
`renamedOutValid` and from `StoreSplitPayload` so the reduced scalar ALU never
sees unsupported `BSTART`/`BSTOP` opcodes. The accepted marker row is buffered
as a one-entry internal completion using the rename bridge's
`robAllocRow`; the buffered completion waits whenever the external
`completeValid` port is in use and is cleared by decode/backend lifecycle
flushes. The module IO `completeAccepted/completeIgnored` remains scoped to
external completions, while `robMarkerRowComplete*` observes the internal
marker completion path.

For store rows at the queue head, the path computes reduced store-dispatch
readiness before rename accepts the row. Unsplit stores require only STA
readiness; split stores require both STA and STD readiness so the model STA/STD
pair cannot partially fire. This readiness is computed from the queued decoded
row, not from `StoreSplitPayload.inReady`, avoiding a combinational loop
through the accepted renamed output. `StoreSplitPayload` then consumes the
accepted renamed row and emits the observed STA, STD, or ST_ALL payloads.
R62 drives the explicit T/U local-register sidecar inputs on
`StoreSplitPayload` from `ScalarTURenameBridge`. `tSeq/uSeq` are the
pre-allocation snapshots captured before T/U destination rename, and
`tuDst*` identifies whether the accepted row owns a T or U destination.

`StoreDispatchSTQPath` consumes those payloads and owns finite STA/STD FIFO
admission plus the first live STQ row mutation boundary in the reduced backend.
Its queue readiness is capacity-only and flush-qualified; it does not depend on
the payload valid bits coming back from `StoreSplitPayload`. Split stores
enqueue both STA and STD payloads atomically, unsplit stores enqueue only a
STA-side `ST_ALL` payload, and protocol-shape errors are exposed as diagnostics
that block enqueue. Queue heads leave the queue only when the corresponding
explicit execution result is valid and the live STQ insert probe accepts that
candidate. A backend recovery flush is passed to the STQ bank; direct frontend
or decode maintenance flush uses `StoreDispatchSTQPath.queueFlushValid` so it
clears only the dispatch queues and does not over-prune STQ rows.
When `reducedStoreDispatchBypass=true`, only the reduced live RF/ALU top skips
this store-dispatch residency. Rename still accepts store rows, and
`StoreSplitPayload` still reports the observed split shape, but all three
payload inputs into `StoreDispatchSTQPath` are forced invalid and
store-dispatch backpressure is suppressed. This is a bring-up escape hatch for
tops whose ALU execute path already emits the QEMU-shaped store sideband while
the real STA/STD execution and STQ lifecycle owners are not connected. R240
exports `storeStqRows` plus mark/free accepted diagnostics so the reduced top
can map committed store rows back to resident STQ rows. Do not enable the
bypass in full backend or LSU integration paths.

The allocator still owns BID generation, BROB metadata allocation, ROB row
reservation/update forwarding, completion, deallocation, commit monitoring,
and ROB flush pruning.
The composition drives BROB scalar completion from two reduced sources:
marker-owned active block lifecycle and the ROB block-last sideband. If both
would fire in the same cycle, marker consumption stalls for one cycle so the
single BROB scalar-done input does not drop an event. A scalar-done pulse is
followed by a one-cycle-later BROB retire pulse for the same full BID.
Block-engine completion remains inactive because full block-control execution
is not part of this owner.

The composition also drives the allocator's row-owned sidecars. `allocStid`
comes from the decoded row's thread ID in the reduced path; `allocTSeq`,
`allocUSeq`, and T/U destination ownership are patched later by the rename
update. This gives the ROB row the same sequence snapshot that the
store-dispatch payload carries without requiring the reservation stage to know
rename-produced values.
R73 uses that same queued-row thread ID as `ScalarTURenameBridge.activeStid`.
R75 uses the queued-row `peId` as `ScalarTURenameBridge.activePeId`, so the
active T/U bank selector is now fully row-owned at the reduced backend
boundary. The emitted `tuRenameActive*` diagnostics prove whether the selected
bank exists before T/U rename state can mutate.
R74/R75 also forward row-owned `allocPeId` into the ROB row image. That value
is stored and later re-emitted as `robDeallocTURetireSource(*).peId` so the
retire-command path uses row-owned bank identity instead of the current active
selector.
R63 also drives `allocGid` from the queued row's native `gid` and
`allocIsLast` from `eob`, then forwards the allocator's
`deallocTURetireSource` vector to module IO. R64 feeds that vector into
`TULinkRetireCommandPath`. The path gates ROB deallocation with serializer
credit for a full commit-width source window and blocks new ROB deallocation
while T/U retire cleanup is active. The serializer preserves valid ROB rows in
slot order, including no-destination block-last rows, feeds
`TULinkRelationCmap`, and drives `ScalarTURenameBridge.tuRetire*` from the
resulting command. It advances relation commands only when
`ScalarTURenameBridge.tuRetireAccepted` proves the live T/U rename owner
accepted the mark or deallocation after flush and commit priority. R65 wires
`cleanup.flush` into the serializer and embedded relation-cmap owner. Recovery
flush prunes only a newest suffix of matching queued sources and relation
entries. R66 forwards the ROB bank's block-last deallocation candidate. R67
keeps the external `cleanBlock*` inputs inactive in this reduced owner, but the
retire-command path now latches an accepted block-last source, blocks new ROB
deallocation-source admission, waits until the generated mark/release command
stream is accepted, and then pulses an internal scalar `cleanBlock` cleanup for
that BID. The next cycle publishes a local block-commit event for the same BID
and keeps ROB deallocation-source admission blocked until it fires. R69 drives
the event ready input from `ScalarTURenameBridge.tuLocalBlockCommitReady`,
feeds the event into the T/U cleanup composition, and exposes both the retire
path fire and rename-side accepted diagnostics. The reduced path therefore
mutates the single live T/U local-register bank after post-clean event
acceptance, while full dynamic PE ownership and multi-STID top integration
remain deferred. R70 carries the
block-last row's STID on that event and exposes
`tuRetireLocalBlockCommitStidMatch` plus
`tuRetireLocalBlockCommitBlockedByStid`; the current reduced bank accepts only
local STID0. R72 forwards the pending BID/STID event directly to
`ScalarTURenameBridge`; inside the bridge, `TULinkLocalBankArray` receives the
event, checks that the selected STID exists, waits for every selected PE bank
group to be ready, and pulses child bank valid only on fanout acceptance. With
`peCount=1` and `stidCount=1`, this preserves current behavior while placing
the future all-PE selected-STID handshake in the SGPR bank hierarchy.
R74 routes relation-cmap retire commands through command PE/STID sidecars:
`TULinkRetireCommandPath.command.peId/stid` drive
`ScalarTURenameBridge.tuRetirePeId/Stid`, and `TULinkLocalBankArray` selects
the retire target from those sidecars rather than from `tuRenameActive*`.
`cleanGroup*` remains inactive until a vector/MTC group-clean owner exists.
R169 forwards `robDeallocBlockMarkerRetireSource` beside the T/U and block-last
deallocation outputs. The vector carries marker-row metadata at the same
deallocation timing point as `SPEROB::dealloc()` observes `uop.last` and marker
instructions before calling block handlers.
R171 feeds that vector into `BlockMarkerRetireSourceSerializer`. The allocator
accepts a ROB deallocation window only when both the existing T/U retire-source
path and the marker serializer have room for a full commit-width window, so a
cycle that observes marker retire sources cannot lose lanes to queue
backpressure.
R172 feeds the serialized `robMarkerRetireSource` output into
`BlockMarkerLifecycle.retiredMarker`, and serializer dequeue readiness is now
`BlockMarkerLifecycle.retiredMarkerReady`. Retired marker lifecycle mutation is
kept distinct from the reduced decode-time marker-skip inputs: decode-time
markers can still allocate BROB-only entries for the bring-up lane, while
retired marker rows consume the full block BID already carried in the ROB row
image. A retired fallthrough boundary without `blockBidValid` is backpressured
at this boundary instead of being silently consumed. `robMarkerRetireSource*`
keeps queue/source diagnostics, while `robMarkerRetireSourceLifecycle*` reports
the lifecycle consumer ready/fire/boundary/stop pulses.
R173 passes `cleanup.flush` into `BlockMarkerRetireSourceSerializer` and keeps
the serializer's hard `clear` inactive in this composition. Recovery cleanup
therefore blocks marker-source enqueue/dequeue for the cycle, prunes only the
newest flushed suffix, compacts surviving sources, and reports the removed
count as `robMarkerRetireSourcePruneCount`.
R174 keeps the serializer-to-lifecycle retired marker path unchanged but makes
the active-state owner STID-indexed. `DecodeRenameROBPath` now drives
`BlockMarkerLifecycle.markerStid` from the marker-only row, drives
`activeQueryStid` and `scalarBlockStartStid` from the selected scalar row, and
adds `scalarRedirectStid` to the backend IO so live execute redirects clear the
matching STID lane instead of treating active block context as global.
R175 keeps decode-time marker-skip lifecycle and retired-marker lifecycle
separate. The new marker-row completion shell prevents admitted marker rows
from deadlocking in the reduced scalar ALU, but it intentionally does not use
retired marker rows as the live active-BID source yet. A full live switch must
split active-BID assignment for following scalar decode from retire-time
completion/redirect semantics, or otherwise prove that one combined lifecycle
cannot double-close the same block.

R103 uses the ROB bank's full block-last BID to drive the reduced BROB
completion path. On a deallocation cycle that frees a block-last row,
`blockScalarDoneFire` pulses with `robDeallocBlockLastBlockBid`. A one-entry
registered pending bit then drives `blockRetireFire` for the same full BID on
the following cycle. This preserves the model split where `SPEROB::dealloc`
calls `CommitLast`/`CommitBlock`, `PEBase::SetBlockComplete` marks the block
complete, and `BlockROB::commitBlock` later retires completed block entries.
At R103 the reduced marker-skip path still did not allocate or retire
`BSTART`/`BSTOP` marker rows; the next marker lifecycle owner had to connect
marker retire to the old/current active block BID rule.

R104 connects that reduced marker lifecycle for the live fetch RF/ALU lane.
`BCtrlUnit::RunFetchStage5` allocates a BROB entry for a block-start marker,
keeps the resulting BID as the current block command identity, stamps following
scalar instructions from that current BID, and treats `BSTOP` as the current
block's terminal marker rather than a new BROB allocation. The Chisel reduced
path mirrors this at marker-consume granularity: `BSTART` allocates BROB-only,
scalar rows reuse the active BID, `BSTOP` completes the current active BID,
and `BSTART` while active completes the old BID before installing the new one.
At R104 this was not a full marker ROB-retire implementation, and the current
path still skipped marker rows instead of admitting them as ordinary ROB rows.

R117 feeds reduced marker/block retire back into scalar rename commit
bookkeeping. When the marker-owned block-retire pending bit is set, the path
converts the retiring full BID through `FullBidRecoveryBridge.fullBidToRobId`
and ORs that event into `ScalarTURenameBridge.commitValid`. This releases
scalar GPR mapQ entries at marker-owned block boundaries even when there is no
external scalar commit pulse for that internal reduced block-retire event.
R117 also treats every terminal T/U retire-command response as progress:
`accepted`, `miss`, `releaseMismatch`, and `unsupported` all drive
`TULinkRetireCommandPath.commandReady`. The diagnostics remain visible on the
module IO, but a stale relation command can no longer hold the retire
serializer and block later local C.LDI/C.SETC packet work forever.

The composition forwards `DispatchROBAllocator.robTULinkSource*` to the module
IO and feeds the same source into `ScalarTURenameBridge.robSource`. The bridge
feeds `StoreDispatchSTQPath.lsuTULinkSource` into its LSU source input. For
non-base cleanup intents, the emitted diagnostics prove whether the ROB and
LSU candidates agree for the same `(bid,rid,stid)`, whether exactly one owner
supplied a matching source, or whether cleanup was blocked by a missing or
conflicting source. Unlike the earlier diagnostic-only path, the selected
publisher output now drives the live T/U rename cleanup path inside the same
composition owner.

## Model Alignment

The C++ model order being preserved is:

1. `DCTop::Work()` selects one instruction, assigns `rid` from the PE ROB
   allocation pointer, calls `SPEROB::allocROB()`, assigns LSID/SID where
   applicable, then writes the instruction to `dec_ren_q`.
2. `SPEROB::allocROB()` stores an allocated row, copies `bid/gid/rid`, and
   advances `allocPtr`, `size`, and `osdSize`.
3. `Decoder::DecodeInst()` assigns the current `lsID` snapshot to every row,
   and `DCTop::SetLoadStoreID()` advances load/store serial counters for memory
   rows. The Chisel reduced path now matches that pre-increment `lsID`
   snapshot for one accepted STID0 decoded row, including non-memory rows used
   as ROB/LDQ retire watermarks.
4. `SPE::Xfer()` calls `dec_ren_q.Work()` to move ready written rows into the
   rename-visible queue image.
5. `SPERename::Rename()` consumes `dec_ren_q`, maps scalar and T/U sources,
   snapshots `inst->tSeq/uSeq` before T/U destination rename, allocates
   scalar and T/U destinations, captures checkpoints for `isLastInBlock`, and
   forwards the renamed uop toward dispatch.
   `SPERename::InsertToSIEXQ()` sends `InstGroup::BLOCK_SPLIT` rows to the
   command IEX queue through `InsertToCmdIEX`; the reduced Chisel path models
   this first command boundary by internally consuming and completing marker
   rows after rename instead of sending them to scalar ALU execute.
6. `GPRRename` owns scalar `smap`, `cmap`, checkpoints, free tags, and mapQ;
   T/U `LocalRegMgr` instances own local link sequence, offset lookup,
   allocation pressure, and cleanup pruning.
7. Store-unit deadlock cleanup builders preserve row-owned T/U sequence and
   destination sidecars and must agree with ROB-owned source evidence for the
   same selected `(bid,rid,stid)`.
8. `SPERename::InsertToStoreIEX()` emits either an atomic STA/STD pair or a
   single ST_ALL store payload behind rename, with PCR-source and pair/cache
   suppression semantics.
9. `SPEROB::dealloc()` walks retired ROB rows in order and calls
   `ReleaseRelative()` before freeing the row image; that relation path marks
   T/U local mapQ entries retired and releases oldest relations on block-last,
   group change, or pressure.
10. The same deallocation walk skips `MinstPipeView` for `OP_BSTOP` and calls
    block handlers from the retired row image. Chisel now preserves marker row
    metadata as a width-wide deallocation source, serializes it, and feeds the
    lifecycle owner with row-owned block BID evidence. The reduced live path
    still consumes marker-only packets before ROB admission until full marker
    row enqueue replaces the skip path.

`DecodeRenameROBPath` now preserves the registered `dec_ren_q` timing point,
assigns reduced memory-order identity before queue enqueue, and reserves
BROB/ROB identity before the row enters the queue. Because Chisel stores value
rows rather than the C++ model's shared `SimInst` pointer, rename-produced
`tSeq/uSeq` and T/U destination ownership are patched through
`ROBEntryBank.renameUpdate*` when the queue head accepts. Full model timing
still requires carrying full `load_id`/`sid` payloads into LIQ/STQ owners. It
exposes the ROB T/U source candidate through the allocator boundary and
composes the live STQ-bank LSU candidate through `ScalarTURenameBridge` and
its `TULinkRecoveryCleanupPath`. R62 wires live
`SPERename::Rename()`-equivalent `tSeq/uSeq` snapshots and T/U destination
ownership into `StoreSplitPayload`; R76 wires the same values into
`DispatchROBAllocator` through the post-rename row update path.
R64 wires the deallocation side of that same T/U ownership contract back into
live T/U rename retirement through the width-aware source serializer. R65 adds
the recovery-pruning side of `SPEROB::FlushRelativeReg` to that path.
R66 adds the deallocation-window stop at block-last, and R67 schedules the
scalar `CleanCMAP` cleanup after that block-last source's relation-cmap command
stream has drained. R68 preserves the next model call boundary,
`SPEROB::ReportLocalRegBlockCommit`, as a ready/valid event after the scalar
`CleanCMAP` pulse. R69 consumes that event through the reduced live
T/U local-register owner, matching `SPERename::ReportSGPRBlockCommit` for the
single implemented bank. R70 carries the selected STID with that event,
matching the model's `ReportSGPRBlockCommit(bid, stid)` selection while the
current Chisel owner remains a reduced STID0 bank.
R71 adds the fanout boundary that corresponds to the model loop over scalar PE
SGPR banks for the selected STID. R72 wraps the T/U local-register owner in an
explicit `TULinkLocalBankArray`, matching the model
`sgprRenameUnit[pe][stid][hand]` shape structurally while keeping the live
reduced lane selected at PE0/STID0. Broader multi-PE/multi-STID top integration
remains deferred, but the local block-commit event is no longer a
backend-local fanout. R73 moves
the reduced active-STID selector from a bridge-local constant to the queued row
sidecar, matching the model's `SPERename::Rename()` use of `inst->stid` for
the implemented single-PE lane. R75 moves the active scalar PE selector from a
backend constant to the queued row sidecar as well, matching
`SPERename::Rename()` indexing `sgprRenameUnit[inst->peID][inst->stid]`.
R74 matches the retire side of the same model by carrying `inst->peID/stid`
from the deallocated ROB row and `RelateInfo.peid` plus STID from relation
entries into local retire commands, matching `SPERename::RepLocalRetired` and
`SPEROB::ReleaseFunc` bank arguments.
Full store timing still requires real STA/STD execution, load-conflict
publication, SCB/MDB handoff, and memory trace side effects.

## Deferred Owners

- Width-wide selection and rename for multiple decoded uops per cycle.
- BID-scoped `dec_ren_q` flush pruning rather than coarse queue clear.
- Top-level frontend backpressure wiring that consumes `decodeReady`.
- Width-wide slot-order LSID/SID allocation and same-cycle memory ordering.
- Full `load_id`/`sid` payload carry into LIQ/STQ owners.
- Real STA/STD execution owners that drive `storeStaExec` and `storeStdExec`.
- Exact automatic checkpoint capture from validated `isLastInBlock`; the
  reduced in-order path currently refreshes scalar GPR checkpoints after each
  accepted row so block-stop recovery restores the latest map for that block.
- External live block/group commit clean event wiring into
  `TULinkRetireCommandPath.cleanBlock*` and `cleanGroup*`; scalar block-last
  auto clean is now owned inside `TULinkRetireCommandPath`.
- Full live scalar commit ownership for real marker ROB-retire rows. The
  current R117 feedback only bridges reduced marker/block-retire events into
  scalar rename mapQ release; R172 feeds serialized marker retire sources into
  lifecycle using the row-owned block BID; R173 prunes queued marker sources on
  recovery cleanup; R174 makes the active context STID-indexed; and R175 lets
  admitted marker rows complete internally. The remaining full-marker work is
  the two-phase active-BID lifecycle and live-top removal of
  `skipBlockMarkers=true`.
- Multi-PE `TULinkLocalBankArray` instantiation and top-level nonzero PE
  packet production. The active selector now consumes row `peId`, but current
  frontend/top packets still default that sidecar to PE0 unless an upstream
  owner drives it.
- Defaulting the live path to `BlockMarkerDecodeContext`. R177 wires the
  decode-side owner into `DecodeRenameROBPath` behind
  `useMarkerDecodeContext=true`, but the live top still instantiates the
  default reduced `BlockMarkerLifecycle` BID source while the harness treats
  legal markers as skip rows.
- Ready-table mutation and physical tag wakeup/release side effects for
  relation cleanup entries.
- SGPR/tile/vector operand classification and rename.
- Old T/U physical tag release accounting for destination overwrite.
- SCB/MDB integration, committed-store memory drain, and load-conflict
  publication behind accepted STQ inserts.
- Ready-table initialization, issue enqueue, execution completion, and full
  commit side effects.
- Full QEMU-vs-DUT compare with live architectural commit rows.
- A Verilator driver for `LinxCoreFrontendTraceTop`; the wrapper now emits a
  raw frontend-window to commit-row top boundary, but the next harness must
  still drive windows, completion surrogates, and JSONL dumping.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only BlockMarkerLifecycle
bash tools/chisel/run_chisel_tests.sh --only BlockMarkerDecodeContextSpec
bash tools/chisel/run_chisel_tests.sh --only BlockMarkerRetireSourceSerializer
bash tools/chisel/run_chisel_tests.sh --only BlockScalarDoneSequencer
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only DecodeLoadStoreIdAssign
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
bash tools/chisel/run_chisel_tests.sh --only StoreSplitPayload
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBankArray
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r111-coremark-sll-tu-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 14 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r119-coremark-cond-bstart-50-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 50 --allow-block-markers --max-seconds 8 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r172-retired-marker-lifecycle-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r173-marker-retire-source-flush-prune-6000-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 6000 --allow-block-markers --allow-block-loop-reentry --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r192-marker-row-brob-retire-drain-128-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 128 --allow-block-markers --allow-block-loop-reentry --marker-rows --max-seconds 16 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
```

Affected gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendTraceTop
bash tools/chisel/run_chisel_frontend_trace_top_lint.sh
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The current tests cover first-valid slot selection, queue admission
backpressure, the reduced memory-order ID observability, the store dispatch
STA/STD readiness rule, decode-time BROB/ROB reservation gating, post-rename
ROB update observability, ROB/LSU T/U cleanup source agreement and conflict
reference behavior, ROB dealloc T/U retire
serializer diagnostics, IO shape, and CIRCT elaboration through frontend
decode, `DecodeLoadStoreIdAssign`,
`DecodeRenameQueue`, scalar/T/U rename, `StoreSplitPayload`,
`StoreDispatchSTQPath` with `STQEntryBank`, backend allocation, and
`TULinkLocalBankArray` with per-bank `TULinkRecoveryCleanupPath` children.
R65 also covers retire-source and relation-cmap cleanup observability through
the composition boundary. R66 covers the
block-last deallocation boundary and forwarded block-clean candidate. R68
covers local block-commit observability after auto scalar clean. R69 covers the
consumer handshake from retire event to live T/U local-register block commit.
R70 covers event STID carry and the reduced owner's non-local STID rejection
diagnostic.
R71 covers the selected-STID fanout boundary and its reduced backend
observability. R72 covers the explicit local bank-array hierarchy and the
bridge-owned fanout observability through this backend path. R73 covers the
queued-row STID selector plumbing and active-bank diagnostics. R74 covers
retired-row PE/STID command sidecars and retire-bank diagnostics through the
backend composition. R75 covers queued-row PE sidecar carry into active rename,
ROB allocation, renamed output, and store-dispatch payload observability.
R76 covers enqueue-time ROB/BROB reservation and post-rename sidecar update
observability through `DecodeRenameROBPath`, `DispatchROBAllocator`, and
`ROBEntryBank`.
R103 covers full block-BID propagation from ROB deallocation, reduced
`blockScalarDone*` and one-cycle-later `blockRetire*` diagnostics, and
stale same-slot BROB event rejection.
R167 covers the extracted `BlockScalarDoneSequencer` boundary that preserves the
same same-cycle scalar-done and next-cycle retire/free timing for those events.
R168 covers the extracted `BlockMarkerLifecycle` owner for active full-BID
context, marker readiness, direct/conditional boundary handling, same-slot
pre-retire, scalar redirect cleanup, scalar-created active blocks, and
block-last closure.
R169 covers marker sidecar forwarding into ROB reservation and
`robDeallocBlockMarkerRetireSource` IO elaboration through the backend
composition.
R171 covers `BlockMarkerRetireSourceSerializer` integration in the backend
deallocation handshake plus `robMarkerRetireSource*` IO elaboration.
R172 covers the retired-marker lifecycle consumer in `BlockMarkerLifecycle`,
backend serializer `outReady` wiring, `robMarkerRetireSourceLifecycle*`
diagnostics, row-owned retired boundary BID use, and malformed retired boundary
backpressure.
R173 covers marker retire-source recovery pruning through the backend cleanup
flush, `robMarkerRetireSourcePruneCount` IO elaboration, and live top
elaboration with the new serializer flush input.
R174 covers STID-indexed `BlockMarkerLifecycle` wiring through
`DecodeRenameROBPath`, including marker STID, scalar active-query STID,
scalar-created block-start STID, and execute-owned `scalarRedirectStid` IO.
The R174 live CoreMark gate
`generated/r174-stid-marker-lifecycle-6000-qemu-elf-xcheck` captured 6000 QEMU
rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows, compared
5137 rows, and passed with zero mismatches and no CBSTOP divergence.
R175 covers the marker-row ROB-admission shell through
`DecodeRenameROBPathSpec`: marker rows accepted after rename are internally
consumed, hidden from scalar issue and store split, buffered until the external
completion port is idle, and then completed through the ROB bank. The live
CoreMark top is still intentionally verified in marker-skip mode until the
full marker lifecycle split is implemented.
R176 adds `BlockMarkerDecodeContext` as the decode-time half of that lifecycle
split. It proves, in a standalone Chisel owner, that a decoded boundary marker
receives the allocator's new full BID even while a prior active context exists,
following scalar rows reuse that new BID, decoded stops reuse and clear the
active BID, and flush/redirect/ROB block-last cleanup remains STID-scoped.
R177 wires that owner into `DecodeRenameROBPath` behind
`useMarkerDecodeContext=true`. The candidate/fire split is intentional:
`decodeValid` produces the selected block-BID and `allocUsesExistingBlock`
decision before allocator acceptance, while `decodeFire` mutates active context
only after `allocator.io.allocFire`.
The R177 live CoreMark regression
`generated/r177-marker-decode-context-optin-6000-qemu-elf-xcheck` kept the
default live top in skip mode and passed with the same 5137 compared rows, zero
mismatches, and no CBSTOP divergence. The manifest again recorded dirty QEMU
because of unrelated local `target/linx/cpu.c` changes.
The R176 live CoreMark regression
`generated/r176-marker-decode-context-prep-6000-qemu-elf-xcheck` preserved the
current skip-mode live behavior: 6000 raw QEMU rows, 5866 expected rows, 5138
normalized QEMU/DUT rows, 5137 compared rows, zero mismatches, and no CBSTOP
divergence. Its manifest recorded dirty QEMU due to unrelated local
`target/linx/cpu.c` changes, so use the run as a regression on this Chisel
packet rather than as clean-QEMU release evidence.
The R175 live CoreMark regression
`generated/r175-marker-row-completion-shell-6000-qemu-elf-xcheck` captured
6000 QEMU rows, produced 5866 expected rows, normalized 5138 QEMU/DUT rows,
compared 5137 rows, and passed with zero mismatches and no CBSTOP divergence.
The manifest recorded dirty LinxCore
`50ea2b10c648435608d07d64e9928b71cc2c9b52` for the uncommitted packet, clean
LinxCoreModel `3c0878da3aa1e06669b718e93269f094e7244066`, clean QEMU
`08783bb4572df9c5f6623bed0d468641befab762`, and dirty superproject
`8d7582a21800cccd3b6461f0be8376c3228a3fc5`.
R104 covers marker-only BROB allocation, active full-BID reuse by scalar rows,
marker-driven scalar-done/retire for old/current active blocks, marker
conflict gating against ROB block-last scalar-done, and top-level marker
lifecycle IO elaboration.
R126 covers scalar redirect clearing of stale marker target state before the
return target block seeds a new active scalar-created block.
