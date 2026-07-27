# OOO Production Bundles

## Source mapping

- Parameters: `chisel/src/main/scala/linxcore/ooo/OooParams.scala`
- Bundles: `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- Stage shell: `chisel/src/main/scala/linxcore/ooo/OooThreadStageBuffer.scala`
- Generated recipe table: `chisel/src/main/scala/linxcore/ooo/OooOpcodeRecipeTable.scala`
- Canonical D1: `chisel/src/main/scala/linxcore/ooo/OooD1Decode.scala`
- Cross-cycle fusion: `chisel/src/main/scala/linxcore/ooo/OooD1FusionHistory.scala`
- Tests: `chisel/src/test/scala/linxcore/ooo/OooParamsSpec.scala`
- Tests: `chisel/src/test/scala/linxcore/ooo/OooBundlesSpec.scala`
- Tests: `chisel/src/test/scala/linxcore/ooo/OooThreadStageBufferSpec.scala`
- Contract IDs: `LC-IF-CHISEL-OOO-001`, `LC-MA-PIPE-001`,
  `LC-MA-ROB-001`

## Purpose

This family is the production packet boundary for `LinxCoreOoo`. It is
independent of the four-wide packet-window `InterfaceParams` compatibility
family and separates instruction-decode, expanded-uop, rename, dispatch, and
retire widths.

The default product is four STIDs with independent retained D2/D3/S1 rows.
Each stage grants at most one STID per cycle; different stages may grant
different STIDs concurrently. Decode widths 2, 4, and 6 are required
elaboration points.

## Identity domains

- `NativeBid` is exactly the per-STID BROB slot index. With 256 BROB rows it is
  eight bits.
- `BrobPointer` carries native BID and a separate generation. Generation never
  widens or replaces BID.
- `RobGroupKey` is `{PE, STID, ridSlot, ridGeneration}`.
- `RobMemberKey` adds native BID, BROB generation, member index, and resident
  generation for exact terminal events.
- `CanonicalUopIdentity` retains up to three ordered architectural parents so
  `BSTART + carrier + BSTOP` fusion does not erase trace, exception, or
  single-step identity.

No consumer may compare native BID or RID by unsigned magnitude to infer age.
Age and kill membership come from the selected STID's ROB/BROB owner.

## Stage transactions

| Bundle | Ownership |
|---|---|
| `OooD2VirtualPlan` | Preview-only RID grouping and complete resource demand; no physical mutation |
| `OooD3Reservation` | Exact provisional ROB/BROB/PTag/PC/IQ claims plus physical rename |
| `OooS1Publication` | Atomic grouped-ROB, speculative-map, and IEX speculative-slot publication |
| `ExactRecoveryKey` | Exact member-qualified retained recovery request |
| `NonFlushWindow` | ROB-owned `{STID, headRobGroupKey, prefixCount, epoch}` safe prefix |

`InstructionDemand` exposes independent counts for instructions, uops, ROB
groups, BROB slots, PC writes, P/T/U destinations, MapQ rows, IQ class/bank
writes, and load/store IDs. No downstream owner may infer one resource demand
from decode width alone.

Demand field widths are sized from the worst-case input recipe rather than the
available resource. This is required for D2 to represent and reject a
six-instruction window containing 12 pair destinations, dispatch writes, or
memory requests instead of truncating the demand to the eight-wide capacity.

## O2 canonical D1

`OooRawInstructionGroup` is a dense same-PE/STID/epoch prefix of fixed-64-bit
containers. Each `OooDecodedUop` preserves its ordered architectural parents,
generated recipe, up to four sources, up to two destinations, immediate,
boundary target, and precise-trap attribution. D1 allocates no RID, BID, PTag,
PC-buffer row, or IQ state.

`OooD1Decode` performs generated most-specific-mask decode and uses the older
frontend decode leaf only as a temporary operand/immediate oracle. Production
pair-memory overlays follow QEMU decodetree fields and do not inherit the
reduced three-source/two-destination limitations. Runtime register aliases are
classified into P, T, and U before D2 resource preview.

`OooD1FusionHistory` owns one retained fusion-eligible uop per STID. It delays
publication until the architectural successor or end-of-stream is known,
which permits cross-cycle BSTART-forward and BSTOP-backward fusion without
patching a published ROB member. Recovery cancellation is per STID, and a
capacity-conflicting terminal window uses the documented standalone-boundary
fallback while retrying the input unchanged.

## IFU raw ingress and CTU diversion

`OooIfuRawIngress` is the production width adapter. It consumes the existing
fixed-four-wide `D1InstructionGroup`, keeps a power-of-two raw reservoir per
STID, and emits the selected STID as a dense 2/4/6-wide
`OooRawInstructionGroup`. It copies PE/STID/instruction/transaction/fetch,
checkpoint, key epoch, prediction epoch, PC, raw bits, and length exactly. It
does not decode instructions or change the IFU cacheline/fetch geometry.

The reservoir supports same-bank enqueue/dequeue, partial prefixes, six-wide
gather, stable backpressure, targeted exact pruning, and four-STID isolation.
A canonical flush is a one-cycle publication barrier: only the addressed bank
is mutated, and every unaffected bank resumes with the same head on the next
cycle. `OooIfuD1Ingress` composes this reservoir with
`OooD1ProductionDecode`; its thread hint scans IFU banks while OOO independently
selects the STID presented to D1.

`OooD1DecodedPacket` carries `ctuParents` and `complexParents` alongside their
masks. Every diverted lane therefore retains the exact raw parent and complete
prediction record needed by the external CTU/complex owner. Masks are never an
authority to reconstruct identity. CTU canonical-child reinsertion and its
retained expansion lease remain an O7 owner; CTU may not allocate RID/BID/PTag
or mutate ROB, RF, IQ, LSU, or memory directly.

## O3 virtual ROB grouping

`OooD2GroupPlanner` converts one canonical D1 packet into an
`OooD2GroupedTransaction`. It is combinational and receives only snapshot
copies of the selected STID's RID tail slot, RID generation, tail epoch, and
next transaction ID. It cannot set a ROB/BROB/PC valid bit or advance an
allocator pointer.

Grouping is older-first. `uopGroupIndex` and `uopMemberBase` assign every
logical uop to a virtual RID and to the base member used by its late-split
children. A group starts before a new block and after a prior block stop,
precise trap, or predicted-taken PC-release boundary. The independent limits
are four trace-owning architectural parents and 12 planned physical members by
default. Fused parents count individually; non-trace internal children do not
consume another architectural-parent slot.

Each `OooRobGroupPreview` carries the wrap-aware exact `RobGroupKey`, logical
uop mask, physical member count, architectural parent count, boundary summary,
precise-trap summary, and ordered PC-base release obligation. The plan records
the input tail epoch; D3 must reject it if any later allocator event changes
that epoch. CTU/complex diversion packets are rejected at this owner because
they must first return as validated canonical children.

`OooD2ProductionStage` connects the combinational planner to
`OooD2ThreadStageBuffer`. The buffer holds one complete immutable preview per
STID and shares one fair D2→D3 grant. A blocked grant retains both selected
STID and payload. Other STIDs may fill their private rows while that grant is
blocked; targeted cancellation removes only the matching row. Live allocator
snapshots are not reread into a retained transaction, so D3 sees the original
tail epoch and can reject it if the physical tail advanced later.

`OooD3ReservationAllocator` owns provisional grouped-ROB capacity and the
reserved tail for each STID. It accepts a D2 transaction only when the captured
tail epoch, transaction identity, dense group mask, every group-valid bit, and
every sequential PE/STID/slot/generation key match live state. Stale or
malformed plans are consumed into a typed reject event with zero allocator
mutation. One provisional claim per STID may be canceled and rolled back
exactly; an S1 output handshake removes only the provisional marker while
retaining published capacity usage.

Claimed `usedGroups` and S1-visible `publishedGroups` are separate counters.
Release is authorized by exact `{first RobGroupKey, headEpoch, groupCount}` and
is bounded by independent `retireGroupWidth`, not decode width. The allocator
tracks head PE/STID/slot/generation/epoch; stale, duplicate, wrong-generation,
over-published, or over-bandwidth release requests report a reject and mutate
nothing. Exact release alone advances the head and returns capacity.

## S1 grouped ROB publication and completion

`OooS1GroupedPublicationRequest` is the atomic S1 envelope. It combines one
retained `OooD3GroupedReservation` with a valid-vector of physical group
bindings. Each binding carries the exact BROB pointer, optional PC-base token,
ROB resident generation, and any member bits that a later fast-resolve owner
has already completed. BROB, PC, rename, and IEX owners may prepare their
resources independently, but no binding is architecturally visible before the
single S1 publication handshake.

`OooS1GroupedRob` revalidates the dense group mask, plan/decoded PE/STID/epoch,
first RID, every consecutive slot/generation, member/parent bounds, binding
valid-vector shape, initial completion mask, and vacancy of every target row.
It writes all groups or none. A rejected or blocked request does not create a
partial ROB row.

Every physical row records its exact `RobGroupKey`, transaction/claim epoch,
BROB pointer, PC-base token, resident generation, group summary, and a dense
physical-member completion bitmap. Completion is a Decoupled terminal request
carrying `RobMemberKey`; PE/STID/RID generation, native BID, BROB generation,
resident generation, and member index must all match. Stale, duplicate, or
out-of-range completion is consumed into a typed reject and mutates nothing.

Commit scans only the exact per-STID physical head and captures an older-prefix
batch bounded by independent `retireGroupWidth`. The batch remains stable under
backpressure. Only `commit.fire` clears rows, advances the physical head and
epoch, and supplies the exact `OooRobGroupRelease` token that the D3 allocator
will accept. BROB/PC commit authorization is not bypassed: the eventual S1
coordinator may hold `commit.ready` until those owners can retire atomically.

## O1 stage shell

`OooThreadStageBuffer` holds one private transaction per STID and uses a fair
shared grant. A blocked output retains its selected STID and packet. A typed
per-STID cancel removes only the matching row.

`LinxCoreOooShell` composes three independent instances for D2, D3, and S1.
It is an executable staging/arbiter skeleton, not yet the production decode,
ROB, rename, or dispatch datapath. Later packets replace the minimal
`OooPipelineToken` payload with the typed stage transactions without changing
the stage-selection contract.

## PTag capacity note

Four STIDs require 96 committed PTag mappings before speculation. The default
128-entry physical namespace therefore guarantees only eight speculative tags
per STID. This is the minimum contract and a deliberate pressure point, not a
performance recommendation. Scale configurations should test 192/256 tags,
per-STID guarantees, and shared borrowing.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooParams
bash tools/chisel/run_chisel_tests.sh --only OooBundles
bash tools/chisel/run_chisel_tests.sh --only OooThreadStageBuffer
bash tools/chisel/run_chisel_tests.sh --only LinxCoreOooShell
bash tools/chisel/run_chisel_tests.sh --only OooOpcodeRecipeTable
bash tools/chisel/run_chisel_tests.sh --only OooD1Decode
bash tools/chisel/run_chisel_tests.sh --only OooD1FusionHistory
bash tools/chisel/run_chisel_tests.sh --only OooIfuRawIngress
bash tools/chisel/run_chisel_tests.sh --only OooIfuD1Ingress
bash tools/chisel/run_chisel_tests.sh --only OooD2GroupPlanner
bash tools/chisel/run_chisel_tests.sh --only OooD2ProductionStage
bash tools/chisel/run_chisel_tests.sh --only OooD3ReservationAllocator
bash tools/chisel/run_chisel_tests.sh --only OooS1GroupedRob
bash tools/chisel/run_chisel_tests.sh --only OooD3S1GroupedRobIntegration
```

The tests cover 2/4/6 decode widths, 1/2/4 STIDs, exact field widths, three
architectural parent references, grant retention under backpressure,
per-STID cancellation, and simultaneous different-STID D2/D3/S1 residency.
The O2 tests additionally cover every generated hardware rule stimulus,
16/32/48/64-bit decode, P/T/U aliases, pair-memory operands, precise faults,
12-count width-six demand, same/cross-cycle three-parent fusion, end-of-stream,
backpressure, per-STID history cancellation, and full-width fallback.
The IFU ingress tests additionally cover exact metadata transport, four-to-two
split, four-to-six gather, partial 2/4/6 prefixes, same-STID ordering,
four-STID hard-flush isolation, trigger-and-younger pruning, and exact CTU
parent delivery through decode/fusion.
The D2 tests cover one-group packing, explicit block splits, planned-member
overflow, PC-release closure, RID slot/generation wrap, member-base assignment,
and 2/4/6-width elaboration without physical state mutation.
The retained-stage tests cover concurrent private STID rows, stable shared
grant backpressure, selected-STID cancellation, and immutable tail-epoch
retention after the live allocator snapshot advances.
The D3 tests cover fresh provisional claim, stable S1 publication handoff,
stale/malformed plan zero-mutation rejection, provisional-only rollback,
published-capacity preservation, exact release, wrong-generation release,
over-retire-width release, and decode-width/retire-width independence.
The S1 grouped-ROB tests cover atomic multi-group publication, target
collision with no partial write, exact/stale/duplicate member completion,
retained older-prefix commit, RID generation wrap, four-STID isolation, and
2/4/6 decode widths with an independently configured retire width.
The D3/S1 integration test feeds the exact S1 commit release back to D3 and
proves both owners advance published/used occupancy and head epoch together.
