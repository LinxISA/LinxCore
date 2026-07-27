# OOO Production Bundles

## Source mapping

- Parameters: `chisel/src/main/scala/linxcore/ooo/OooParams.scala`
- Bundles: `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- Stage shell: `chisel/src/main/scala/linxcore/ooo/OooThreadStageBuffer.scala`
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
```

The tests cover 2/4/6 decode widths, 1/2/4 STIDs, exact field widths, three
architectural parent references, grant retention under backpressure,
per-STID cancellation, and simultaneous different-STID D2/D3/S1 residency.
