# STQMemoryAttributeOwner

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQMemoryAttributeOwner.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQMemoryAttributeOwnerSpec.scala`
- Parent composition: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Contract ID: `LC-CHISEL-LSU-STQ-MEMATTR-001`

## Purpose

`STQMemoryAttributeOwner` retains the translation/PMA result for one exact
physical STQ residency. Decode and AGU do not infer cacheability or MMIO from
the opcode or a hard-coded address range. An external translation/PMA adapter
classifies the resolved address as `NormalCacheable`,
`NormalNonCacheable`, `DeviceMmio`, or `Fault`; `Unknown` remains an explicit
fail-closed state.

The sidecar is keyed by all of:

- physical STQ index and lease generation;
- complete semantic ROB/BROB owner;
- logical store beat.

This prevents a reused physical slot from inheriting the preceding store's
memory class.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `classify` | Decoupled translation/PMA result with lease, exact owner, logical beat, and typed memory class. |
| `rows` | Current registered STQ row image used to prove live ownership. |
| `recoveryActive` | Fences new classification writes during recovery. |

### Outputs

| Signal | Description |
|---|---|
| `attributes` | Per-row retained memory class, valid only while generation and owner still match the live row. |
| `accepted` | One exact WAIT-row classification was stored. |
| `missing/multiple` | Semantic owner lookup found zero or more than one live row. |
| `duplicate/conflict` | The exact residency was already classified with the same or a different class. |
| `malformed` | Token lacks valid lease/owner/native BID or carries `Unknown`. |

## State and Invariants

The module owns one valid bit, lease generation, exact owner, and memory class
per physical STQ slot. Stored state is observable only while the live row is
`Wait` or `Commit` and its lease generation plus exact owner still match.
Explicit clearing on slot reuse is therefore unnecessary: stale state becomes
unreachable immediately.

Classification is single-assignment for a residency. A duplicate is reported
but not consumed; a conflicting rewrite is reported and cannot alter the
stored class. Only an address-ready `Wait` row may accept first classification.

## Recovery

Recovery blocks new `classify` handshakes. Existing committed classifications
remain available, while attributes for pruned or reused rows automatically
become invalid through the live-row identity check.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQMemoryAttributeOwnerSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPathSpec`
- `bash tools/chisel/build_chisel.sh`

The focused UT covers exact acceptance, duplicate/conflict rejection,
recovery fencing, and stale classification invalidation after slot reuse.

## Remaining Integration Gaps

- connect a real DTLB/PMA result producer instead of a test adapter;
- carry precise translation/PMA fault metadata, not only the `Fault` class;
- define the platform PMA table and its reset/configuration ownership;
- apply the same typed policy to loads, atomics, fences, and maintenance ops.
