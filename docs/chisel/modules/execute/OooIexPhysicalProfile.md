# OooIexPhysicalProfile

## Purpose

`OooIexPhysicalProfile` is the elaboration-time source of truth for physical
issue residency and pipe capabilities. It prevents three different concepts
from being collapsed into one dispatch class:

1. the logical class assigned by generated decode metadata;
2. the class/bank rows a physical domain may select;
3. the recipe capabilities accepted by the attached execution pipe.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexPhysicalProfile.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexE1TransferFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexPhysicalProfileSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexAtomicReadArbiterSpec.scala`

## Formal topology

`OooIexLinxPhysicalProfile` defines twelve independently selected domains and
twelve exact release ports:

| Domain | Logical residency | Declared capabilities |
|---|---|---|
| ALU0 | partition of ALU; lower-half STD banks | simple ALU, store data |
| ALU1 | partition of ALU | simple ALU |
| ALU2 | partition of ALU; lower-half SYS banks | simple/multi-cycle ALU, system |
| ALU3 | partition of ALU; upper-half STD banks | simple ALU, store data |
| ALU4 | partition of ALU | simple ALU |
| ALU5 | partition of ALU; upper-half SYS banks | simple/multi-cycle ALU, system |
| AGU0 | partition of AGU | load address, store address |
| AGU1 | partition of AGU | load address, store address |
| AGU2 | partition of AGU | load address only |
| BRU0 | partition of BRU | branch |
| BRU1 | partition of BRU | branch |
| FSU0 | all FSU and CMD banks | floating/vector, engine command |

Boundary metadata is fast-resolved and owns no physical IQ bank. For every
other generated class, the union of domain masks covers every configured bank
exactly once. ALU masks preserve two physical clusters; STD and SYS follow the
same lower/upper split. The profile requires at least eight even-numbered IQ
banks so both ALU clusters contain at least four banks.

## Capability boundary

Capability is retained as profile metadata rather than inferred from class.
This distinction is required because:

- AGU contains both LDA and STA recipes, but AGU2 cannot accept STA;
- ALU contains simple and multi-cycle recipes, but only ALU2/5 accept the
  latter;
- STD and SYS share physical ALU queues without becoming ordinary ALU
  operations;
- FSU and engine commands share one external selection domain but require
  different downstream execution behavior.

Generated recipe metadata now carries one capability requirement per dispatch
class. `OooIexCapabilityTopology` projects the static domain declarations into
a class-by-bank matrix for D3. Reservation considers only free banks whose
physical owner covers the recipe requirement. S2 stores the selected child's
requirement beside the compact scheduling state; S3 candidate creation,
retained-token claim, retry, and query all revalidate it against the static
domain capability. I2-to-E1 transfer performs the final check from the full
recipe before ownership and exact IQ release can fire.

All checks are fail-closed: a nonzero dispatch demand needs exactly one known
capability, and a physical domain must cover every required bit. Therefore
AGU2 cannot receive STA, and DIV/REM-class work can be reserved or selected
only by ALU2/5. Default constructors retain a permissive topology for focused
legacy unit harnesses; the formal profile exposes `capabilityTopology` and
`transferConfigs` for canonical integration.

The same separation is needed for picker multiplicity. AGU0/1 ultimately need
independent load- and store-address pickers over one residency owner, whereas
AGU2 has only a load picker. Shared DIV/PAC/SYS resources also require
cross-domain arbitration after local oldest-ready selection.

## Scaling the read boundary

The formal profile exceeds the earlier eight-domain limit. `OooParams` now
allows up to sixteen physical domains, and `OooIexAtomicReadArbiter` computes a
total priority rank followed by greedy complete-group packing. The network is
polynomial in domain count and preserves the prior lexicographic selection
contract without enumerating `2^N` subsets.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexPhysicalProfileSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAtomicReadArbiterSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexOldestReadyPickerSpec
bash tools/chisel/run_chisel_tests.sh --only OooDispatchSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueP1FabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferSlotSpec
```

The profile UT proves exact names, domain/release counts, complete disjoint
coverage, required capability placement, representative cluster masks,
invalid coverage/capability rejection, and twelve-domain transfer-fabric
elaboration. Dispatch, P1-fabric, and E1-slot tests prove recipe capability
steering, unsupported-row residency without claim, and final retained-transfer
rejection. The RF arbiter UT proves six oldest complete P-read groups win
from twelve contenders. Fabric and picker tests prove multi-class admission,
wrong-bank rejection, and oldest selection across ALU/STD projections.

## Remaining work

- represent multiple picker functions over AGU0/1 residency;
- add shared DIV/PAC/SYS and result-bus arbitration;
- instantiate the profile in the canonical static IEX/LSU top;
- synthesize the twelve-domain rank, RF crossbar, release fanout, and picker
  matrix at the default geometry.
