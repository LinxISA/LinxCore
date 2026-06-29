# TULinkLocalBankArray

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkLocalBankArray.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkLocalBankArraySpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/isa/ISACommon/OperandType.h`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkLocalBlockCommitFanout.scala`
  - `chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`TULinkLocalBankArray` is the structural SGPR local-register bank owner for
the Chisel T/U lane. It turns the previous reduced single
`TULinkRecoveryCleanupPath` instance into an explicit
`[scalar PE][STID]` bank-group array. Each bank group still contains the two
SGPR hands, T and U, through `TULinkRename`.

R72 keeps the live reduced backend selected at PE0/STID0, because decode does
not yet publish a dynamic scalar PE/STID routing surface. R73 begins replacing
that constant selection by routing the bridge's active STID from the queued
decoded row's thread/STID sidecar, while PE remains the reduced PE0 lane. The
important progress is that the hardware hierarchy now matches the model shape:
rename/retire/external commit are routed to one active bank group, recovery
cleanup is broadcast to every bank group, and post-clean local block commit is
accepted only through the selected-STID/all-PE fanout.

## Interface

Inputs:

- `activePeId`, `activeStid`: selected bank group for the current reduced
  rename, retire, and external commit traffic.
- `in`, `renameValid`: decoded-uop payload for the active bank group.
- `retireValid`, `retireKind`, `retireSeq`, `retireDealloc`: relation-cmap
  retire command routed to the active bank group.
- `commitValid`, `commitBid`: external local block-commit command routed to
  the active bank group for backward-compatible reduced tests.
- `localBlockCommitValid`, `localBlockCommitBid`, `localBlockCommitStid`:
  post-`CleanCMAP` `ReportLocalRegBlockCommit` event from
  `TULinkRetireCommandPath`.
- `cleanup`, `robSource`, `lsuSource`: recovery cleanup intent plus selected
  row sources broadcast to every bank group.

Outputs:

- The selected bank group's `TULinkRecoveryCleanupPath` surfaces: `ready`,
  `accepted`, source and destination resolution, T/U sequences, pressure
  diagnostics, retire results, cleanup publisher/selector diagnostics, and
  mapQ masks.
- `activePeInRange`, `activeStidInRange`, `activeBankValid`, `activePeOH`,
  `activeStidOH`: active-bank decode diagnostics.
- `localBlockCommitReady`, `localBlockCommitAccepted`: upstream handshake for
  the post-clean local block-commit event.
- `localBlockCommitFanout*`: selected-STID fanout diagnostics from
  `TULinkLocalBlockCommitFanout`.
- `bankTUsedEntries`, `bankUUsedEntries`: per-bank occupancy observability for
  future multi-bank tests.

## Logic Design

The module instantiates:

```text
TULinkRecoveryCleanupPath[peCount][stidCount]
TULinkLocalBlockCommitFanout
```

For each bank group `(pe, stid)`, `localStid` is set to the bank's STID index.
The active-bank predicate is:

```text
peMatches(pe) && stidMatches(stid)
```

Only that active bank receives current reduced rename, retire, and external
commit valid pulses. All bank groups receive the same recovery cleanup intent
and ROB/LSU source candidates, matching `SPERename::Flush`, which iterates
every PE, STID, and SGPR hand.

Post-clean local block commit is not routed through the active-bank predicate.
It goes through `TULinkLocalBlockCommitFanout`. The fanout selects the event
STID and waits until every scalar PE bank group for that STID reports
`localBlockCommitReady`. Only then does it pulse the selected banks. This
preserves the model's `ReportSGPRBlockCommit(bid, stid)` shape:

```text
for each scalar PE:
  for each SGPR hand in sgprRenameUnit[pe][stid]:
    ReportBlockCommit(bid)
```

The selected output surfaces are muxed from the active bank. If the active PE
or STID is out of range, `ready` is low, selected-bank outputs are zeroed, and
`blockedByMaintenance` reports the bad active-bank selection while a valid
rename is present.

## Model Alignment

`SPERename::Build()` constructs:

```text
sgprRenameUnit[stdPeCount][scalar_smt_thread][SGPR_HAND_COUNT]
```

with `SGPR_HAND_COUNT == 2`, where hand 0 is T and hand 1 is U.
`TULinkRecoveryCleanupPath` already models the two-hand bank group through
`TULinkRename`. `TULinkLocalBankArray` adds the missing PE/STID dimensions
around that bank group.

The model scopes operations as follows:

- `SPERename::Rename()` chooses one bank group by `inst->peID` and
  `inst->stid`.
- `SPERename::RepLocalRetired()` chooses one `(peid, tid, hand)` bank.
- `SPERename::Flush()` broadcasts cleanup to all SGPR bank groups.
- `SPERename::ReportSGPRBlockCommit(bid, stid)` selects one STID and applies
  block commit to both SGPR hands for every scalar PE.

R72 preserves those scopes structurally. R73 wires the first dynamic selector
source by using the reduced backend row's STID for active-bank selection.
Dynamic PE routing and retire-command PE/STID payloads remain deferred, but the
bank-array boundary now exists for those later owners to target.

## Deferred Owners

- Decode/rename dynamic PE routing into `activePeId`; the current reduced
  backend still selects PE0.
- Retire-command payloads that carry PE/STID and route release commands to the
  exact retired row's bank group instead of the reduced active bank.
- Per-bank ROB/LSU cleanup source vectors once ROB/STQ expose multiple PE/STID
  candidates in one cycle.
- Ready-table initialization and wakeup ownership per T/U bank group.
- Multi-PE top-level integration beyond the current PE0/STID0 reduced lane.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBankArray
```

Affected gates:

```bash
cd chisel && sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBlockCommitFanout
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRename
bash tools/chisel/run_chisel_tests.sh --only TULinkRelationCmap
bash tools/chisel/run_chisel_tests.sh --only ROBEntryBank
bash tools/chisel/run_chisel_tests.sh --only DispatchROBAllocator
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The R72/R73 tests cover active PE/STID selection shape, selected-STID all-PE
block-commit readiness, bridge/backend active-STID selector plumbing, IO
widths, and elaboration through the bank array, recovery cleanup path, fanout,
and T/U rename child hierarchy.
