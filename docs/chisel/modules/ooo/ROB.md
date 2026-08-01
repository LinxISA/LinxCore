# ROB

`ROB` owns published grouped-ROB state, exact member completion,
non-flush evidence, retained commit batches, and exact suffix recovery for all
STIDs.

Each physical group also retains the memory-order tail before and after the
group plus one after-snapshot per logical uop. These are full LSID/LID/SID
values, not queue indices. A partial-pivot recovery recomputes only the
surviving group's `memoryAfter` from its youngest surviving logical uop and
clears killed logical snapshots. The original `pivot.memoryAfter` remains in
the recovery plan so the memory-order allocator can prove its old live tail
before atomically installing the trimmed tail.

Runtime completion carries `{member, faultValid, faultCause}`. An exact fault
completion sets the member-completion bit, the matching fault bit/cause, and
the group precise-trap summary on one mutation. The commit row retains all
three views. Intra-group suffix recovery masks `faultedMembers` by the same
surviving-member mask as completion and zeroes every killed member cause.

## Physical row address

The logical RID slot remains the architectural ordering key. Physical storage
uses:

```text
rows[stid][bank][subbank][row]

bank    = RID slot low log2(robBankCountEffective) bits
subbank = the following log2(robSubbankCountEffective) bits
row     = the remaining high RID slot bits
```

The default 64-group partition uses eight banks, two even/odd subbanks per
bank, and four rows per subbank. Consecutive groups first spread across the
eight banks; the next eight groups select the other subbank. This is the ROB
counterpart of the odd/even pointer scheme in `Documents/a.txt`, section
6.4.1.2.1.

Small-depth tests retain the requested topology when it fits. Effective bank
and subbank counts shrink only when the complete logical partition is smaller
than the requested physical shape. Both publication width and retirement
width must fit within the effective bank count, so one ordered prefix cannot
request the same bank twice.

All owners use one slot decoder. Publication, completion, non-flush evidence,
commit, recovery planning, survivor rewrite, and killed-row invalidation cannot
reinterpret the same RID through different physical mappings.

## Timing status

O8.3d establishes the physical bank/subbank address boundary without changing
the external protocol. O8.3e removes the full-partition combinational recovery
view. `robRecoveryScanGroupsPerCycle` defaults to eight and shrinks with the
effective bank count for small ROBs. Recovery first walks every old-window
slice to validate physical identity and find exactly one pivot, then walks the
same bounded slices again to retain ordered killed-row records and the
surviving tail. The target STID is fenced throughout both passes; peer STIDs
continue, prepare withdrawal discards private scan state, and only the common
apply mutates rows or occupancy.

O8.3f closes the grouped-ROB head-read retirement loop described by the
reference design. The first commit stage arbitrates an eligible STID and
retains only the exact head token `{PE, STID, RID slot/generation, head epoch,
count}`. The following stage validates that immutable token and reads the
banked group payload into `commitRow`. Backpressure is therefore absorbed by
retained state, and only a fire of that retained row may clear groups or move
head/occupancy pointers. A same-STID recovery is fenced during both the token
and payload stages while peer STIDs remain independent.

O8.3i removes the four-STID full-window non-flush prefix network. One fair
retained scanner selects a dirty, non-interrupted STID and captures its exact
`{occupied, head slot/generation/epoch/PE, authorized prefix, window epoch}`.
It then checks at most `robNonFlushScanGroupsPerCycle` consecutive groups per
cycle, starting again at the head so an already-authorized prefix is also
revalidated. The default eight-group slice therefore needs one capture cycle
plus at most eight scan cycles for a 64-group window, and may stop early at the
first unsafe or malformed row.

Publication, exact evidence, commit, or recovery apply on the selected STID
invalidates the private snapshot and leaves that STID dirty for retry. An
interrupt or active recovery fence aborts only that target scan. The RR start
advances after completion or restart, so peer STIDs continue. No partial slice
is public: the ROB changes `prefixCount` only after the complete retained result
is still exact. Commit still subtracts its retired count immediately, recovery
still clears only the target window, and both events take priority over scanner
publication.

Commit eligibility still examines only the bounded retirement prefix
combinationally. O8.3i closes the unbounded non-flush discovery path, not
physical macro selection, default-width timing reports, or the remaining
commit-selection policy work.

## Verification

`OOORobCommitSpec` covers:

- atomic publication and retained ordered commit;
- separate head-token and payload-read stages, stable retained output, and
  same-STID recovery fencing across both stages;
- stale completion rejection and exact non-flush evidence;
- atomic bounded non-flush publication, non-default one/two-group slices,
  evidence-triggered restart, interrupt freeze, commit rebasing, and recovery
  reset;
- wrapped head generation and exact suffix recovery;
- multi-cycle recovery prepare, private partial-scan abort, request-drift
  rejection, non-default two-group slices, and peer-STID publication;
- independent 2/4/6 decode width elaboration; and
- three legal sequential publications spanning bank 0 through bank 7 and
  then the odd subbank, followed by exact completion reads at RID slots 0 and
  8.

`OOORecoverySpec` separately covers exact runtime fault/cause
retention at commit and killed-member fault/cause pruning in a surviving
intra-group recovery pivot.

The lower ROB/BROB/PC coordinator, D3-to-S1 integration, and upper O3
coordinator suites verify that the physical layout and added read stage remain
behind the existing common publish, commit, and recovery fires.

The O8.3i generated-test boundary is 107,286 SystemVerilog lines at eight
groups and 216,393 lines at sixteen groups, compared with 107,407 and 217,635
at O8.3f. The old `safePrefixByStid` structure is absent from generated RTL;
the retained scanner and snapshot registers are present. O8.3e bounds recovery
read/compare depth, O8.3f breaks the selected-head/payload/pointer path, and
O8.3i bounds non-flush prefix discovery. Line counts are structure evidence,
not an area or synthesis timing claim.
