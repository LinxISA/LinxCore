# PRename

## Purpose

`PRename` is the absolute-index P-register rename
owner. Each STID owns a 24-entry speculative map (SMAP), a 24-entry committed
map (CMAP), and one logically ordered P MapQ. Prepare resolves sources and
performs oldest-to-youngest same-bundle RAW/WAW forwarding without mutation.
Only the common O3 publication fire updates SMAP and appends MapQ rows.

## Physical MapQ layout

The logical queue contract is independent of its physical layout:

```text
logical index q
  subbank = low log2(pMapQSubbankCount) bits of q
  row     = remaining high bits of q
```

Storage is `[stid][subbank][row]`. The default `pMapQSubbankCount=2` maps even
and odd logical indices to separate subbanks. Both the depth and subbank count
are powers of two, and the subbank count must evenly divide the per-STID depth.
Counts of 1, 2, and 4 elaborate; two is the default.

There is still exactly one logical `mapQHead`, `mapQTail`, and `mapQCount` per
STID. Every stored row retains its full logical `mapQIndex`. Publication,
commit validation, commit drain, recovery tail drain, and survivor replay all
use one logical-index decoder and still compare the row's stored index. A
physical bank choice therefore cannot reinterpret age, ownership, or wrap.

Commit and killed-tail recovery use explicit registered read stages. A commit
read slice contains at most
`min(pTagReturnWidth, pMapQSubbankCount)` consecutive logical rows, so one
slice reads at most one row from each configured low-index subbank. The slice's
head, count, complete rows, and therefore its even/odd selection are retained
before `ptagReturn` becomes valid. `CMAP`, row-valid bits, `mapQHead`, and
`mapQCount` change only when that retained return fires. A following slice
cannot read until the intervening return state completes, so no subbank is read
in consecutive cycles. Recovery similarly retains one exact tail row and its
logical index before returning the killed current PTag and moving `mapQTail`.

## Commit and recovery invariants

- Commit accepts only the exact dense MapQ-head prefix described by the
  retained ROB batch.
- Commit and recovery are mutually exclusive for the selected STID.
- Commit returns each previous PTag in logical order before common physical
  deallocation.
- Neither commit nor recovery mutates MapQ pointers in its entry-read cycle;
  retained PTag returns remain stable under arbitrary backpressure.
- Recovery drains killed rows from the exact logical tail, returns each killed
  current PTag, copies CMAP to SMAP, and replays every surviving row from head
  to tail.
- Wrong generation, member, transaction, row index, or mapping-chain evidence
  rejects or asserts before mutation; unrelated STIDs remain independent.

O8.3b established the physical storage partition. O8.3c adds the registered
read/return states that split entry read and pointer update across cycles.
Default-width post-elaboration synthesis timing remains a separate gate; the
structural split does not by itself claim a target frequency.

## Reference evidence

`Documents/a.txt`, section 6.2.6.3, motivates even/odd MPQ subbanks to break a
one-cycle flush/commit pointer loop. The local LinxCoreModel
`model/bctrl/spe/GPRRename.cpp` provides functional evidence for SMAP/CMAP,
MapQ capacity, commit, and flush behavior through `Insert2MapQ`, `RetireBlock`,
and `Flush`. Its search-allocated vector and numeric RID/BID ordering are not
the physical contract; Chisel retains the stronger ordered-ring and
exact identity rules.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooParams
bash tools/chisel/run_chisel_tests.sh --only RENUSpec
bash tools/chisel/run_chisel_tests.sh --only RENUAtomicSpec
bash tools/chisel/build_chisel.sh
```

Directed coverage observes the empty read cycle before every retained commit
or recovery return, holds a registered return under backpressure, and proves
that pointer/mapping state changes only on the return handshake. It also
advances the logical queue to index three, publishes a
two-row transaction across indices `3 -> 0`, kills the wrapped younger row,
replays the surviving index-three prefix, and commits it. Elaboration covers
instruction widths 2/4/6 together with subbank counts 1/2/4. Existing tests
retain same-bundle RAW/WAW, capacity, backpressure, exact commit rejection,
PTag return widths, multi-group RID wrap, and full recovery behavior.

## Remaining work

O8 still owns default-width synthesis timing, PC metadata/read macro
realization, commit/non-flush prefix timing, dispatch
occupancy/in-flight/PTag-aware cost steering, and safe-mode policy. O8.3f has
closed the separate retained ROB head-token and payload-read boundary; O8.3g
has closed the PC bank-address transform while preserving six logical reads;
O8.3h has bounded PC recovery compare/read depth and retained apply masks.
