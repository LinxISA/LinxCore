# ReducedStoreWaitReplayChiselPathProbe

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathProbe.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathSpec.scala`
- Verilator harness: `rtl/LinxCore/tools/chisel/reduced_store_wait_replay_chisel_path_tb.cpp`
- Wrapper: `rtl/LinxCore/tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::handleSTQReceive`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-006`

## Purpose

`ReducedStoreWaitReplayChiselPathProbe` is a bounded generated-RTL fixture for
the reduced store-wait replay path. It composes the real Chisel owners used by
the reduced replay-LIQ path:

- `STQEntryBank`
- `ReducedStoreResidentForward`
- `ReducedLoadWaitReplaySlot`
- `ResidentStoreReplayWakeup`
- `ReducedLoadReplayRelaunchQueue`
- `ReducedLoadReplayLiqAllocPath`

The probe exists because R450 showed that delaying split STD data in the live
top does not force the younger load to execute while the resident store is
address-ready and data-late. This fixture drives that microarchitectural
window directly and proves the owner chain through generated RTL.

## Interface

### Store Insert

| Signal | Description |
|---|---|
| `storeInsertValid` | Drives one STQ insert request. |
| `storeInsert` | Native `STQStoreRequest`. The harness uses `Addr` for STA, `Data` for STD merge, and `All` for the ready-store negative case. |
| `storeInsertAccepted` | STQ insert accepted. |
| `stqOccupiedMask` | Resident STQ rows. |
| `stqAddrReadyMask` | Address-ready resident rows. |
| `stqDataReadyMask` | Data-ready resident rows. |

### Load Lookup

| Signal | Description |
|---|---|
| `loadValid` | Younger load lookup is active. |
| `loadAddr` | Load byte address. |
| `loadSize` | Load size in bytes. |
| `loadBid` | Younger load BID. |
| `loadLsId` | Younger load LSID. |
| `baseLoadData` | Base data presented to the resident-forward path. |
| `captureEnable` | Allows `ReducedLoadWaitReplaySlot` to capture a wait-store hit. |

### Diagnostics

| Signal | Description |
|---|---|
| `forwardWaitBlocked` | Resident store forwarder selected a not-ready older store. |
| `forwardReady` | Resident store forwarder can return ready store bytes. |
| `forwardWaitStoreValid` | Forwarder produced a wait-store identity. |
| `forwardWaitStoreIndex` | STQ row selected as the blocking store. |
| `waitSlotCaptureAccepted` | Wait slot captured the blocking store key. |
| `waitSlotActive` | Wait slot is resident. |
| `wakeValid` | Ready store row emitted a store-unit replay wakeup. |
| `waitStoreClear` | Store wakeup cleared the wait slot. |
| `relaunchQueueOutFire` | Relaunch queue handed the replay candidate to LIQ allocation. |
| `liqAllocAccepted` | LIQ accepted the replay candidate. |
| `liqRefillValid` / `liqRefillLineAddr` / `liqRefillData` | Fixture-driven refill wakeup for the allocated replay row. |
| `liqRefillAccepted` / `liqRefillWakeMask` | LIQ refill wakeup was accepted and matched resident rows. |
| `liqLaunchEnable` | Fixture-owned launch arm for `ReducedLoadReplayLiqAllocPath`. |
| `liqLaunchValid` / `liqLaunchReady` / `liqLaunchDriveValid` / `liqLaunchAccepted` | Selector, readiness, gated drive, and accepted launch diagnostics. |
| `liqLaunchSelectedLoadLsId` | Selected launch row preserved the original load LSID. |
| `liqWaitMask` / `liqRepickMask` | LIQ row status masks before and after launch. |
| `liqFirstYoungestStoreLsId` | Allocated LIQ row preserved the forwarding snapshot sidecar. |

## Logic Design

The generated-RTL harness runs two scenarios:

1. A ready `ST_ALL` row is inserted, then a younger load looks up the same
   address. The forwarder reports ready forwarding and no wait-store key is
   captured.
2. A split `STA` row is inserted, then a younger load looks up the same
   address while the store has `addrReady=1` and `dataReady=0`. The forwarder
   reports a wait hit, the wait slot captures the store key, a later matching
   `STD` merge makes the row ready, `ResidentStoreReplayWakeup` emits a store
   wakeup, the wait slot clears into a relaunch candidate, the queue fires, and
   `ReducedLoadReplayLiqAllocPath` allocates a resident LIQ row.
3. The harness drives a read-refill wakeup for the allocated row's cacheline.
   This gives the row complete row-owned bytes through the existing
   `LoadRefillWakeup` path, making `LoadInflightLaunchSelect` assert
   `launchValid`. With `liqLaunchEnable` asserted for one cycle, the LIQ row
   accepts launch and enters `Repick`.

This is fixture evidence for the reduced owner chain. It is not architectural
QEMU/DUT replacement evidence and does not prove live top scheduling can yet
create this timing window.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreWaitReplayChiselPath
bash tools/chisel/run_chisel_reduced_store_wait_replay_chisel_path.sh
```

The wrapper emits SystemVerilog under
`generated/chisel-verilog/reduced-store-wait-replay-chisel-path`, builds a
Verilator executable, and writes:

```text
generated/chisel-reduced-store-wait-replay-chisel-path/report/reduced_store_wait_replay_chisel_path.json
```

The JSON schema is `linxcore.reduced_store_wait_replay_chisel_path.v1`. A
passing R452 report records `ready_forward_observed=true`,
`sta_wait_capture=true`, `not_ready_wake_blocked=true`,
`std_wake_clear=true`, `relaunch_queue_fire=true`, `liq_alloc=true`, and
`youngest_store_lsid=1`.

R453 extends the same report with `liq_refill=true`,
`liq_launch_valid=true`, `liq_launch_accepted=true`, and
`launch_load_lsid=3`. This remains generated-RTL fixture evidence: the refill
and launch arm are harness-driven, and the live reduced top still ties the
alloc-path refill wakeup inactive.
