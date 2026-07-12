# ScalarLoadGPRCompletionSink

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/top/ScalarLoadGPRCompletionSink.scala`
- Parent: `chisel/src/main/scala/linxcore/top/LinxCoreTop.scala`
- GPR owner: `chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala`
- Tests: `chisel/src/test/scala/linxcore/top/ScalarLoadGPRCompletionSinkSpec.scala`
- Generated proof: `tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`
- Model: `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`, `LDAPipe::runW2`;
  `model/LinxCoreModel/model/iex/iex_iq.cpp`, `IssueQueue::WakeupIQTag`

## Contract

The sink is the physical scalar-GPR side of the canonical W2 rendezvous. It
classifies the resident load destination, requests write bandwidth, and returns
writeback/wakeup readiness to `ScalarLSULoadReturnPipeline`. It commits no
state from candidate validity alone.

When W2 resolves:

- a GPR destination commits returned data to its physical tag;
- ordinary non-speculative P wakeup publishes by setting the same ready bit;
- writeback and wakeup fires must match the destination/speculation shape;
- the sink and exact ROB completion are accepted in one cycle.

External writeback uses port 0. With one configured port it blocks scalar W2.
With multiple ports, different tags may complete together; a same-tag external
write retains priority and holds W2. W2 retries with its complete payload.

## Destination Policy

`DestinationKind.Gpr` is supported. No-destination returns require neither GPR
write nor GPR wakeup. Speculative-wakeup and stack sidebands suppress ordinary
wakeup according to the canonical LRET payload.

T/U destinations are deliberately not converted to GPR writes. An ordinary
T/U wakeup request keeps `loadWakeupReady` low, reports
`loadBlockedByUnsupportedDestination`, and raises `protocolError` until the
local-link bank/qtag owner is connected. W2 is not accepted and no ROB, GPR, or
P-ready evidence is valid for that request. This explicit integration failure
prevents a silent permanent stall or an ISA-invalid substitution.

## State And Timing

The sink owns no retained arbitration state. `ScalarGPRFile` owns data and
readiness. Port grants are combinational from stable W2 candidates; mutation is
registered only from fire. Same-tag external contention and a one-port
configuration therefore backpressure W2 without early ROB completion, RF
write, or ready-table update.

## Diagnostics

- `loadWriteRequired` and `loadWakeupRequired` expose payload classification.
- `loadWritebackSelected` reports committed scalar GPR data.
- `loadWakeupPublished` reports committed ordinary P-tag readiness.
- `loadBlockedByExternalWrite` distinguishes physical-port contention.
- `loadBlockedByUnsupportedDestination` distinguishes the staged T/U path.
- `protocolError` checks resolve/writeback/wakeup fire coherence, GPR state
  ownership, and unsupported T/U integration requests.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLoadGPRCompletionSinkSpec`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTopSpec`
- `bash tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`

The generated probe clears a physical tag, proves a same-tag external write
holds scalar completion, retries W2, and then observes returned data, ready bit,
wakeup publication, and ROB completion together. It also proves independent
external and scalar tags use two configured ports in one cycle, a one-port
configuration serializes different tags, and a T destination reports the
explicit contract error without mutating ROB or GPR state. Each public
resolve/writeback/wakeup allow is also withdrawn independently to prove that
the resident W2 and every side effect remain held.
