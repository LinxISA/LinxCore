# Recovery Producers

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoveryProducers.scala`
- Production bank:
  `chisel/src/main/scala/linxcore/recovery/RecoveryNonLsuProducerBank.scala`
- Backend integration:
  `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
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

`RecoveryNonLsuProducerBank` is the production composition owner for the four
non-LSU families. It gives each family an independent retained lane and appends
those lanes to the backend fabric after any externally supplied lanes, so an
existing source index is not silently renumbered. `DecodeRenameROBPath`
instantiates this bank unconditionally. Its default external prefix contains
only the real scalar-redirect lane; reduced shells tie off raw events, not the
retained owner.

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

`IexIqStallRecoveryIdentity` derives the watchdog's exact replay pointer from
the selected STID's authoritative BROB commit cursor. It publishes
successor(commit-pointer) only while that STID has a valid oldest block, and it
qualifies completed or absent oldest state before the watchdog counts. The
addition is over the full implementation pointer width, including rollover;
the module never increments a canonical BID slot in isolation.

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

R657 adds `RecoveryNonLsuProducerBank`, uses it in the generated proof and the
canonical `DecodeRenameROBPath`, and derives IQ-watchdog identity from resident
BROB order state. Raw BCC miss, IEX slow-insert, IEX IQ-stall, and PE mismatch
event ports are now production backend inputs. Upstream live BCC, IEX dispatch,
and PE compare owners still need to drive those ports; tied-off reduced shells
are not evidence of workload activation.

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
stall suppression without an oldest block followed by full-pointer
`0xffff -> 0` rollover and accepted fast replay. The same probe checks stable
bank lane indices and consumed source/payload provenance. Focused elaboration
also proves the per-STID watchdog identity owner.
