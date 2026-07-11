# MDBRecoveryDeliveryPath

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBRecoveryDeliveryPath.scala`
- Atomic admission: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/MDBConflictTransactionControl.scala`
- Exact promotion: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSURecoverySource.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/MDBRecoveryDeliveryPathSpec.scala`
- Generated proof: `rtl/LinxCore/tools/chisel/run_chisel_mdb_recovery_delivery_path_probe.sh`
- Model evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/mdb/MDB.cpp`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`, `LC-MA-REC-001`
- pyCircuit atomic-admission peer: `rtl/LinxCore/src/bcc/lsu/mdb_transaction.py`

## Purpose

`MDBRecoveryDeliveryPath` is the retained publication boundary between one
accepted scalar memory-dependence violation and central Linx recovery. It
ensures that the MDB learning record and typed recovery report are admitted
together. It then retains the report until the report's STID selects a valid
ROB/BROB oldest watermark, the resident ROB row returns an exact full BID, and
the central recovery owner accepts the promoted source.

The module does not add architectural state or an LSU-local cleanup policy.
The full BID is allocator-owned implementation identity; Linx architectural
recovery remains defined by block, STID, and precise ROB ordering.

## Parameters

| Parameter | Contract |
|---|---|
| `entries` | Power-of-two ROB ring capacity used by wrap-qualified BID/RID identity. |
| `recoveryQueueEntries` | Power-of-two retained report capacity greater than one. |
| `stidCount` | Number of independently ordered scalar STID watermark lanes. |
| `bidWidth` | Width of allocator-stamped full block BID. |
| Address, PC, PE, STID, TID, and size widths | Payload widths; they do not change conflict or recovery policy. |

## Transaction Contract

1. A non-conflicting candidate needs no record or recovery credit and may pass
   even when those sinks are full.
2. A conflicting candidate is accepted only when both `recordReady` and the
   recovery queue enqueue port are ready.
3. `recordValid` and recovery enqueue therefore describe the same accepted
   candidate. Neither may fire alone.
4. The queue head remains stable while age eligibility, exact lookup, or
   central source acceptance is blocked.
5. The queue dequeues only on `ScalarLSURecoverySource.sourceAccepted`.

## STID And Identity Contract

- The queued report STID selects exactly one `oldestValid/oldestBid/oldestRid`
  lane. BIDs from different STIDs are never compared.
- An out-of-range STID selects no lane and cannot authorize recovery.
- Exact promotion requires the ROB lookup response to echo
  `(BID,GID,RID,PE,STID,TID)`, carry a valid full BID, and project that full BID
  back to the same wrap-qualified ring BID.
- Lookup miss, stale echo, ring-projection mismatch, missing oldest state, and
  downstream backpressure all retain the report without cleanup side effects.
- Flush clears queued reports. SSIT predictor state is owned elsewhere and is
  not cleared by this delivery module.

## Linx Binding

- Same-block violations publish `InnerFlush`.
- Cross-block violations publish `NukeFlush`.
- The report carries the violating load's restart TPC and precise
  BID/GID/RID/LSID scope.
- ARM exception classes, condition flags, register banking, and architectural
  replay behavior are outside this module and must not be inferred from the
  ISA-neutral queue or handshake structure.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictTransactionControl`
- `bash tools/chisel/run_chisel_tests.sh --only MDBRecoveryDeliveryPath`
- `bash tools/chisel/run_chisel_mdb_recovery_delivery_path_probe.sh`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSU`
- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreFrontendFetchRfAluTraceTop`

The generated probe covers record-side backpressure, atomic admission,
retention under source backpressure, exact full-BID promotion, STID1 lane
selection, out-of-range STID suppression, accepted dequeue, and flush cleanup.
`tests/test_lsu_mdb_transaction_cross_rtl.sh` additionally requires the Chisel
and pyCircuit atomic-admission lanes to declare and pass the same conflict
backpressure, atomic publication, and unrequired-sink bypass scenarios.
