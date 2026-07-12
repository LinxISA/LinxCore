# ScalarLoadCompletionROBBridge

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/top/ScalarLoadCompletionROBBridge.scala`
- Parent: `chisel/src/main/scala/linxcore/top/LinxCoreTop.scala`
- ROB: `chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala`
- Tests: `chisel/src/test/scala/linxcore/top/ScalarLoadCompletionROBBridgeSpec.scala`
- Generated proof: `tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`
- Model: `model/LinxCoreModel/model/iex/iex.cpp`, `IEX::setMemData`;
  `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`, `LDAPipe::runW2`

## Contract

The bridge owns one reduced physical ROB-completion port. External execute
completion has fixed priority. When both sources are candidates, scalar
resolve-ready is low, the W2 slot remains resident, and `collision` is set.
Legal contention requires different ROB slots. A same-slot external and scalar
candidate is a duplicate-owner protocol violation because the legacy external
port has no generation or producer identity with which to prove idempotence.
When scalar resolve fires, the bridge forwards the complete slot-plus-wrap RID
through the ROB's exact-completion port and reports `scalarLoadSelected`.
Scalar resolve-ready also requires the ROB to revalidate that exact RID in the
same cycle. A free, stale, or invalid RID therefore holds W2 and cannot mutate
the completion bit.

The bridge also routes the canonical queue-head RID lookup to the reduced ROB.
The ROB validates slot plus wrap generation before LRET can enter W1. This
read-only lookup is distinct from the full-BID recovery lookup and does not
fabricate block authority.

The bridge is combinational. It must not retain or reconstruct completion
payloads, truncate RID generation, or emit both completion sources in one
cycle. The external source uses the legacy trusted slot-only harness port;
canonical scalar W2 does not.

## Scope

Shared completion arbitration and exact generation lookup are ISA-neutral.
The identity is Linx RID state; ARM exception levels, flags, exclusives,
barriers, acquire/release policy, and return state are not imported.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLoadCompletionROBBridgeSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- `bash tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`

The generated probe cycles the ROB through wrap and slot reuse, rejects a stale
pre-wrap RID, proves exact wrapped completion, and covers legal different-row
external-completion contention. The reference test classifies same-row
contention as a duplicate-owner protocol violation.
