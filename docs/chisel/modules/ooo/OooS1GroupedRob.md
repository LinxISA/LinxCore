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
the external protocol. The retained `commitRow` already keeps the public
commit transaction stable under backpressure, but commit eligibility and
non-flush prefix discovery still read live rows combinationally. A later O8
packet must register the head read/selection state and prove that pointer
updates depend only on that retained state. Subbanking alone is not evidence
that the two-cycle entry-read-to-pointer loop is closed.

Recovery also still scans the full logical partition in one combinational
prepare view. It remains exact and fail-closed, but synthesis closure requires
a retained bounded scan comparable to the IEX recovery scan.

## Verification

`OooS1GroupedRobSpec` covers:

- atomic publication and retained ordered commit;
- stale completion rejection and exact non-flush evidence;
- wrapped head generation and exact suffix recovery;
- independent 2/4/6 decode width elaboration; and
- three legal sequential publications spanning bank 0 through bank 7 and
  then the odd subbank, followed by exact completion reads at RID slots 0 and
  8.

The lower ROB/BROB/PC coordinator and upper O3 coordinator suites verify that
the physical layout remains behind the existing common publish, commit, and
recovery fires.
