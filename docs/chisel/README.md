# LinxCore Chisel RTL Lane

This directory is the authoring home for the Chisel replacement lane. The lane
is developed beside the current pyCircuit implementation until Chisel reaches
equivalent module, trace, QEMU, and LinxCoreModel evidence.

Current phase:

- Phase 0: build skeleton
- Phase 0A: model notes
- Phase 0B: ROB and cross-check infrastructure first
- Phase 1: interface schema and type-system monitors in progress
- Phase 2: frontend F4 decode-window, instruction-buffer, and decode-ingress
  slicing started
- Phase 5 preparation: integrated ROB/CMT status vocabulary, entry-bank
  skeleton, flush-prune selector, entry-bank flush application, and native row
  BID/RID sidecars started
- Backend/recovery integration: dispatch/BROB-to-ROB allocation bridge,
  full-BID recovery handoff, and registered cleanup intent started
- LSU recovery and drain integration: first STQ flush-prune consumer, state
  bank, store-commit queue, and memory-side commit drain boundary started
- Phase 1 top shell: `LinxCoreTop` wraps the monitored reduced ROB so top
  emit/lint uses real commit structure before the full frontend/backend exists

The first implementation packets are ROBID, commit identity, the initial
FlushControl arbitration primitive, BROB/BID metadata, and the shared Phase 1
common interface bundles. They are derived from
`model/LinxCoreModel/model/ModelCommon/ROBID.*`,
`model/LinxCoreModel/model/interface/CommitInfo.h`, and
`model/LinxCoreModel/model/core/FlushControl.*`,
`model/LinxCoreModel/model/bctrl/BROB.*`, and the C++ model bus headers under
`model/LinxCoreModel/model/ModelCommon/bus/`. The first Phase 2 frontend slice
also follows the LinxCoreModel `CheckMInstSize` instruction-length rule from
`model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h` and the F4/F5/instBuffer
queueing flow in `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`. The
current frontend transport slice composes the instruction buffer with F4
visibility. `FrontendDecodeStage` now consumes those F4 slots and uses the
pyCircuit opcode catalog mask/match metadata to produce D1 `DecodedUop`
records plus block/load/store sideband masks and generated store split
metadata for load/store pairs, PCR stores, and cache-maintain rows.
`FrontendOperandDecode` is the first scalar operand owner behind that stage: it
consumes generated `rdKind`/`rs1Kind`/`rs2Kind`/`immKind` metadata, extracts
architectural source/destination tags, classifies scalar reg6 aliases as
GPR/T/U according to LinxCoreModel, and forms common scalar immediates. LSID
allocation, STA/STD execution and STQ mutation, T/U rename or queue consumption,
SGPR/tile/vector operands, D2 width expansion, and full model-like ROB
reservation before queue enqueue remain later owners.
`ScalarDecodeRenameBridge` now adds
the first one-uop D2 decode-to-rename staging owner: it composes scalar
`GPRRenameCheckpoint`, emits a `RenamedUop`, and produces a ROB allocation row
request while rejecting reg6 aliases outside the 24-entry scalar GPR owner.
`DecodeRenameROBPath` now adds the first reduced composition of frontend
decode, a registered `DecodeRenameQueue`, scalar rename, and real ROB/BROB
allocation. It selects one decoded slot, queues the raw decoded row, stamps
reduced memory-order identity when the row is accepted into the queue, stamps
temporary backend identity from allocator cursors at the queue head, drives
allocator valid from a pre-ready bridge attempt signal, gates accepted store
rows on capacity-only store-dispatch readiness, feeds `StoreSplitPayload`
STA/STD or ST_ALL payloads into `StoreDispatchQueues`, and exposes finite
STA/STD queue heads. `StoreDispatchToSTQ` now maps those heads plus explicit
execution results into typed `STQStoreRequest` rows, preserving STA priority
and STD merge-bypass progress without computing address/data itself.
`STQInsertProbe` now factors the live-row insert readiness predicate used by
`STQEntryBank`, and `StoreDispatchSTQPath` composes the queues, bridge,
per-candidate probes, and STQ bank so a mergeable STD can bypass an
allocation-blocked STA. `ReducedStoreExecResultBridge` is the reduced-top
adapter from ALU store completion sidebands to matched STA/STD execution
results; STQ commit/free ownership is still a later owner before the path can
run long CoreMark windows. The lane still keeps enqueue-time ROB reservation,
full SID/LID payload carry, ready-table, and full live top integration in
later owners. The first integrated ROB/CMT
preparation slices preserve the LinxCoreModel `PROBStatus` lifecycle, add a
status-backed entry bank with separate commit and deallocation walks, and expose
the model-derived flush-prune selection rule. The entry bank now consumes that
selector to clear pruned rows, update resident/outstanding counts, rebase local
ROB pointers, and compare flushes against native row BID/RID sidecars before
the reduced ROB harness grows into a full ROB. The first backend integration
bridge now generates a full hardware BID, allocates BROB metadata and ROB row
state atomically, and forwards that BID into `ROBEntryBank.allocBid`.
`FullBidRecoveryBridge` defines the matching recovery handoff: full block BID
for BROB-style cleanup, ring `ROBID` for ROB row pruning.
`RecoveryCleanupControl` registers the selected recovery request and exposes
the first cleanup-intent hooks for BCTRL, rename, backend, frontend, LSU/STQ,
tile, PE fanout, and ROB consumers. `GPRRenameCheckpoint` is the first scalar
rename cleanup consumer: it owns model `GPRRename` `smap`/`cmap`, per-BID
checkpoints, free physical GPR mask, and mapQ pruning for STID0, while leaving
ClockHands/T/U and multi-thread rename to later owners. `STQFlushPrune` is the
first concrete LSU/STQ consumer: it mirrors the model `FlushBus::match`
predicate and emits free masks for valid `STQ_WAIT` rows. `STQEntryBank` is
the first STQ state owner that consumes those masks, stores row sidecars,
performs first-free allocation, merges split store address/data halves, and
keeps resident plus WAIT/outstanding counts. `STQCommitQueue` is the first store-commit ordering
owner: it keeps committed row indices sorted by `(bid, lsId)` and selects
downstream-ready rows for future memory-side owners. `STQCommitDrain` composes
that queue with committed row sidecars, models scalar cacheline split request
shaping, and drives `STQEntryBank` committed-row free masks only after
abstract memory-side segment acceptance. `SCBCommitIngress` is the first
store-coalescing owner after that drain: it allocates 64-byte SCB line entries,
merges same-line store fragments, and publishes post-merge byte-valid wakeup
masks while leaving DCache eviction, CHI completion, MDB, and forwarding policy
to later packets. `SCBCommitBridge` composes that ingress owner with the model
SCB batch gate, stalls commit descriptors when SCB free entries are below the
commit width, and returns committed-row free masks only after accepted
last-fragment admission. `SCBEntryState` and `SCBEgressSelect` add the first
SCB egress-selection owner: only valid rows can issue lookup descriptors, full
valid rows win over partial rows, and the model's random not-full fallback is
made deterministic for repeatable RTL and cross-check evidence.
`SCBLookupControl` consumes that descriptor and owns the next abstract
DCache/L2 split: writable DCache hits produce byte-update/free intent, while
non-writable lookups emit L2 write or upgrade ownership requests using the
model transaction tag encoding. `SCBStateUpdate` consumes the lookup outcome
masks plus a decoded memory-response row id and computes the next SCB row
image for `Valid -> Lookup`, hit free, miss state, and response-driven
`Miss -> Lookup` transitions. `SCBRowBank` is the first registered composition
owner around those contracts: it owns one row image, applies model-batch
ingress admission, prevents same-line merge into `Lookup` or `Miss` rows, and
registers the egress/lookup/state-update result consumed by the STQ-to-SCB
commit path and later LSU wiring. `SCBResponseDecode` now owns the raw
WriteResp/UpgradeResp tag boundary: model transaction ids encoded as
`(entryIndex << 2) | 2` are range-checked and accepted only for valid `Miss`
rows before they drive `SCBStateUpdate`. `SCBResponseBuffer` adds the
registered raw-response FIFO in front of that decoder, preserving FIFO order,
backpressure, and illegal/stale-head visibility for later L2/CHI wiring.
`SCBResponseRetrySelect` owns the next model `resp_list` priority point:
response-returned `Lookup` rows retry before ordinary `Valid` row eviction.
`SCBResponseRetryQueue` now stores decoded response row ids in model
`resp_list` order, while `SCBEgressSelect` remains the normal valid-row
selector.
`STQSCBCommitPath` is the first full STQ-to-SCB composition owner: it wires
`STQEntryBank`, `STQCommitDrain`, and `SCBRowBank` so SCB accepted `last`
fragments are the only committed-row free source back into the STQ bank, while
the earlier drain free mask remains debug-only observability.
`MDBConflictDetect` starts the load/store conflict path after STQ insertion:
it classifies scalar store-arrival probes against active LDQ rows and
`ResolveQ`, suppresses current tile conflicts, emits `ST_ADDR` wait-store
masks for unresolved loads, and selects the oldest resolved conflicting load
for later MDB learning plus inner/nuke recovery publication.
`MDBSSIT` owns the MDB Store Set ID Table state behind that conflict record:
it applies first-after-nuke suppression, confidence and weight-based stall
qualification, same-store reinforcement, different-store replacement or
confidence decrement, delete decay, and finite-table overflow reporting.
`MDBQueueFanout` wraps that table with finite lookup/delete/record command
queues, atomically fans lookup results to LU and SU output queues, freezes
delete/record behind a blocked lookup fanout, and exposes the scalar
`StoreUnit::mdbCheck` wakeup decision for matching ready STQ rows.
`ScalarLSUMDBPath` composes those neutral mechanisms beneath the canonical
scalar LSU. Accepted loads enqueue lookup before launch; accepted
address-bearing stores train conflict state only when record/wait-plan
capacity is available; multi-row active wait masks are retained; LU/SU output
is held until LIQ mutation applies; and resolved conflicts publish typed Linx
inner/nuke flush requests. Recovery clears transient queues while preserving
SSIT predictor state. `LoadWaitStoreTimeout` adds deterministic per-row ageing
for stable predicted-store waits. Expiry is retained until one cycle can both
clear the LIQ wait and enqueue MDB delete feedback, after which `MDBSSIT`
decays or releases the failed prediction.
`LoadStoreForwarding` is the first scalar store-to-load byte forwarding owner:
it selects the nearest older eligible store per requested load byte, forwards
ready bytes over a cache-data baseline, reports not-ready wait masks, and keeps
LDQ/STQ row mutation plus memory/recovery publication in later owner packets.
`LoadForwardPipeline` wraps that selector in the first registered E2/E3/E4
load-forwarding boundary, carrying merged line data, byte-valid masks,
wait-store replay classification, source-return gating, and return-slot
readiness into an E4 wakeup decision.
`LoadInflightQueue` is the first registered LIQ/LHQ row-state owner around
that pipeline: it allocates slot-plus-wrap load IDs, launches WAIT rows through
the forwarding pipe, applies E4 hit/miss/replay outcomes to resident rows, and
publishes an LHQ-style resolved-load record for later conflict checks.
`LoadReplayWakeup` adds the first store-unit/SCB replay wakeup sidecar for
that row owner: it clears matching wait-store diagnostics, merges wakeup bytes
into eligible miss/working rows, and returns completed replay rows to `Wait`
for the normal relaunch path while leaving L1 refill, ready-table, bypass, and
trace ownership to later packets.
`LoadRefillWakeup` adds the first read-refill wakeup sidecar: it matches
refill lines against unresolved scalar LIQ rows, records local `l1Hit` plus
full-line data, and lets later relaunch use row-owned bytes through the normal
`LoadForwardPipeline` path.
`LoadReplayReturnConsumerReady` names the replay-return consumer split before
live LIQ relaunch is enabled: every ordinary replay return still needs an IEX
LRET sink, while the dependent mem-wakeup sink is only required for selected
rows that are not speculative-wakeup rows and not stack-valid rows.

The current `LinxCoreTop` is a reduced bring-up shell, not the final core. It
owns a monitored `ReducedCommitROB` and canonical `ScalarLSU` store plus
active/resolved load boundaries. The LSU independently parameterizes ROB
identity, STQ/SCB, LIQ/ResolveQ, cache-line, register-tag, and MapQ resources.
MDB SSIT/command/output/wait-plan resources and the failed-wait interval are
also independently sized. Cache/miss queues, final recovery arbitration, and
final load return remain staged integration work.

Open setup issues are tracked in `docs/chisel/issues.md`. The optimized
cross-toolchain flow starts in `docs/chisel/integrated-development-flow.md`.
The multi-agent development loop is captured in
`docs/chisel/development-loop.md`, with the detailed packet ledger in
`docs/chisel/agent-loop.md`. The dedicated agent-facing skill-evolve loop,
model-learning map, and launch prompt template live in
`docs/chisel/skill-evolve-loop.md`.

Commands from `rtl/LinxCore`:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/run_chisel_tests.sh --only ScalarDecodeRenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameQueue
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchQueues
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchToSTQ
bash tools/chisel/run_chisel_tests.sh --only STQInsertProbe
bash tools/chisel/run_chisel_tests.sh --only StoreDispatchSTQPath
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
bash tools/chisel/run_chisel_tests.sh --only GPRRenameCheckpoint
bash tools/chisel/run_chisel_tests.sh --only STQFlushPrune
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only STQCommitQueue
bash tools/chisel/run_chisel_tests.sh --only STQCommitDrain
bash tools/chisel/run_chisel_tests.sh --only SCBCommitIngress
bash tools/chisel/run_chisel_tests.sh --only SCBCommitBridge
bash tools/chisel/run_chisel_tests.sh --only SCBEgressSelect
bash tools/chisel/run_chisel_tests.sh --only SCBLookupControl
bash tools/chisel/run_chisel_tests.sh --only SCBStateUpdate
bash tools/chisel/run_chisel_tests.sh --only SCBRowBank
bash tools/chisel/run_chisel_tests.sh --only SCBResponseDecode
bash tools/chisel/run_chisel_tests.sh --only SCBResponseBuffer
bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetrySelect
bash tools/chisel/run_chisel_tests.sh --only SCBResponseRetryQueue
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only MDBSSIT
bash tools/chisel/run_chisel_tests.sh --only MDBQueueFanout
bash tools/chisel/run_chisel_tests.sh --only LoadWaitStoreTimeout
bash tools/chisel/run_chisel_tests.sh --only ScalarLSUMDBPath
bash tools/chisel/run_chisel_scalar_lsu_mdb_path_probe.sh
bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup
bash tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_tests.sh
bash tools/chisel/emit_verilog.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

`build_chisel.sh`, `run_chisel_tests.sh`, and `emit_verilog.sh` require a local
JDK plus `sbt`. The wrappers prefer Homebrew `openjdk@17` when `JAVA_HOME` is
not set. The ROBID-only gate always runs the model-derived semantic checks and
runs the Scala test when the toolchain exists.
