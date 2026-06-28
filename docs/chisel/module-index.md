# Chisel Module Index

| Module | Package | Documentation | Status |
|---|---|---|---|
| `ROBID` | `linxcore.rob` | `docs/chisel/modules/rob/ROBID.md` | Unit-green Packet A |
| `CommitIdentity` / `CommitTraceRow` | `linxcore.commit` | `docs/chisel/modules/commit/CommitTrace.md` | Unit-green Packet 0B schema |
| `FlushControl` / `FlushOlderSelector` | `linxcore.recovery` | `docs/chisel/modules/recovery/FlushControl.md` | Unit-green Packet B |
| `BID` / `BROB` metadata | `linxcore.bctrl` | `docs/chisel/modules/bctrl/BROB.md` | Unit-green Packet C |
| `LinxCoreTop` | `linxcore.top` | `docs/chisel/verification/chisel-flow.md` | Unit-green stub; Verilator lint passes |
| QEMU cross-check adapter | tooling | `docs/chisel/verification/qemu-crosscheck.md` | Adapter self-test green; full compare awaits live Chisel rows |

Future rows must be added before each Chisel module leaves draft status.
