# OooPcBuffer

`OooPcBuffer` owns compressed program-counter bases for OOO. It accepts a
side-effect-free D3 preparation, publishes on the common S1 transaction, retires
only exact ROB-group prefixes, and applies only the exact retained recovery
plan. No other owner may infer PC-base age from a global index.

## Logical organization

The default configuration has 64 entries split into four fixed 16-entry STID
rings. Each STID owns independent head, tail, allocation epoch, used count,
current token, and current base. A public `PcBufferToken` contains:

- the global entry index;
- a byte offset, sufficient for 2/4/6/8-byte Linx instructions;
- an allocation epoch that rejects a stale token after ring reuse.

Consumers reconstruct `base + byteOffset` only after the complete token matches
the resident row. A stale epoch, a cross-partition index, or an invalid row
returns `readValid = false` and zero data.

## Physical address boundary

Canonical allocation/commit/recovery metadata is addressed as
`[stid][bank][row]`. Let `local` be the index within one STID partition:

```text
bank = local[log2(pcBankCount)-1:0]
row  = local[log2(pcEntriesPerStid)-1:log2(pcBankCount)]
```

The default is four banks by four rows per STID. `pcBankCount` is a power of two
and must evenly divide each STID partition. `pcWritePorts` and
`retireGroupWidth` must not exceed the bank count, so an ordered allocation or
retirement prefix cannot address one bank twice. Every prepare, publish,
commit, recovery, and consumer read goes through the same `rowAt(stid, local)`
decoder; the logical ring order and token encoding do not depend on the number
of banks.

Consumer base payload is physically separated from that metadata. The minimal
`OooPcReadEntry` contains only
`{valid, STID, index, allocationEpoch, base}` and is stored as
`[replica][stid][bank][row]`. The default six readyless ports use three
replicas, with compile-time mapping `replica = port % 3`; ports 0/3, 1/4, and
2/5 are therefore the two fixed reads of replicas 0, 1, and 2. Parameters must
evenly distribute every logical port and permit no more than two ports per
replica. There is no runtime read arbitration or invalidation.

Allocation publication writes the new payload to every replica on the common
S1 fire. Ordered commit free and exact recovery free clear every replica on the
same owner mutation. Metadata-only changes remain canonical and are not
replicated. `dontTouch` preserves the three explicit arrays in generated RTL;
the structure is a replicated register/array boundary, not yet a claim about a
specific foundry SRAM wrapper or its read latency.

## Allocation, commit, and recovery

D3 computes base demand, row targets, byte offsets, predicted-taken/precise-trap
close events, and implicit close ownership without mutation. At most
`pcWritePorts` new bases may be prepared. Only the shared `publishFire` installs
rows and advances the selected STID tail/current state.

Commit validates a wrap-aware ROB-group prefix against the row token,
`nextCommitRobGroup`, live-reference count, and exact close owner. Only
`commit.fire` advances the per-row commit cursor, clears a releasable head
prefix, and moves the partition head.

Recovery consumes the grouped ROB's authoritative killed suffix. Prepare
captures that immutable plan and checks a parameterized
`pcRecoveryScanGroupsPerCycle` slice on each cycle. The default four-group
slice takes 16 scan cycles for a 64-group ROB window. The walk accumulates
per-row killed-reference counts, prior ROB keys, close-owner repair, allocated
row clears, restored tail, and restored current base entirely in private
state. A changed plan rejects, and prepare-valid withdrawal discards the
partial walk without physical undo. The target STID is fenced while other
STIDs may still publish or commit.

After the complete walk validates, the prepared result and row-repair masks
remain stable until the common recovery fire. Only that fire decrements
surviving row references, reopens a surviving close owner, clears allocated
suffix rows, and restores tail/current state. Apply consumes retained masks;
it does not rebuild a full killed-group-by-PC-row comparison network.

## Reference boundary

`Documents/a.txt` sections 6.6.3–6.6.8 motivate three D3/S1 writes, six logical
reads, fixed thread partitions, D3 address generation, S1 data write, I1 read,
I2 reconstruction, and conservative free-space reporting. LinxCore keeps those
useful timing and port-shape ideas but uses four STIDs, byte offsets, exact
ROB-group ownership, and epoch-qualified recovery rather than importing the
reference design's thread count, reset-only flush policy, or pointer shortcuts.
LinxCoreModel does not own a physical PC-buffer topology;
`tools/LinxCoreModel/model/bctrl/spe/SPEROB.cpp::{allocROB,commit,flush}` supplies
only the program-order allocation/retirement/recovery lifecycle comparison.
It is not evidence for a bank count or port realization.

## Verification

`OooPcBufferSpec` covers:

- 2/4/6/8-byte reconstruction and stale-token rejection;
- predicted-taken, offset-overflow, explicit close, and implicit close;
- exact partial commit, skipped/duplicate group rejection, and epoch wrap;
- exact suffix recovery, fixed scan latency, stale recovery rejection, and
  stable prepared retention;
- partial-scan abort, retained-plan drift rejection, target-STID fencing,
  peer-STID publication, and a non-default two-group scan slice;
- consecutive bases across banks and rows, including six simultaneous reads
  where two ports target different rows of the same bank;
- three fixed two-read replicas, all-replica allocation visibility, ordered
  commit clear, exact recovery clear, and surviving-row coherence;
- 1/2/4-bank elaboration paired with 2/4/6 decode widths.

`OooRobBrobPcCoordinatorSpec`, `OOORecoverySpec`, `OOOIntegrationSpec`, and
`OooFrontendIfuRecoveryIntegrationSpec` cover
the common O3 publication/commit/recovery transaction and both adjacent
IEX and real-IFU recovery boundaries.

## Remaining timing work

The recovery compare/read depth is bounded by the configured scan slice, apply
does not recreate the killed-window CAM, and the six logical reads now have an
explicit three-replica/two-read structure. This is still not a final physical
timing claim: common apply may fan out to multiple metadata and read-payload
rows, a foundry macro wrapper may require an I1 request/I2 response contract,
and default-width synthesis timing has not selected those macros. The grouped
ROB's separate commit prefix discovery and dispatch cost steering also remain
O8 work.
