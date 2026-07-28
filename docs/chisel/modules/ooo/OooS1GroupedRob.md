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

The retained `commitRow` keeps the public commit transaction stable under
backpressure, but commit eligibility and non-flush prefix discovery still read
live rows combinationally. A later O8 packet must register the head
read/selection state and prove that pointer updates depend only on retained
state. The recovery scan closes one full-window read/CAM path; it does not by
itself close the two-cycle entry-read-to-pointer retirement loop.

## Verification

`OooS1GroupedRobSpec` covers:

- atomic publication and retained ordered commit;
- stale completion rejection and exact non-flush evidence;
- wrapped head generation and exact suffix recovery;
- multi-cycle recovery prepare, private partial-scan abort, request-drift
  rejection, non-default two-group slices, and peer-STID publication;
- independent 2/4/6 decode width elaboration; and
- three legal sequential publications spanning bank 0 through bank 7 and
  then the odd subbank, followed by exact completion reads at RID slots 0 and
  8.

The lower ROB/BROB/PC coordinator and upper O3 coordinator suites verify that
the physical layout remains behind the existing common publish, commit, and
recovery fires.

At the current generated-test boundary, the four-wide eight-group module is
107,178 SystemVerilog lines and the sixteen-group module is 217,545 lines.
This remains approximately linear with depth, but both are larger than O8.3d
because the exact recovery plan is now retained. These line counts are not an
area-reduction claim; the closed claim is the bounded per-cycle recovery read
and compare depth.
