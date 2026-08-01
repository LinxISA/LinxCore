# OooMemoryOrderAllocator

## Purpose

`OooMemoryOrderAllocator` is the sole OOO owner of the full per-STID memory
serial tails. It assigns one unified LSID range plus type-local load-ID or
store-ID ranges before rename publication. These identities describe program
order; they are never physical LHQ/STQ indices.

Source and tests:

- `chisel/src/main/scala/linxcore/ooo/OooMemoryOrderAllocator.scala`
- `chisel/src/test/scala/linxcore/ooo/OooMemoryOrderAllocatorSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala`

## State and owner events

Each STID owns:

- `next`: the next full `{LSID, load ID, store ID}` values plus the valid exact
  unified LSID of the youngest allocated store;
- at most one provisional `OooMemoryOrderReservationLease` retained between
  D3 reserve and common S1 publication.

Prepare is side-effect free. It walks active logical uops in slot order,
checks typed memory controls against `memoryRequestCount`, reproduces the D2
load/store demand, and builds consecutive before/after snapshots. An accepted
D3 reserve claims the whole transaction or none. Common S1 publication checks
the exact `{PE, STID, epoch, transaction, uopMask, tail}` lease and removes
only the provisional marker; the assigned serial tail remains live.

For an accepted store range, the youngest-store LSID becomes
`before.lsid + requestCount - 1`; loads and non-memory rows preserve it. This
is not reconstructible from `storeId - 1` after load/store interleaving and is
therefore carried through ROB snapshots and recovery as independent state.

A private cancel may rewind only its own unpublished suffix. Published state
can move backward only through global recovery. ROB is the recovery kill-set
and snapshot authority: the original pivot plus ordered killed rows proves the
old published chain, while the surviving tail or trimmed logical-pivot
snapshot defines the new tail. Partial-pivot recovery deliberately keeps these
two values separate.

## Downstream contract

The exact lease joins ROB/BROB/PC, P/T/U rename, dispatch, fast resolve, and
IEX on one common S1 fire. ROB groups retain group and logical-uop snapshots.
IEX stores each logical allocation in the stable payload sidecar; split STA and
STD children inherit the same logical store range.

The store path now allocates a separate generation-qualified physical STQ
lease and binds it to full SID/LSID plus the exact logical ROB member.
Canonical STQ state owns address/PGEN and data/DGEN convergence. The IEX
AGU/STD scheduling rows retain the minimal logical-store order key and derive
the same-STID oldest issue frontier directly from canonical IQ residency. No
pre-STQ join buffer or duplicate frontier queue may become a competing owner,
and queue wrap must not alter serial identity.

## Remaining gaps

- Connect actual STQ issue-window credit to the existing per-STID logical-store
  frontier, then add commit/non-flush authorization, forwarding, and
  architectural visibility.
- Define and verify full-serial wrap quiescence or a separate generation
  sidecar. Plain unsigned comparison across full-LSID wrap remains forbidden.
- Connect the owner to the final static core and prove Dhrystone/CoreMark plus
  synthesis timing after the complete LSU path exists.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooMemoryOrderAllocator
bash tools/chisel/run_chisel_tests.sh --only OOOIntegrationSpec
bash tools/chisel/run_chisel_tests.sh --only OOORobCommitSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreIssueFrontier
bash tools/chisel/build_chisel.sh
```

Directed tests cover mixed load/non-memory/scalar-store/pair-store ranges at a
40-bit LSID width, independent STIDs, provisional cancel, same-cycle
publish-and-replace without a bubble, malformed demand, global suffix recovery,
partial-pivot trim, and a real D2/O3/S1/IEX load row whose full identity is
restored with the rest of the OOO owners. The recovery cases also prove the
youngest-store unified-LSID tail survives publication and is restored from the
selected ROB snapshot.
