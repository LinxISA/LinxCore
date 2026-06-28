# Chisel Module Index

| Module | Package | Documentation | Status |
|---|---|---|---|
| `InterfaceBundles` | `linxcore.common` | `docs/chisel/interfaces/CommonBundles.md` | Unit-green Phase 1 shared type packet |
| `F4DecodeWindow` | `linxcore.frontend` | `docs/chisel/modules/frontend/F4DecodeWindow.md` | Unit-green Phase 2 F4/D1 decode-window slice |
| `FrontendInstructionBuffer` | `linxcore.frontend` | `docs/chisel/modules/frontend/FrontendInstructionBuffer.md` | Unit-green Phase 2 frontend packet FIFO |
| `FrontendDecodeIngress` | `linxcore.frontend` | `docs/chisel/modules/frontend/FrontendDecodeIngress.md` | Unit-green Phase 2 IB-to-F4 transport wrapper |
| `DispatchROBAllocator` | `linxcore.backend` | `docs/chisel/modules/backend/DispatchROBAllocator.md` | Unit-green backend allocation bridge from BROB BID generation to ROB row allocation |
| `FullBidRecoveryBridge` | `linxcore.recovery` | `docs/chisel/modules/recovery/FullBidRecoveryBridge.md` | Unit-green recovery handoff from full block BID to ring ROBID pruning sidecar |
| `RecoveryCleanupControl` | `linxcore.recovery` | `docs/chisel/modules/recovery/RecoveryCleanupControl.md` | Unit-green registered cleanup-intent owner for BCTRL, rename, backend, frontend, LSU/STQ, tile, PE, and ROB consumers |
| `STQFlushPrune` | `linxcore.lsu` | `docs/chisel/modules/lsu/STQFlushPrune.md` | Unit-green first LSU/STQ cleanup consumer; emits model-derived free masks for valid `STQ_WAIT` rows |
| `STQEntryBank` | `linxcore.lsu` | `docs/chisel/modules/lsu/STQEntryBank.md` | Unit-green first STQ state owner; consumes flush-prune masks, owns row sidecars, split-store merge, resident/WAIT counts, and committed-row free masks |
| `STQCommitQueue` | `linxcore.lsu` | `docs/chisel/modules/lsu/STQCommitQueue.md` | Unit-green R16 store-commit ordering owner; sorted enqueue and downstream-ready issue selection |
| `STQCommitDrain` | `linxcore.lsu` | `docs/chisel/modules/lsu/STQCommitDrain.md` | Unit-green R18 memory-side commit drain boundary; split-aware request descriptors and bank free masks after issue acceptance |
| `SCBCommitIngress` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBCommitIngress.md` | Unit-green R19 scalar SCB ingress owner; 64-byte line allocation, same-line merge, and byte-valid wakeup masks |
| `SCBCommitBridge` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBCommitBridge.md` | Unit-green R20 SCB capacity-feedback bridge; model batch gate and STQ free masks after accepted SCB admission |
| `SCBEgressSelect` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBEgressSelect.md` | Unit-green R21 SCB valid-line egress selector; full-line priority and deterministic not-full fallback |
| `SCBLookupControl` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBLookupControl.md` | Unit-green R22 SCB lookup outcome owner; writable-hit update/free and L2 write/upgrade request descriptors |
| `SCBStateUpdate` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBStateUpdate.md` | Unit-green R23 SCB row-state transition owner; hit free, miss state, and response-driven `Miss -> Lookup` masks |
| `SCBRowBank` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBRowBank.md` | Unit-green R24 registered SCB row-bank composition owner; ingress, egress, lookup, and state-update around one row image |
| `STQSCBCommitPath` | `linxcore.lsu` | `docs/chisel/modules/lsu/STQSCBCommitPath.md` | Unit-green R25 STQ-to-SCB composition owner; SCB accepted `last` fragments are the only committed-row free source |
| `SCBResponseDecode` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBResponseDecode.md` | Unit-green R26 raw SCB response decode owner; model `(entryIndex << 2) | 2` tags become legal `Miss -> Lookup` row responses |
| `SCBResponseBuffer` | `linxcore.lsu` | `docs/chisel/modules/lsu/SCBResponseBuffer.md` | Unit-green R35 raw SCB response FIFO; preserves FIFO order, backpressure, and stale-head decode reporting |
| `MDBConflictDetect` | `linxcore.lsu` | `docs/chisel/modules/lsu/MDBConflictDetect.md` | Unit-green R27 store-arrival conflict classifier; oldest resolved load selection, ST_ADDR wait masks, and inner/nuke split |
| `MDBSSIT` | `linxcore.lsu` | `docs/chisel/modules/lsu/MDBSSIT.md` | Unit-green R28 MDB Store Set ID Table owner; first-after-nuke suppression, weight/confidence learning, and delete decay |
| `MDBQueueFanout` | `linxcore.lsu` | `docs/chisel/modules/lsu/MDBQueueFanout.md` | Unit-green R29 MDB queue/fanout owner; atomic LU/SU fanout, phase freeze on output backpressure, and SU ready-store wakeup |
| `LoadStoreForwarding` | `linxcore.lsu` | `docs/chisel/modules/lsu/LoadStoreForwarding.md` | Unit-green R30 scalar store-to-load byte forwarding owner; nearest older store selection, ready-byte merge, and wait-store replay masks |
| `LoadForwardPipeline` | `linxcore.lsu` | `docs/chisel/modules/lsu/LoadForwardPipeline.md` | Unit-green R31 registered E2/E3/E4 load-forwarding boundary; final byte-valid mask, wait-store replay, and E4 wakeup gating |
| `LoadInflightQueue` | `linxcore.lsu` | `docs/chisel/modules/lsu/LoadInflightQueue.md` | Unit-green R32 LIQ/LHQ row owner; slot-plus-wrap load IDs, E4 row updates, miss pending, and LHQ hit records |
| `LoadReplayWakeup` | `linxcore.lsu` | `docs/chisel/modules/lsu/LoadReplayWakeup.md` | Unit-green R33 store-unit/SCB replay wakeup owner; wait-store clear, byte merge, and LIQ miss-row completion masks |
| `LoadRefillWakeup` | `linxcore.lsu` | `docs/chisel/modules/lsu/LoadRefillWakeup.md` | Unit-green R34 L1 read-refill wakeup owner; same-line row wake masks, local l1Hit sideband, and row-owned relaunch data |
| `ROBID` | `linxcore.rob` | `docs/chisel/modules/rob/ROBID.md` | Unit-green Packet A |
| `ROBEntryStatus` | `linxcore.rob` | `docs/chisel/modules/rob/ROBEntryStatus.md` | Unit-green Phase 5 integrated ROB/CMT status contract |
| `ROBEntryBank` | `linxcore.rob` | `docs/chisel/modules/rob/ROBEntryBank.md` | Unit-green Phase 5 integrated ROB/CMT entry-bank skeleton with flush application and native row IDs |
| `ROBFlushPrune` | `linxcore.rob` | `docs/chisel/modules/rob/ROBFlushPrune.md` | Unit-green Phase 5 integrated ROB/CMT flush-prune selector consumed by `ROBEntryBank` |
| `ReducedCommitROB` | `linxcore.rob` | `docs/chisel/modules/rob/ReducedCommitROB.md` | Unit-green; monitor-backed Verilator xcheck-green reduced harness |
| `CommitIdentity` / `CommitTraceRow` | `linxcore.commit` | `docs/chisel/modules/commit/CommitTrace.md` | Unit-green Packet 0B schema |
| `CommitTraceMonitor` | `linxcore.commit` | `docs/chisel/modules/commit/CommitTraceMonitor.md` | Unit-green Phase 1 schema monitor; wired into reduced ROB |
| `FlushControl` / `FlushOlderSelector` | `linxcore.recovery` | `docs/chisel/modules/recovery/FlushControl.md` | Unit-green Packet B |
| `BID` / `BROB` metadata | `linxcore.bctrl` | `docs/chisel/modules/bctrl/BROB.md` | Unit-green Packet C |
| `LinxCoreTop` | `linxcore.top` | `docs/chisel/modules/top/LinxCoreTop.md` | Unit-green reduced commit shell; top Verilator xcheck-green |
| QEMU cross-check adapter | tooling | `docs/chisel/verification/qemu-crosscheck.md` | Adapter self-test green; reduced top compare green; full compare awaits real frontend/backend rows |
| Agent development loop | coordination | `docs/chisel/agent-loop.md` | ROB/cross-check-first module loop and skill-evolve closeout runbook |

Future rows must be added before each Chisel module leaves draft status.
