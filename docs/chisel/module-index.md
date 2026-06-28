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
