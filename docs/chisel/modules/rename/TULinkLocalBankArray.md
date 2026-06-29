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

R72 keeps the live reduced backend selected at PE0/STID0. R73 begins replacing
that constant selection by routing the bridge's active STID from the queued
decoded row's thread/STID sidecar. R75 routes the active PE side from the
queued row's `peId` sidecar as well, while default frontend/top packets still
produce PE0 unless an upstream owner sets a nonzero PE. The important progress
is that the hardware hierarchy now matches the model shape:
rename/external commit are routed to one active bank group, recovery cleanup is
broadcast to every bank group, and post-clean local block commit is accepted
only through the selected-STID/all-PE fanout.
R74 separates local retire routing from active rename routing. Relation-cmap
mark/release commands now carry their own retired-row PE/STID and target that
bank group even when the current rename head selects a different bank.

## Interface

Inputs:

- `activePeId`, `activeStid`: selected bank group for the current reduced
  rename and external commit traffic.
- `in`, `renameValid`: decoded-uop payload for the active bank group.
- `retireValid`, `retireKind`, `retireSeq`, `retireDealloc`: relation-cmap
  retire command.
- `retirePeId`, `retireStid`: retired-row bank selector carried by the retire
  command.
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
- `retirePeInRange`, `retireStidInRange`, `retireBankValid`, `retirePeOH`,
  `retireStidOH`: retire-command bank decode diagnostics.
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

Only that active bank receives current reduced rename and external commit valid
pulses. R74 changes retire: only the bank selected by
`retirePeId/retireStid` receives relation-cmap mark/release commands. All bank
groups receive the same recovery cleanup intent and ROB/LSU source candidates,
matching `SPERename::Flush`, which iterates every PE, STID, and SGPR hand.

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
source by using the reduced backend row's STID for active-bank selection, and
R75 wires the matching row-owned PE sidecar into active-bank selection.
R74 wires the retire selector separately, matching the model split between
`Rename()`'s active row lookup and `RepLocalRetired()`'s retired-row bank
arguments. Dynamic PE production remains deferred, but the bank-array boundary
now has independent active and retire target surfaces.

## Deferred Owners

- Top-level nonzero PE production and matching multi-PE instantiation. The
  reduced backend now routes decoded `peId` into `activePeId`, but default
  packets still select PE0 unless an upstream owner drives a different PE.
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

The R72/R73/R74 tests cover active PE/STID selection shape, retire PE/STID
selection independent from active rename selection, selected-STID all-PE
block-commit readiness, bridge/backend active-STID selector plumbing, IO
widths, and elaboration through the bank array, recovery cleanup path, fanout,
and T/U rename child hierarchy.
