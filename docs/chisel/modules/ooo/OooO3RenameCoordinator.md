# OooO3RenameCoordinator

## Purpose

`OooO3RenameCoordinator` is the production atomic seam between D2 virtual
planning and the D3/S1 physical owners. It composes grouped ROB/BROB/PC
reservation, PTag staging and P rename, independent T/U rename, dispatch
reservation, IEX S1 publication, fast resolve, commit, and retained recovery.
No child owner may publish or recover independently through this wrapper.

## D3 identity and pointer domains

The D3 reservation allocator owns four independent per-STID state domains:

- `headSlot/headGeneration` identifies the oldest live published group and
  advances only when an exact release is accepted;
- `tailSlot/tailGeneration` identifies the next absolute wrap-qualified ROB
  group reservation and advances on reserve;
- `tailEpoch` invalidates D2 virtual-tail previews whenever reserve, release,
  cancel, or recovery changes the reservation view;
- `nextTransactionId` identifies the next D3 reservation transaction and
  advances monotonically modulo its configured width after accepted reserve.

Recovery may rewind the wrap-qualified tail to the exact surviving suffix end.
It must not rewind transaction identity. Recovery also advances `tailEpoch`,
so a D2 plan made from the pre-recovery tail is stale even when the restored
numeric slot happens to match it.

These domains must not be derived from an instruction serial number, a
cumulative issued count, or current ROB occupancy. In particular:

```text
accepted reserve: tail += groupCount; tailEpoch += 1; transactionId += 1
accepted release: head += groupCount; tailEpoch += 1
accepted recovery: tail = exact newTail; tailEpoch += 1; transactionId holds
```

`usedGroups` and `publishedGroups` describe live physical occupancy. They
shrink on recovery and therefore are not transaction counters.

## Recovery composition

The grouped ROB is the sole exact kill-set authority. The lower
`OooRobBrobPcCoordinator` validates the retained ROB plan independently
against D3 head/count/tail state, BROB state, and PC-buffer state. The upper
coordinator then prepares dispatch, IEX, fast-resolve, and P/T/U owners and
fires one common destructive apply. Rejection or abort mutates no owner.

The following outputs are read-only verification and integration visibility:

- `d3UsedGroups`, `d3PublishedGroups`;
- `d3TailSlot`, `d3TailGeneration`, `d3TailEpoch`;
- `d3NextTransactionId`.

They expose the real owner state to independent reference models. They are not
alternate allocation inputs and must not be used to reconstruct a new tail or
transaction identity in production glue.

## Reference-model evidence

The LinxCoreModel keeps the same conceptual separation:

- `SPEROB::allocROB` advances allocation state independently from live size;
- `SPEROB::flush` truncates exact live entries and restores the allocation
  position;
- `GPRRename::Flush` frees killed MapQ PTags and rebuilds speculative mapping
  from committed state plus survivors.

The O8.3a randomized scoreboard therefore maintains independent instruction
serial, transaction ID, head, tail, tail epoch, and live-source state. After
every publication or recovery it compares those domains separately with the
DUT, including recovery after RID wrap.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooD3ReservationAllocator
bash tools/chisel/run_chisel_tests.sh --only OooRobBrobPcCoordinator
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameRandomized
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
bash tools/chisel/build_chisel.sh
```

Directed coverage includes a nonzero ROB head, tail-generation wrap, exact
recovery to slot zero of the new generation, unchanged transaction identity,
advanced tail epoch, and successful reuse of the restored tail. The randomized
four-STID integration crosses publication and survivor/trigger-kill recovery
while comparing all P/T/U and grouped-ROB occupancy state after every event.

## Remaining O8 work

O8.3a closes absolute recovery-tail/reference-model behavior only. ROB, MapQ,
and PC-buffer bank/subbank timing, occupancy/in-flight/PTag-aware dispatch
steering, safe-mode policy, and complete 2/4/6 physical timing closure remain
separate O8 packets.
