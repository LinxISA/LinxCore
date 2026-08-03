# OooIexPhysicalProfile

## Purpose

`OooIexPhysicalProfile` is the elaboration-time source of truth for physical
Issue Queue residency, picker multiplicity, and execution-lane capabilities.
It keeps five concepts distinct:

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
- `chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala`

## Canonical topology

`OooIexPhysicalProfile.fromCoreParams` derives eight disjoint residency
owners, ten picker functions, ten execution lanes, and ten exact release ports
for the current W4 configuration. Decode, dispatch, and issue width remain
parameterized through W2/W4/W6/W8. Execution resources remain the specified
2 ALU, 1 BRU, 2 AGU, 2 STD, 1 system/multicycle queue, and 1 CMD queue.
Residency-owner count is a storage property; picker count must not be used to
duplicate IQ rows.

| Residency owner | Logical residency | Picker functions | Declared capabilities |
|---|---|---|---|
| ALU0 | partition of ALU and STD banks | `alu0` | simple ALU, store data |
| ALU1 | partition of ALU and STD banks | `alu1` | simple ALU, store data |
| AGU0 | partition of AGU | `agu0-lda`, `agu0-sta` | load address, store address |
| AGU1 | partition of AGU | `agu1-lda`, `agu1-sta` | load address, store address |
| BRU0 | all BRU banks | `bru0` | branch |
| SYS0 | ALU/SYS banks | `sys0` | multicycle ALU, PAC, system |
| FSU0 | all FSU banks | `fsu0` | floating/vector |
| CMD0 | all CMD banks | `cmd0` | engine command |

Boundary metadata is fast-resolved and owns no physical IQ bank. For every
other generated class, the union of residency-owner masks covers every
configured bank exactly once. Picker projections may overlap only when their
declared capability sets are disjoint. Each AGU's LDA and STA picker can
therefore observe the same physical banks without becoming eligible for the
same row. ALU and STD masks preserve the two physical integer clusters.

## Capability boundary

Capability is retained as profile metadata rather than inferred from class.
This distinction is required because:

- AGU contains LDA and STA recipes with separate picker functions over each
  shared AGU residency owner;
- simple ALU work and system/multicycle/PAC work have separate owners even
  when both observe ALU-class banks;
- STD shares physical ALU queues without becoming an ordinary ALU operation;
- FSU and engine commands use separate owners and downstream behavior.

Generated recipe metadata carries one capability requirement per dispatch
class. `OooIexCapabilityTopology` projects the static owner declarations into
a class-by-bank matrix for D3. Reservation considers only free banks whose
physical owner covers the recipe requirement. S2 stores the selected child's
requirement beside compact scheduling state; S3 candidate creation, retained
token claim, retry, and query revalidate it against the static capability.
I2-to-E1 transfer performs the final check before ownership and exact IQ
release can fire.

All checks fail closed: a nonzero dispatch demand needs exactly one known
capability, and a picker must cover every required bit. The formal profile
exposes `capabilityTopology` and `transferConfigs` for canonical integration.
Multicycle, PAC, and system reservations remain independent physical
resources; their I1 arbiter uses execution-lane identity without changing
residency or picker ownership.

## Scaling the read boundary

`OooParams` allows up to sixteen picker functions, and
`OooIexAtomicReadArbiter` computes a total priority rank followed by greedy
complete-group packing. The network is polynomial in picker count and
preserves the lexicographic selection contract without enumerating `2^N`
subsets.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexPhysicalProfileSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAtomicReadArbiterSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferFabricSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexOldestReadyPickerSpec
bash tools/chisel/run_chisel_tests.sh --only OOODispatchSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferSlotSpec
bash tools/chisel/run_chisel_tests.sh --only IEXMechanismSpec
```

The profile UT proves exact owner/picker/lane names, disjoint residency
coverage, capability-disjoint picker overlap, required capability placement,
representative cluster masks, and invalid topology rejection.
`IEXMechanismSpec` proves that main and bounded W2/W4/W6/W8 configurations
derive the same physical topology and dynamically elaborates the bounded
mechanisms. Full-capacity RTL generation remains a separate closure gate.
Dispatch and E1-slot tests prove recipe steering, unsupported-row residency
without claim, and final retained-transfer rejection. The RF arbiter tests
oldest complete read-group selection. Fabric and picker tests prove multi-class
admission, simultaneous AGU LDA/STA selection, wrong-bank rejection, and
oldest selection across ALU/STD projections.

## Remaining work

- connect shared-resource busy/latency and result-bus reservations;
- connect `OooIexPipeline` to the typed execution and LSU owners;
- remove the older `OooIexLinxPhysicalProfile.apply` alternate topology after
  its remaining focused tests and helper defaults migrate to
  `fromCoreParams`;
- synthesize the parameter-derived rank, RF crossbar, release fanout, and
  picker matrix at the default geometry.
