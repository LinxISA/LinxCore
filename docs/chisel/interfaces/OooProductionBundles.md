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
will accept. BROB/PC commit authorization is not bypassed:
`OooRobBrobPcCoordinator` holds external `commit.valid` until every owner has
validated the retained batch, then lets all four owners fire atomically.

## Production BROB

`OooProductionBrob` is the sole native block-order owner for production OOO.
Each STID has an independent 256-entry default ring. The architectural BID is
only the ring slot; `BrobPointer.generation` is separate wrap state and neither
allocation nor commit uses unsigned BID magnitude as age.

The module views the retained D3 reservation without mutating state. It assigns
every active ROB group one valid BROB pointer, allocates a new pointer only for
`boundaryStart`, keeps body groups on the current pointer, and rejects a group
after `boundaryStop` unless it opens a new block. `publishFire` is the same
all-or-none S1 event used by grouped ROB publication. It atomically installs
new block entries, aggregates all group references, advances the native BID
tail/generation, and updates the selected STID's current block.

An in-body BSTART opens a new BID and implicitly closes the previous BID. The
old entry records the new-BID boundary group's exact `RobGroupKey` as its close
owner. An open block whose own groups have already committed therefore remains
resident until that close owner commits; a speculative BSTART cannot free the
old entry early. Explicit BSTOP uses its own group as close owner.

BROB consumes the retained `OooRobCommitBatch`. It validates the release header
against `groups(0).key`, every group/BROB generation and STID, same/next-block
ordering, close transitions, and per-entry live-group counts. Every entry owns
an exact `nextCommitRobGroup` cursor; a retained batch must begin at that cursor
for each BID it enters and must carry wrap-aware consecutive RID keys. Partial
retire advances the cursor, so skipped or duplicate groups cannot satisfy a
later count-only commit. Only `commit.fire` decrements references and frees a
contiguous exact head prefix.
Malformed retained commit reports a typed reject but does not starve S1
prepare. Exact same-STID commit has one-cycle priority over publication;
different STIDs remain concurrent. Combining non-overlapping same-STID
head/tail updates is a later port-throughput optimization, not a correctness
dependency.

## Production PC-base buffer

`OooProductionPcBuffer` is the sole production PC-base owner. The default
64 rows are four fixed 16-row STID partitions. Each partition has independent
head, tail, allocation epoch, live count, and current-base state; PC-buffer age
is never inferred by comparing a global index.

The module observes the retained D3 reservation without mutating state. It
collects every architectural parent PC assigned to each ROB group, proves the
inverse relation between `logicalUopMask` and `uopGroupIndex`, and emits an
exact token for every group and every active parent. A token contains the
global row index, byte offset, and allocation epoch. Byte offsets reconstruct
2/4/6/8-byte instruction PCs without assuming four-byte instruction length.

A new base is prepared when no current base exists, the group PC range does
not fit the configured byte-offset width, or the group carries a precise trap.
Predicted-taken release and precise-trap groups close the selected base. A new
base allocated while an older base remains current records the new group's
exact `RobGroupKey` as the older base's implicit close owner. Admission is
all-or-none and rejects capacity overflow, more than the configured three
base writes, occupied targets, malformed group/parent mappings, or a stale
current token. Only the shared S1 `publishFire` installs bases and advances
partition state.

Commit accepts only an exact, wrap-aware consecutive ROB-group prefix whose
PC tokens match live row index and allocation epoch. Every row tracks its
`nextCommitRobGroup`, live group count, and close owner. Partial retirement
advances the cursor; a base is freed only after all of its groups and exact
close owner have committed. Six combinational read ports validate the complete
token before returning `base + byteOffset`; stale epochs and cross-partition
tokens return invalid with no data owner mutation.

## O3 ROB/BROB/PC coordinator

`OooRobBrobPcCoordinator` is the terminal O3 owner composition. It retains D3
reservations, asks BROB and PC for side-effect-free bindings, adds the next
per-ROB-slot resident generation, and presents the fully bound grouped-ROB
publication to later RENU/dispatch owners. `preparedValid` is an immutable
view; `publishPermit` may be asserted only after those later owners can join.
The single `publishFire` publishes D3, ROB, BROB, and PC state together. A
malformed owner preparation or blocked grouped ROB produces no partial state.

The grouped ROB is the retained commit source. D3 release, BROB commit, and PC
commit readiness are exact, side-effect-free checks against that same batch.
External backpressure leaves the ROB batch and every other owner unchanged.
Only the terminal external handshake asserts the three internal valids, so
ROB row removal, D3 capacity release, BROB retirement, and PC retirement occur
on one common fire. Same-STID publish/commit is serialized when both would
write the same ring state; a different STID may publish while another commits.

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

`OooPTagStagingPool` is the first O4 owner. At reset, 96 tags are owned by the
per-STID identity CMAP mappings and 32 tags are initially free. All 128 tags are
in exactly one of the initial-committed set, shared banked free list, compact
staging rows, per-STID provisional D3 leases, or published-live set. Replacing
an identity mapping returns that exact generation-zero tag into the ordinary
free/staged lifecycle; identity is an initialization state, not a permanently
reserved tag class. D2 refill may select from the free list, but D3 can claim
only already-staged tags. Transaction ID rotates the preferred starting bank,
but each destination falls forward to the first bank with remaining staging
credit after older destinations in the same bundle. Therefore skewed committed
identity returns cannot permanently strand free tags behind one exhausted
preferred bank. A multi-destination claim is all-or-none and carries exact
`{ptag, bank, allocationGeneration}` tokens; a stale generation cannot return a
reissued tag.

Claim moves the complete selected transaction into a stable per-STID lease.
Exact cancellation returns only that lease, while the common S1 publication
event moves it to published-live ownership. Exact batched return is Decoupled
and validates count, range, uniqueness, bank, generation, and current live
ownership before changing any bit. A cycle-by-cycle checker proves the entire
128-tag namespace remains in exactly one lifecycle location.

`OooProductionPRename` is the O4.2 P-map owner. It consumes the immutable O3
prepared publication together with the matching retained PTag lease. For every
active P source it reads the selected STID's 24-entry SMAP and then applies all
older destinations in the same transaction from oldest to youngest. A WAW
destination therefore records the immediately preceding mapping, including an
older destination in the same bundle. Each new payload preserves PTag
generation and producer transaction identity; `producerBindingValid` remains
false until O5 supplies the exact IQ reservation.

Prepare is side-effect free. `OooPMapQEntry` records exact ROB member identity,
transaction/uop/destination order, old mapping, and new mapping. The per-STID
MapQ is an ordered ring, not a search-allocated collection. SMAP and MapQ mutate
only on the common O3 publication fire. `OooO3RenameCoordinator` joins D3 and
PTag claim on one reserve handshake, then joins ROB/BROB/PC publication, PTag
publication, SMAP update, and MapQ insertion on one terminal fire.

O4.3 adds the P architectural commit walk. Every physical ROB group carries its
exact `pMapQRows` obligation. `OooProductionPRename` validates the retained ROB
batch against the dense MapQ-head prefix using RID/native-BID/BROB/resident
generations, transaction ID, member index, uop membership, queue index, and
old/new mapping chain. It then drains at most `pTagReturnWidth` rows per cycle.
Each row advances CMAP oldest-to-youngest and returns its exact previous PTag,
including a replaced reset identity tag. Return backpressure holds the row,
CMAP, MapQ head, and token stable.

ROB/BROB/PC/D3 deallocation remains retained until every MapQ row and old-PTag
return has completed. The final common commit fire only releases those physical
owners; it cannot repeat the commit walk. A younger provisional row that has
not reached a complete prepared view never blocks older commit, because it may
need capacity released by the walk. If its prepared valid was already exposed,
ready/valid retention requires that exact row to publish before same-STID commit
starts. During an active walk, a previously retained but unprepared same-STID
row remains immutable and resumes afterward; readiness-aware D3 selection does
not choose a new row from that STID and may choose another STID when no older
retained selection is already exposed. Full downstream-readiness-aware per-STID
arbitration remains an O5 dispatch integration obligation. External PTag return
stays closed until O6 supplies recovery authority. CMAP-to-SMAP survivor replay
and recovery are therefore still open, but ordinary P architectural commit is
no longer a sealed seam.

## T/U sequential rename boundary

`OooProductionTURename` is the O4.4 local-register owner. T and U have separate
per-STID sequence tails, physical-tag cursors, capacity counters, MapQ rings,
and provisional leases. A relative source resolves `tail - (relativeIndex+1)`
in its own namespace. The D3 preview walks expanded uops oldest-to-youngest, so
a younger source can resolve an earlier destination from the same bundle before
either has changed persistent state. Source underflow, demand mismatch, local
MapQ/physical capacity pressure, or an existing same-STID lease rejects the
whole reserve.

The O3 seam uses `OooTUPublicationRequest`, not the complete
`OooO3PreparedPublication`. It contains exact PE/STID/epoch/transaction
identity and, per active uop, only `RobMemberKey` plus local source/destination
shape. This keeps T/U ownership independent of P decode payload, CTU parents,
prediction, and PC tokens while still proving that every published row belongs
to the retained ROB member. The lease retains the D2-known uop mask, exact
group key, and first member index; publication must match those fields. Native
BID/BROB and resident generation are S1-assigned fields and therefore come only
from the coordinator's immutable O3 prepared binding, rather than from an
independent T/U input. `OooTURenamePreparedTransaction` returns the
pre-destination T/U sequence snapshots, resolved local sources, destination
mappings, and exact MapQ rows used by later dispatch/recovery owners.

`OooO3RenameCoordinator` gates D3 admission on both PTag and T/U preparation,
cancels both leases by STID, and requires both P and T/U publication views
before asserting the shared permit. One terminal fire therefore publishes all
ROB/BROB/PC/P/T/U owners.

O4.4.2 adds `OooProductionTURetire` as the separate retire-source and relation-
CMAP owner. `OooTURetirePublication` retains one exact source per logical uop,
including no-destination rows, plus its pre-destination T/U sequences, local
destinations, block-last bit, and optional implicit `closeBefore` pointer. A
retained `OooRobCommitBatch` is accepted only when each group and each logical-
uop bit matches exactly at the per-STID source head.

The owner emits serialized `OooTURetireCommand` operations in T-before-U
pre-release, mark, and pressure-release order. `OooProductionTURename` validates
the full local sequence generation, exact ROB member, namespace, and physical
deallocation head before mutating its MapQ. After exact-block relation cleanup,
`OooTULocalBlockCommit` releases only the retired MapQ head prefix whose native
BID and BROB generation match. P and T/U commit owners first expose
side-effect-free `commitStartReady` probes; the coordinator starts both
atomically and withholds common ROB/BROB/PC deallocation until both are ready.

O4.4.3a establishes the common killed-suffix authority without yet exposing a
partial recovery through the O3 coordinator. Every retire source now also
retains publication epoch and the number of P MapQ destinations owned by that
logical uop. `OooRenameRecoveryRequest` names one exact `ExactRecoveryKey` and
states whether the trigger itself is killed. `OooProductionTURetire` scans the
selected STID's complete source ring read-only, requires exactly one match, and
then emits `OooRenameRecoverySource` rows youngest-to-oldest. It removes a
source row only when that Decoupled transfer fires. Missing, stale, malformed,
or ambiguous authority produces a diagnostic and zero ring mutation.

Commit has priority when commit and recovery are presented together. Once a
recovery is captured, commit and publication for that STID wait until the
suffix and all downstream owners finish; unrelated STIDs may still publish.
Transaction ID zero is legal. Native BID is never ordered numerically: suffix
membership comes only from exact source-ring position after member, native-BID,
BROB/resident generation, transaction, and epoch matching. P replay and killed
PTag return plus T/U MapQ/cursor rollback remain O4.4.3b/c work, so the O3
recovery input stays sealed until all owners can join one atomic transaction.

D3's `planStale` preview has priority over P/T/U resource readiness. An obsolete
plan is consumed even if its obsolete local source underflows, but the stale
fire suppresses both PTag and T/U claims. Conversely, a valid same-STID reserve
may replace the lease being published in the same cycle. Its preview includes
the outgoing lease's T/U counts, sequence advance, physical-tag advance, and
source bypass, so capacity is never borrowed twice and the new lease observes
the just-published local destinations.

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
bash tools/chisel/run_chisel_tests.sh --only OooProductionBrob
bash tools/chisel/run_chisel_tests.sh --only OooD3S1BrobIntegration
bash tools/chisel/run_chisel_tests.sh --only OooProductionPcBuffer
bash tools/chisel/run_chisel_tests.sh --only OooRobBrobPcCoordinator
bash tools/chisel/run_chisel_tests.sh --only OooPTagStagingPool
bash tools/chisel/run_chisel_tests.sh --only OooProductionPRename
bash tools/chisel/run_chisel_tests.sh --only OooProductionTURename
bash tools/chisel/run_chisel_tests.sh --only OooProductionTURetire
bash tools/chisel/run_chisel_tests.sh --only OooO3RenameCoordinator
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
The BROB tests cover first-block admission, same-BID multi-group aggregation,
explicit and implicit close ownership, empty-open-block retention, malformed
commit isolation, release-header consistency, 2/4/6 decode versus four-group
retire, four-STID isolation, and native BID/generation wrap. The three-owner
integration test proves D3, grouped ROB, and BROB publish and retire on the same
terminal transactions.
The PC-buffer tests cover byte-granular reconstruction for 2/4/6/8-byte
instructions, offset overflow, predicted-taken close, implicit close ownership,
three-write admission, malformed uop/group inverse mapping, skipped/duplicate
ROB-group rejection, four fixed STID partitions, allocation-epoch wrap, stale
read rejection, and 2/4/6 decode widths.
The O3 coordinator tests cover retained prepare views, publication backpressure,
one common publication/commit fire, exact resident-generation binding, PC read
tokens, malformed-PC zero-mutation rejection, commit retention, different-STID
commit-plus-publish concurrency, and 2/4/6 decode widths.
The PTag staging tests cover reset ownership, balanced bank refill, exact D3
lease retention/cancel, per-STID isolation, malformed demand, publish/return,
duplicate and stale-generation rejection, complete speculative exhaustion with
zero-mutation backpressure, lifecycle conservation, and 2/4/6 decode widths.
The P-rename tests cover bundle-wide RAW/WAW, exact lease rejection, ordered
MapQ publication, per-STID capacity, retained CMAP/old-PTag commit walks,
wrapped two-group commit validation, and 2/4/6 elaboration. The T/U tests cover
same-bundle relative bypass, source underflow, exact cancel/publication,
D2-known member/uop-mask mismatch, same-cycle publish/reserve replacement with
outgoing-lease bypass, four-STID isolation, exact wrap-generation rejection,
physical-tag reclamation, and post-clean block-prefix release. The retire-owner
tests cover no-destination block-last retention, T-before-U draining, oldest-
relation pressure release, malformed STID/BROB-generation rejection, and exact
source-head conservation. The O3 rename integration also proves stale-plus-
T/U-underflow consumption with zero lease mutation, common P/T/U/ROB
publication, and shared P/T/U commit completion before physical deallocation.
