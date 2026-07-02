# ReducedLoadReplayCompletionDrain

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayCompletionDrain.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/ReducedLoadReplayCompletionDrainSpec.scala`
- Integrated user: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- LinxCoreModel evidence:
  - `tools/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
    - `LDQInfo::waitStore`
    - `LDQInfo::handleSUWakeup`
  - `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`
    - `STQ::lookupForLoad`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
- Contract IDs: `LC-CHISEL-LSU-STQ-FWD-006`

## Purpose

`ReducedLoadReplayCompletionDrain` consumes a queued reduced replay candidate
only when the same load later completes through the reduced execute pipeline.
This models the current reduced-top approximation: the load is held in E while
the resident store is not ready, then advances to W1/W2 and completes once the
store becomes forwardable.

This is not a full relaunch owner. It does not allocate or mutate
`LoadInflightQueue`, issue a second load, update a ready table, or wake
dependent consumers. It only drains the diagnostic candidate queue when the
reduced in-place completion matches the remembered load identity. R274 expands
that identity check to the model ROB sidecars `BID/GID/RID` plus reduced
`LSID`, preserving the same sidecar shape carried by LinxCoreModel `MemReqBus`
into LDQ rows. R275 leaves matching on that load-completion identity unchanged
but preserves the candidate's forwarding snapshot
`(youngestStoreId, youngestStoreLsId)` through the queue so a later real
`LoadInflightQueue` handoff does not have to recover it from implicit reduced
top aliases.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `candidateValid` | Queue head valid from `ReducedLoadReplayRelaunchQueue`. |
| `candidate` | Remembered replay candidate: load PC, address, size, BID, GID, RID, reduced LSID, and forwarding snapshot `(youngestStoreId, youngestStoreLsId)`. |
| `completeValid` | Reduced execute completion valid. |
| `completeMemLoad` | Completion row is a load memory side effect. |
| `completePc` | Completion row PC. |
| `completeAddr` | Completion row memory address. |
| `completeSize` | Completion row memory size. |
| `completeBid` | Completed row BID. |
| `completeGid` | Completed row GID sidecar. |
| `completeRid` | Completed row RID sidecar. |
| `completeLsId` | Completed row reduced LSID. |

### Outputs

| Signal | Description |
|---|---|
| `consumeReady` | Exact match; drive the queue dequeue handshake. |
| `matchValid` | Candidate and load completion were comparable and all identity fields matched. |
| `mismatch` | Candidate and load completion were comparable but at least one identity field differed. |
| `pcMismatch` | PC mismatch diagnostic. |
| `addrMismatch` | Address mismatch diagnostic. |
| `sizeMismatch` | Size mismatch diagnostic. |
| `bidMismatch` | BID mismatch diagnostic. |
| `gidMismatch` | GID mismatch diagnostic. |
| `ridMismatch` | RID mismatch diagnostic. |
| `lsIdMismatch` | LSID mismatch diagnostic. |

## State

The module is combinational and stateless. The queue owns candidate residency;
execute owns completion timing.

## Logic Design

The model replay path clears a load's wait-store bit through
`LDQInfo::handleSUWakeup`, after which the row can return to the normal wait
and launch path. The reduced top does not yet own full LIQ row launch. Instead
it keeps the load resident in execute while `ReducedStoreResidentForward`
reports wait and completes that same load when forwarding becomes ready.

R273/R274 therefore drains the queued candidate only on an exact in-place
completion match:

1. Require a valid queue head and a valid load completion.
2. Compare candidate and completion PC.
3. Compare load address and size from the completion memory sideband.
4. Compare BID, GID, and RID using valid, wrap, and value.
5. Compare reduced LSID using valid, wrap, and value.
6. Assert `consumeReady` only when every compared completion-identity field
   matches. The forwarding snapshot is carried through this module's candidate
   port but is not part of the current in-place W2 completion comparison.

If a candidate is pending and a different load completion appears, the module
reports mismatch diagnostics but leaves the candidate queued. That preserves
the first divergence for debug instead of silently discarding the wrong
candidate.

## Flush/Recovery

The module has no internal flush state. `ReducedLoadReplayRelaunchQueue`
handles flush clearing and suppresses queue output during flush.

## Deferred Owners

- A real LIQ/LDQ relaunch consumer.
- Arbitration between replay candidates and newly issued loads.
- Ready-table or dependent-consumer wakeup.
- Precise replay-candidate recovery pruning by BID/GID/RID/LSID.

## Verification

Focused gates:

```bash
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayCompletionDrain
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadReplayRelaunchQueue
bash tools/chisel/run_chisel_tests.sh --only ReducedLoadWaitReplaySlot
bash tools/chisel/run_chisel_tests.sh --only ReducedScalarAluExecute
bash tools/chisel/run_chisel_tests.sh --only ResidentStoreReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup
bash tools/chisel/run_chisel_tests.sh --only ReducedStoreResidentForward
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop
git diff --check
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r273-replay-completion-drain-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --reduced-store-dispatch-stq --disable-store-memory-mutation --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r274-replay-full-identity-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --reduced-store-dispatch-stq --disable-store-memory-mutation --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh --build-dir generated/r275-replay-forwarding-snapshot-1024-qemu-elf-xcheck --elf tests/benchmarks/build/coremark_real.elf --expected-rows 0 --capture-rows 1024 --allow-block-markers --allow-block-loop-reentry --reduced-store-dispatch-stq --disable-store-memory-mutation --max-seconds 30 -- -nographic -monitor none -machine virt -m 1280M -kernel tests/benchmarks/build/coremark_real.elf
```

Reference tests cover exact completion consumption, non-load completion
suppression, mismatch diagnostics without consumption, absent-candidate
suppression, and Chisel elaboration of match diagnostics.

R273 live evidence:

- `generated/r273-replay-completion-drain-1024-qemu-elf-xcheck/report/crosscheck_manifest.json`
- `status=pass`, `comparator_status=0`
- `compared_rows=665`, `mismatch_count=0`
- `cbstop_counts dut=0 qemu=0 inflation_ratio=1.0 warn=false`
- `model/LinxCoreModel=3c0878da3aa1e06669b718e93269f094e7244066`

R274 live evidence:

- `generated/r274-replay-full-identity-1024-qemu-elf-xcheck/report/crosscheck_manifest.json`
- `status=pass`, `comparator_status=0`
- `compared_rows=665`, `mismatch_count=0`
- `cbstop_counts dut=0 qemu=0 inflation_ratio=1.0 warn=false`
- `rtl/LinxCore=18d8a800f59e50e4aa912e65e56cab1ae2e37f33`
- `model/LinxCoreModel=3c0878da3aa1e06669b718e93269f094e7244066`
- `emulator/qemu=8f6de68e091dbef19a89b977f3d18cbbc6f43b68`

R275 live evidence:

- `generated/r275-replay-forwarding-snapshot-1024-qemu-elf-xcheck/report/crosscheck_manifest.json`
- `status=pass`, `comparator_status=0`
- `compared_rows=665`, `mismatch_count=0`
- `cbstop_counts dut=0 qemu=0 inflation_ratio=1.0 warn=false`
- `rtl/LinxCore=49ed3f993750cb61ec1e829a424154b5d249d8b2`
- `model/LinxCoreModel=793722e85c62eade9ab4e8481c9577dc5b9c98f7`
- `emulator/qemu=ea87f8ca51381e2d0d42424974fe13364a5e074e`
