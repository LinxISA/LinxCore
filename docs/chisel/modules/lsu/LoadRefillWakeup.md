# LoadRefillWakeup

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadRefillWakeup.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/lsu/LoadRefillWakeupSpec.scala`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq.h`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.cpp`
  - `model/LinxCoreModel/model/lsu/load_unit/ldq_cluster.h`
- Related Chisel contracts:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/LoadReplayWakeup.scala`
- Contract IDs: `LC-CHISEL-LSU-LIQ-003`

## Purpose

`LoadRefillWakeup` is the first Chisel owner for read-refill wakeups into
resident LIQ rows. It models the row-state part of `LDQInfo::handleL1Wakeup`:
read refill packets wake matching unresolved scalar rows, mark the local L1
hit sideband, and provide a full-line valid mask so `LoadInflightQueue` can
relaunch the load through the normal forwarding pipeline.

This module deliberately does not own L1D arrays, the miss queue, prefetch set,
L2/CHI response ordering, ready-table updates, consumer bypass routing,
ResolveQ/LHQ movement, or trace emission. Canonical `ScalarLSULoadPath` places
it after `ScalarL1D` refill installation; it owns only matching and LIQ row
wakeup.

## Interface

### Refill Request

| Signal | Description |
|---|---|
| `refillValid` | Qualifies one refill packet. |
| `refill.isRead` | Must be true before rows are woken; non-read packets are ignored. |
| `refill.lineAddr` | 64-byte line address/tag returned by the refill path. |
| `refill.data` | Refilled 64-byte cacheline data. |
| `refill.l2Miss` | Metadata carried for later stats/trace owners; not consumed in this packet. |

### Row Inputs

| Signal | Description |
|---|---|
| `rows` | Current LIQ row image from `LoadInflightQueue`. |

### Outputs

| Signal | Description |
|---|---|
| `refillAccepted` | Refill is valid and read-typed. |
| `wakeMask` | Rows eligible to return to `Wait` because the refill line matches. |
| `requestByteMasks` | Recomputed requested load-byte mask per row. |
| `lineValidMask` | Full 64-byte valid mask for accepted read refills. |

## State

`LoadRefillWakeup` is combinational and stateless. `LoadInflightQueue` owns
the registered row image and stores the refill line data plus `l1Hit` sideband.

## Logic Design

The C++ model path is:

1. `handleL1Wakeup` ignores non-read packets after prefetch feedback.
2. For read packets, it erases the line from `missQ` and `prefSet`, converts
   packet data into `ReqData`, and merges it into `clusters[cID].cData`.
3. It scans the matching cluster and wakes each row where:
   - the row has not already hit LDQ or L1 data,
   - the row is neither `LDQ_IDLE` nor `LDQ_RESOLVED`,
   - the row tag matches the refill line,
   - the row is not a tile load.
4. A woken row returns to `LDQ_WAIT` and sets `l1Hit=true`; later `pickL1`
   relaunches it through the normal data-return path.

The Chisel mapping keeps only the row-state subset:

1. Accept only valid read refills.
2. Match working, non-resolved, non-idle, non-tile rows on 64-byte line
   address.
3. Suppress rows that already have `l1Hit` set.
4. Report `wakeMask`; `LoadInflightQueue` applies it by setting row status to
   `Wait`, storing the full refill line, setting `validMask` to all 64 bytes,
   and setting `l1Hit`.

## Timing

`LoadRefillWakeup` is a combinational sidecar. `LoadInflightQueue` applies
the refill mask after E4 updates, resolved-row clears, and store/SCB replay
wakeups, then before launch/allocation state updates. A refilled row can be
selected by `launchReady` on the following cycle; there is no same-cycle
refill-to-launch path.

When a row is launched, `LoadInflightQueue` now uses row-owned `lineData` and
`validMask` as the forwarding-pipeline base if those bytes are present. This
lets both refill-woken rows and store/SCB replay-completed rows relaunch
without depending on an external base-data replay input.

## Flush/Recovery

The helper has no internal flush state. `LoadInflightQueue` gates
`refillValid` during global flush and owns row clearing. Precise load-queue
`FlushBus` pruning and miss-queue pruning remain later packets.

## Trace/Observability

`refillWakeMask` and `refillAccepted` are debug/bring-up observability only.
They are not architectural memory traces and are not yet compared against QEMU
or LinxCoreModel execution traces.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LoadRefillWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadInflightQueue`
- `bash tools/chisel/run_chisel_tests.sh --only LoadReplayWakeup`
- `bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline`
- `bash tools/chisel/run_chisel_tests.sh --only LoadStoreForwarding`
- `bash tools/chisel/run_chisel_tests.sh --only MDBConflictDetect`
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --reduced-rob`
- `python3 tools/chisel/trace_schema_adapter.py --self-test`
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run`

Focused tests cover read-refill row wakeup, suppression for non-read packets,
tile rows, already-hit rows, resolved/idle rows, different-line rows, working
`Wait`/`Repick`/`L2Wait` rows, Chisel elaboration, and integrated LIQ relaunch
from row-owned refill data.
