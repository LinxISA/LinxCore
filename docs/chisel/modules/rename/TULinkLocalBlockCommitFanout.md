# TULinkLocalBlockCommitFanout

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/rename/TULinkLocalBlockCommitFanout.scala`
- Tests: `chisel/src/test/scala/linxcore/rename/TULinkLocalBlockCommitFanoutSpec.scala`
- LinxCoreModel:
  - `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`
  - `model/LinxCoreModel/model/bctrl/spe/SPERename.cpp`
  - `model/LinxCoreModel/model/bctrl/LocalRegMgr.cpp`
  - `model/LinxCoreModel/isa/ISACommon/OperandType.h`
- Related Chisel:
  - `chisel/src/main/scala/linxcore/rename/TULinkLocalBankArray.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRetireCommandPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRecoveryCleanupPath.scala`
  - `chisel/src/main/scala/linxcore/rename/TULinkRename.scala`
  - `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`

## Purpose

`TULinkLocalBlockCommitFanout` is the selected-STID fanout boundary for the
post-`CleanCMAP` scalar local-register block-commit event. It consumes one
`ReportLocalRegBlockCommit(bid, stid)` event and presents an atomic valid pulse
to every scalar PE bank for the selected STID only.

R71 introduced this owner before full multi-PE local-register banks existed.
R72 moves it under `TULinkLocalBankArray`, where it drives explicit
`[scalar PE][STID]` bank groups. The current reduced backend still elaborates a
1-PE, 1-STID array, so current behavior is unchanged while the future fanout
contract is explicit and tested.

## Interface

Parameters:

- `peCount`: number of scalar PE local-register bank groups.
- `stidCount`: number of scalar SMT STID bank groups.
- `stidWidth`: event STID width.

Inputs:

- `inValid`, `inBid`, `inStid`: the pending post-clean local block-commit
  event from `TULinkRetireCommandPath`.
- `bankReady`: `Vec(peCount, Vec(stidCount, Bool()))` readiness from the
  downstream local-register bank groups. Each bank group is expected to own
  both scalar SGPR hands for that PE/STID.

Outputs:

- `ready`, `accepted`: upstream handshake. `ready` is true only when `inStid`
  names an implemented STID and every scalar PE bank for that STID is ready.
- `bankValid`, `bankBid`, `bankStid`: atomic per-bank event outputs. The
  module pulses valid only on accepted fanout, so no subset of PE banks can
  consume the event early.
- `selectedStidOH`, `selectedPeReadyMask`, `targetPeMask`: fanout
  observability for debugging and later cross-checks.
- `stidInRange`, `blockedByStidRange`, `blockedByBankReady`: diagnostics for
  unsupported STID and selected-bank backpressure.

## Logic Design

The module compares `inStid` against every implemented STID index and forms a
one-hot selected-STID vector. Out-of-range STIDs keep `ready` low and do not
target any bank.

For an in-range STID, the module checks the selected bank readiness for every
scalar PE:

```text
ready = stidInRange && bankReady[0][stid] && ... && bankReady[peCount-1][stid]
```

`accepted` is `inValid && ready`. The downstream `bankValid` pulse is also
gated by `accepted`, not just by `inValid`. This is intentional: the model call
updates all selected PE SGPR bank groups as one logical operation, so the
Chisel fanout must not allow a ready subset of banks to consume the event
while another selected PE bank is stalled.

## Model Alignment

The model path is:

1. `SPEROB::CommitBlock()` runs `CleanCMAP(bid)`.
2. `SPEROB::ReportLocalRegBlockCommit(bid, stid)` forwards the BID/STID pair
   to `SPERename`.
3. `SPERename::ReportSGPRBlockCommit(bid, stid)` iterates all scalar PE SGPR
   rename groups, selects `peSGPRRename[stid]`, and calls
   `ReportBlockCommit(bid)` on both SGPR hands.
4. `LocalRegMgr::ReportBlockCommit()` releases consecutive retired mapQ rows
   at the deallocation head while their BID matches the committed block.

`TULinkLocalBlockCommitFanout` owns step 3's selected-STID/all-PE fanout
contract. The child bank group remains responsible for both-hand
`LocalRegMgr::ReportBlockCommit` behavior through `TULinkRename.commit*`.

## Timing

The fanout is combinational. It relies on the upstream
`TULinkRetireCommandPath` to hold the pending event until `ready` is true.
Downstream banks see a one-cycle valid pulse only on the accepted fanout cycle.

## Deferred Owners

- Dynamic scalar PE ownership and broader multi-STID top integration into the
  bank array.
- Per-bank trace and cross-check events for full SGPR local-register release.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBlockCommitFanout
bash tools/chisel/run_chisel_tests.sh --only TULinkLocalBankArray
```

Affected gates:

```bash
sbt --client --error 'Test / compile'
bash tools/chisel/run_chisel_tests.sh --only DecodeRenameROBPath
bash tools/chisel/run_chisel_tests.sh --only TULinkRecoveryCleanupPath
bash tools/chisel/run_chisel_tests.sh --only ScalarTURenameBridge
bash tools/chisel/run_chisel_tests.sh --only TULinkRetireCommandPath
bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

The current tests cover selected-STID broadcast to every PE, atomic all-PE
readiness, out-of-range STID rejection, IO shape, and CIRCT elaboration.
