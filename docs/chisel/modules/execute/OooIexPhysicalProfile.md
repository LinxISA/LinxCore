# OooIexPhysicalProfile

## Purpose

`OooIexPhysicalProfile` is the elaboration-time source of truth for physical
issue residency, picker multiplicity, and execution-lane capabilities. It
prevents five different concepts from being collapsed into one dispatch
class or one numerical "domain":

1. the logical class assigned by generated decode metadata;
2. the class/bank rows retained by one residency owner;
3. the class/bank rows observed by one picker function;
4. the recipe capabilities admitted by that picker;
5. the execution lane receiving the selected transaction.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexPhysicalProfile.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexE1TransferFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexPhysicalProfileSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexAtomicReadArbiterSpec.scala`

## Formal topology

`OooIexLinxPhysicalProfile` defines twelve disjoint residency owners, fourteen
picker functions, fourteen execution lanes, and fourteen exact release ports.
Residency owner count is a storage/topology property; picker count is issue
bandwidth and must not be used to duplicate IQ rows.

| Residency owner | Logical residency | Picker functions | Declared capabilities |
|---|---|---|---|
| ALU0 | partition of ALU; lower-half STD banks | `alu0` | simple ALU, store data |
| ALU1 | partition of ALU | `alu1` | simple ALU |
| ALU2 | partition of ALU; lower-half SYS banks | `alu2` | simple/multi-cycle ALU, PAC, system |
| ALU3 | partition of ALU; upper-half STD banks | `alu3` | simple ALU, store data |
| ALU4 | partition of ALU | `alu4` | simple ALU |
| ALU5 | partition of ALU; upper-half SYS banks | `alu5` | simple/multi-cycle ALU, PAC, system |
| AGU0 | partition of AGU | `agu0-lda`, `agu0-sta` | load address, store address |
| AGU1 | partition of AGU | `agu1-lda`, `agu1-sta` | load address, store address |
| AGU2 | partition of AGU | `agu2-lda` | load address only |
| BRU0 | partition of BRU | `bru0` | branch |
| BRU1 | partition of BRU | `bru1` | branch |
| FSU0 | all FSU and CMD banks | `fsu0` | floating/vector, engine command |

Boundary metadata is fast-resolved and owns no physical IQ bank. For every
other generated class, the union of residency-owner masks covers every
configured bank exactly once. Picker projections may overlap only when their
declared capability sets are disjoint. Consequently the LDA and STA picker of
AGU0 or AGU1 can observe the same physical banks without being eligible for
the same row. ALU masks preserve two physical clusters; STD and SYS follow the
same lower/upper split. The profile requires at least eight even-numbered IQ
banks so both ALU clusters contain at least four banks.

## Capability boundary

Capability is retained as profile metadata rather than inferred from class.
This distinction is required because:

- AGU contains both LDA and STA recipes, but AGU2 cannot accept STA;
- ALU contains simple, multi-cycle, and pointer-authentication recipes, but
  only ALU2/5 accept the latter two;
- STD and SYS share physical ALU queues without becoming ordinary ALU
  operations;
- FSU and engine commands share one external selection domain but require
  different downstream execution behavior.

Generated recipe metadata now carries one capability requirement per dispatch
class. `OooIexCapabilityTopology` projects the static owner declarations into
a class-by-bank matrix for D3. Reservation considers only free banks whose
physical owner covers the recipe requirement. S2 stores the selected child's
requirement beside the compact scheduling state; S3 candidate creation,
retained-token claim, retry, and query all revalidate it against the static
domain capability. I2-to-E1 transfer performs the final check from the full
recipe before ownership and exact IQ release can fire.

All checks are fail-closed: a nonzero dispatch demand needs exactly one known
capability, and a picker must cover every required bit. Therefore
AGU2 cannot receive STA, and DIV/REM-class work can be reserved or selected
only by ALU2/5. Default constructors retain a permissive topology for focused
legacy unit harnesses; the formal profile exposes `capabilityTopology` and
`transferConfigs` for canonical integration.

The same separation now models picker multiplicity directly. AGU0/1 each have
independent load- and store-address pickers over one residency owner, whereas
AGU2 has only a load picker. The two typed pickers can issue together when
both have ready rows. DIV, PAC, and SYS remain independent physical resources
shared by ALU2/ALU5. Their I1 arbiter uses the separate execution-lane identity
without changing residency or picker ownership.

## Scaling the read boundary

The formal profile exceeds the earlier eight-picker limit. `OooParams` now
allows up to sixteen picker functions, and `OooIexAtomicReadArbiter` computes a
total priority rank followed by greedy complete-group packing. The network is
polynomial in picker count and preserves the prior lexicographic selection
contract without enumerating `2^N` subsets.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexPhysicalProfileSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAtomicReadArbiterSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexOldestReadyPickerSpec
bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1FabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferSlotSpec
```

The profile UT proves exact owner/picker/lane names, 12/14/14 counts, complete
disjoint residency coverage, capability-disjoint picker overlap, required
capability placement, representative cluster masks, invalid topology
rejection, and fourteen-picker transfer-fabric elaboration. Dispatch,
P1-fabric, and E1-slot tests prove recipe capability
steering, unsupported-row residency without claim, and final retained-transfer
rejection. The RF arbiter UT proves six oldest complete P-read groups win
from the configured contenders. Fabric and picker tests prove multi-class
admission, simultaneous AGU LDA/STA selection, wrong-bank rejection, and
oldest selection across ALU/STD projections.

## Remaining work

- connect shared-resource busy/latency and result-bus reservations;
- `OooIexPipeline` now instantiates the profile in the canonical static
  issue/read/E1 top; typed execution and LSU owners remain to be connected;
- synthesize the fourteen-picker rank, RF crossbar, release fanout, and picker
  matrix at the default geometry.
