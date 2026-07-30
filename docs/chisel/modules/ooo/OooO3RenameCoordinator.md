# OooO3RenameCoordinator

## Purpose

`OooO3RenameCoordinator` is the atomic seam between D2 virtual
planning and the D3/S1 physical owners. It composes grouped ROB/BROB/PC
reservation, PTag staging and P rename, independent T/U rename, dispatch
reservation, full memory-order serial allocation, IEX S1 publication, fast
resolve, commit, and retained recovery. No child owner may publish or recover
independently through this wrapper.

`OooRobStoreCommitOwner` is a commit peer of P rename and T/U retirement.
Its side-effect-free exactness/credit probe joins commit start; its complete
semantic store-beat batch is captured only on the same terminal
ROB/BROB/PC deallocation fire. The resulting `storeCommit` stream is
independently backpressured by LSU and never carries a physical STQ index.

`OooMemoryOrderAllocator` is joined at the same three boundaries as the other
physical owners. D2 prepare proves recipe-derived load/store demand and claims
one complete per-STID serial suffix at D3 reserve. The exact lease reaches ROB
group bindings and IEX S1 on the common publication fire. A provisional lease
may roll back only through private same-STID cancel; a published tail may move
back only through the grouped-ROB recovery plan and the common all-owner apply.
Full LSID/LID/SID values are independent from RID, BID, LHQ, and STQ capacity.

Execution completion is a retained vector boundary. Every physical terminal
lane plus fast resolve may publish in the same cycle into
`OooRobCompletionBuffer`; the buffer preserves lane/FIFO order and drains the
current single-write grouped ROB without forcing completed producers to wait
behind a one-result arbiter. The buffer joins the same global recovery
prepare/apply transaction and compacts only exact surviving members.

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
coordinator then prepares dispatch, IEX, fast-resolve, memory-order, and P/T/U
owners and fires one common destructive apply. Rejection or abort mutates no
owner. The memory-order owner uses the original pivot/killed-row chain to prove
the old published tail, while the surviving pivot's per-logical-uop snapshot
defines the new trimmed tail.

The following outputs are read-only verification and integration visibility:

- `d3UsedGroups`, `d3PublishedGroups`;
- `d3TailSlot`, `d3TailGeneration`, `d3TailEpoch`;
- `d3NextTransactionId`.

They expose the real owner state to independent reference models. They are not
alternate allocation inputs and must not be used to reconstruct a new tail or
transaction identity in glue.

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
bash tools/chisel/run_chisel_tests.sh --only OooMemoryOrderAllocator
bash tools/chisel/run_chisel_tests.sh --only OooO3IexIntegration
bash tools/chisel/run_chisel_tests.sh --only OooRobCompletionBuffer
bash tools/chisel/build_chisel.sh
```

Directed coverage includes a nonzero ROB head, tail-generation wrap, exact
recovery to slot zero of the new generation, unchanged transaction identity,
advanced tail epoch, and successful reuse of the restored tail. The randomized
four-STID integration crosses publication and survivor/trigger-kill recovery
while comparing all P/T/U and grouped-ROB occupancy state after every event.

## Remaining O8 work

O8.3a closes absolute recovery-tail/reference-model behavior. O8.3b-d establish
MapQ retained read timing and the ROB physical bank/subbank address boundary;
O8.3e bounds the ROB recovery scan, and O8.3f separates retained head
selection, banked payload read, and commit-driven pointer mutation. O8.3g adds
the PC-buffer banked address boundary without changing this coordinator's
token or common-fire protocol. O8.3h lets the PC owner join preparation only
after its bounded retained scan, while the coordinator continues to hold the
same plan. Physical PC metadata/read realization, combinational
commit/non-flush prefix discovery, occupancy/in-flight/PTag-aware dispatch
steering, safe-mode policy, and complete 2/4/6 timing closure remain separate
O8 packets.
