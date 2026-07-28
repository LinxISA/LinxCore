# OooS1GroupedRob

`OooS1GroupedRob` owns published grouped-ROB state, exact member completion,
non-flush evidence, retained commit batches, and exact suffix recovery for all
STIDs.

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

Commit eligibility and non-flush prefix discovery still inspect live ordered
rows combinationally. O8.3f separates selection, payload read, and pointer
mutation; it does not claim closure of that prefix-discovery path. PC-buffer
banking is also a separate O8 packet.

## Verification

`OooS1GroupedRobSpec` covers:

- atomic publication and retained ordered commit;
- separate head-token and payload-read stages, stable retained output, and
  same-STID recovery fencing across both stages;
- stale completion rejection and exact non-flush evidence;
- wrapped head generation and exact suffix recovery;
- multi-cycle recovery prepare, private partial-scan abort, request-drift
  rejection, non-default two-group slices, and peer-STID publication;
- independent 2/4/6 decode width elaboration; and
- three legal sequential publications spanning bank 0 through bank 7 and
  then the odd subbank, followed by exact completion reads at RID slots 0 and
  8.

The lower ROB/BROB/PC coordinator, D3-to-S1 integration, and upper O3
coordinator suites verify that the physical layout and added read stage remain
behind the existing common publish, commit, and recovery fires.

At the current generated-test boundary, the four-wide eight-group module is
107,407 SystemVerilog lines and the sixteen-group module is 217,635 lines.
This remains approximately linear with depth. These line counts are not an
area-reduction claim; O8.3e bounds per-cycle recovery read/compare depth and
O8.3f breaks the selected-head/payload/pointer path with retained state.
