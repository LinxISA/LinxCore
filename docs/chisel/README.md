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
visibility but still leaves opcode decode, macro-boundary decode, and D1/D2
uop construction to future decode-owner modules. The first integrated ROB/CMT
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
tile, PE fanout, and ROB consumers. `STQFlushPrune` is the first concrete
LSU/STQ consumer: it mirrors the model `FlushBus::match` predicate and emits
free masks for valid `STQ_WAIT` rows. `STQEntryBank` is the first STQ state
owner that consumes those masks, stores row sidecars, performs first-free
allocation, merges split store address/data halves, and keeps resident plus
WAIT/outstanding counts. `STQCommitQueue` is the first store-commit ordering
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
rows before they drive `SCBStateUpdate`.
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

The current `LinxCoreTop` is a reduced bring-up shell, not the final core. It
forwards a monitored `ReducedCommitROB` so top-level generated RTL carries the
same commit-window contract used by the reduced QEMU cross-check harness.

Open setup issues are tracked in `docs/chisel/issues.md`. The multi-agent
development loop and skill-evolve closeout rules are captured in
`docs/chisel/agent-loop.md`.

Commands from `rtl/LinxCore`:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
bash tools/chisel/run_chisel_tests.sh --only ROBEntryStatus
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only ROBFlushPrune
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_tests.sh --only FullBidRecoveryBridge
bash tools/chisel/run_chisel_tests.sh --only RecoveryCleanupControl
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
bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect
bash tools/chisel/run_chisel_tests.sh --only MDBSSIT
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
