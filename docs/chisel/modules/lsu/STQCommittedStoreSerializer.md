# STQCommittedStoreSerializer

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQCommittedStoreSerializer.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/STQCommittedStoreSerializerSpec.scala`
- Parent composition: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala`
- Contract ID: `LC-CHISEL-LSU-STQ-SERIALIZER-001`

## Purpose

`STQCommittedStoreSerializer` is the exactly-once transport owner for committed
`NormalNonCacheable` and `DeviceMmio` stores. It accepts one complete logical
store batch, retains every scalar/pair and split-fragment descriptor, and
allows at most one external request to be outstanding.

Unlike the cacheable SCB path, successful request acceptance is not terminal.
The serializer frees all participating STQ rows and emits one logical-store
completion only after the last exact response returns.

## Interface

### Inputs

| Signal | Description |
|---|---|
| `batch` | One retained logical store: one or two STQ issues and one to four ordered fragments. |
| `request.ready` | External non-cacheable/device transport acceptance. |
| `response` | Transaction-qualified terminal response with error status. |
| `recoveryActive` | Fences admission of new committed work; does not cancel accepted work. |

### Outputs

| Signal | Description |
|---|---|
| `request` | Stable transaction ID, memory class, exact issue identity, and current fragment. |
| `freeMask` | Atomic free of every participating STQ row after the final exact response. |
| `logicalCompletion` | One completion for the whole scalar or pair store. |
| `terminalError` | OR of response errors across all fragments, asserted with terminal completion. |
| `busy/waitingResponse` | Retained-batch and outstanding-request state. |
| `batchMalformed/staleResponse` | Fail-closed shape and response-identity diagnostics. |
| `acceptedRequestCount` | Number of externally accepted fragments in the current batch. |

## State and Protocol

The serializer retains the memory class, issue rows, fragment descriptors,
pending mask, transaction generation, outstanding transaction ID, response
error aggregate, and accepted-fragment count.

Admission requires a routable serialized class and an exact batch shape:

- each valid issue owns either one unsplit `segment=0,last=1` fragment or two
  split `segment=0/1` fragments whose row ownership is carried only by the
  final fragment;
- every fragment agrees with its issue on STQ index, STID, LSID, and class;
- all valid issues belong to one semantic logical-store owner.

Requests are sent in retained fragment order. `valid` payload and transaction
identity remain stable under backpressure. A wrong transaction response is not
accepted and cannot advance state. One request is outstanding at a time, so a
fragment cannot be duplicated or reordered by this owner.

## Recovery and Errors

Ordinary recovery blocks only a new batch. Once a committed batch has been
accepted, it continues through its exact terminal response and is never
cancelled or reissued by recovery. This is required because the external side
effect may already be visible.

Response errors are retained across the batch and reported on the final
logical completion. Mapping that terminal error into the precise ROB/platform
fault path remains an integration responsibility.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only STQCommittedStoreSerializerSpec`
- `bash tools/chisel/run_chisel_tests.sh --only STQSCBCommitPathSpec`
- `bash tools/chisel/build_chisel.sh`

The focused UT covers request backpressure stability, stale response rejection,
recovery during an accepted batch, pair plus split serialization, atomic final
free, single logical completion, and malformed batch rejection.

## Remaining Integration Gaps

- connect `request/response` to the real uncached/device interconnect;
- route terminal errors to the precise architectural exception owner;
- define timeout, machine-check, and reset behavior for a non-responding target;
- coordinate ordering with Device loads, atomics, fences, and cache maintenance;
- prove transaction-ID wrap only after sufficient quiescence.
