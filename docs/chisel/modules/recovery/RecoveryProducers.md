# Recovery Producers

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoveryProducers.scala`
- Integrated probe:
  `chisel/src/main/scala/linxcore/recovery/RecoveryProducerProbe.scala`
- Tests:
  `chisel/src/test/scala/linxcore/recovery/RecoveryProducerSpec.scala`
- Generated-RTL testbench:
  `tools/chisel/recovery_producer_probe_tb.cpp`
- pyCircuit retained producer:
  `src/bcc/ooo/recovery_producer.py`
- pyCircuit generated-RTL flow:
  `tests/test_ooo_recovery_producer_pyc_flow.sh`
- Contract ID: `LC-CHISEL-RECOVERY-PRODUCERS-001`

## Purpose

The producer layer converts owner-local Linx recovery events into retained
`FullBidFlushReq` records. It preserves the useful model mechanisms while
requiring the trigger owner to supply exact block-generation identity. A
producer must never increment a ring BID, reinterpret a fetch-bundle ID, or
fill a missing full BID from debug state.

`RecoveryProducerQueue` parameterizes finite event retention independently of
the central source arbiter. Acceptance into this queue transfers ownership;
the request remains bit-stable until `RecoveryFabric` accepts it.

## Producer Contracts

| Producer | Model mechanism | Linx request | Required owner evidence |
|---|---|---|---|
| `BccRecoverySource` | `BlockROB::resolveBlock` miss prediction | `MissPredFlush`, block-based, non-immediate | Exact first invalid full block BID, STID scope, and model IEX engine class |
| `IexSlowInsertRecoverySource` | `DispatchUnit::GenFlushRequest` | `NukeFlush`, block-based, immediate | Exact full block BID, IEX PE/STID/TID and row identity, and exact Linx64 restart TPC |
| `IexIqStallRecoverySource` | `IEX::checkIQStall` | `FastReplay`, block-based, non-immediate | Per-STID no-progress condition and exact next full block BID |
| `PeMismatchRecoverySource` | `SPEROB::CheckDstDataOut` / `VecPEROB::CheckDstDataOut` | `InnerFlush`, PE-scoped, non-immediate | Compare-owner full block BID, RID/LSID, PE/STID/TID, engine class, and exact Linx64 restart TPC |

The IQ watchdog uses parameter `stallThreshold`. Progress or completed-oldest
evidence resets its consecutive counter. At threshold, missing full identity
holds a visible blocked condition; it does not publish a projected request.
Once identity is valid, the watchdog captures one request before the owner
inputs may change.

`FullBidFlushReq` and `FlushReq` carry both `fetchTpcValid` and the 64-bit
`fetchTpc`. This is functional recovery state: a non-block-based PE inner flush
restarts from the mismatched row PC. The target is retained through class merge
and registered cleanup; a valid bit without its target is not a legal request.

## Foreign-ISA Exclusions

These modules define no ARM exception level, SPSR/PSTATE restoration,
condition-code rollback, exclusive monitor, return-from-exception, or ARM
barrier behavior. They map only Linx block, ROB, execution-scope, and recovery
class semantics.

## Integration Status

R642 implements and composes all four non-LSU producer adapter families in a
generated-RTL proof. Canonical BCC resolution, IEX dispatch/IQ, and PE compare
owners do not yet expose every authoritative full-BID event port, so production
top connections remain open. The adapters are not evidence of live workload
activation until those owner connections exist.

R643 adds the first pyCircuit retained producer boundary. It parameterizes BID,
restart-TPC, STID, and owner widths; backpressures incomplete identity; retains
the exact packet bit-stably; and supports consume-and-replace. It is not yet
wired into the pyCircuit backend, and pyCircuit class merge and cleanup fanout
remain open. The legacy redirect latch is not treated as equivalent recovery
RTL.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryProducerSpec`
- `bash tools/chisel/run_chisel_recovery_producer_probe.sh`
- `bash tests/test_ooo_recovery_producer_pyc_flow.sh`

The generated probe covers simultaneous BCC/IEX retention, exact full-BID
delivery, immediate slow-insert nuke behavior, PE-scoped inner recovery, and IQ
stall identity blocking followed by accepted fast replay.
