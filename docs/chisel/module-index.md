# Chisel Module Index

| Module | Package | Documentation | Status |
|---|---|---|---|
| `ROBID` | `linxcore.rob` | `docs/chisel/modules/rob/ROBID.md` | Unit-green Packet A |
| `ReducedCommitROB` | `linxcore.rob` | `docs/chisel/modules/rob/ReducedCommitROB.md` | Unit-green; monitor-backed Verilator xcheck-green reduced harness |
| `CommitIdentity` / `CommitTraceRow` | `linxcore.commit` | `docs/chisel/modules/commit/CommitTrace.md` | Unit-green Packet 0B schema |
| `CommitTraceMonitor` | `linxcore.commit` | `docs/chisel/modules/commit/CommitTraceMonitor.md` | Unit-green Phase 1 schema monitor; wired into reduced ROB |
| `FlushControl` / `FlushOlderSelector` | `linxcore.recovery` | `docs/chisel/modules/recovery/FlushControl.md` | Unit-green Packet B |
| `BID` / `BROB` metadata | `linxcore.bctrl` | `docs/chisel/modules/bctrl/BROB.md` | Unit-green Packet C |
| `LinxCoreTop` | `linxcore.top` | `docs/chisel/modules/top/LinxCoreTop.md` | Unit-green reduced commit shell; top Verilator xcheck-green |
| QEMU cross-check adapter | tooling | `docs/chisel/verification/qemu-crosscheck.md` | Adapter self-test green; reduced top compare green; full compare awaits real frontend/backend rows |

Future rows must be added before each Chisel module leaves draft status.
