# LinxCore Scalar LR/SC Bring-Up Spec

Date: 2026-07-24

## Scope

This document defines the first Chisel hardware slice for scalar Linx LR/SC
instructions. The first executable slice is `LR.W` and `SC.W`. The same owner
interfaces must be sized so `LR.B`, `LR.H`, `LR.D`, `SC.B`, `SC.H`, and
`SC.D` can be added without changing ROB, LSU, or writeback contracts.

This is a scalar LSU/ROB contract. It excludes vector and tile atomics. It is
not a QEMU compatibility waiver: Chisel must follow the active ISA/Sail
contract unless a separate ISA decision changes that contract.

## Cross-Stack Contract

| Source | Evidence | Contract used by Chisel |
|---|---|---|
| ISA opcode table | `isa/v0.57/opcodes/lx_32.opc` | `LR.*` uses `RegDst, SrcL, SrcZero, aq, far, rl`; `SC.*` uses `RegDst, SrcL, SrcR, aq, far, rl`. |
| Sail execution | `isa/sail/model/execute/execute.sail` | LR reads memory, sets a 64-byte exclusive reservation, and writes the loaded value. SC checks the reservation line, conditionally stores, clears the reservation, and writes status `0` on success or `1` on failure. `LR.W` sign-extends the loaded 32-bit value. |
| Sail state | `isa/sail/model/state/state.sail` | Reservation is a single local monitor at 64-byte cache-line granularity. |
| QEMU | `emulator/qemu/target/linx/helper.c`, `translate.c` | QEMU implements helpers and clears reservations on stores/atomics, but its LR reservation match is exact address plus access size. QEMU `linx_lr_w` currently zero-extends. |
| LinxCoreModel | `tools/LinxCoreModel/emulator/engine/AaccelssMemoryEngine.cpp`, `model/lsu/lsu.cpp` | Model classifies LR/SC as `InstGroup::ATOMIC`; LR is load-like, SC is store-like, SC data comes from source index 0, and status writes the destination. |
| SuperNPU SLSU RTL | `Documents/superscalarNPU/rtl/sn_slsu/*`, `docs/microarch/sn_slsu/sn_slsu_arch.md` | Use retained committed-store boundaries, full identity revalidation, and recovery-owner handshakes. Do not release speculative rows from a slot-only or mask-only side effect. |

Normative Chisel rule: `LR.W` must sign-extend from bit 31 to XLEN. The current
QEMU zero-extension for `linx_lr_w` is a known divergence and must be reported
as QEMU mismatch evidence, not copied into Chisel.

## Instruction Semantics

### LR.W

- Address operand: `SrcL`.
- Data width: 4 bytes.
- Destination: `RegDst`.
- Memory result: signed 32-bit load, sign-extended to 64 bits.
- Reservation: set local `(STID, lineAddr)` where `lineAddr = addr[63:6]`.
- Completion: one atomic event that publishes load data to RF/writeback, wakes
  dependents, marks the exact ROB row complete, and records the reservation.

### SC.W

- Store-data operand: `SrcL`.
- Address operand: `SrcR`.
- Data width: 4 bytes.
- Destination: `RegDst`.
- Success condition: a valid local reservation exists for the same `STID` and
  same 64-byte line as the SC address, and no invalidating event cleared it.
- Success side effect: write `SrcL[31:0]` to memory and write status `0`.
- Failure side effect: write no memory and write status `1`.
- Completion: status writeback, ROB completion, reservation clear, and
  conditional store side effect must be one accepted transaction.

### B/H/D Extension

`LR.B` and `LR.H` zero-extend. `LR.W` sign-extends. `LR.D` returns 64 bits.
`SC.B/H/W/D` truncate `SrcL` to 1/2/4/8 bytes and always write status `0` or
`1`. The reservation match remains 64-byte line based for all sizes; the first
slice may optionally require same-size match as a conservative implementation
guard, but the ISA-visible line-granularity contract must be the promotion
target.

## Reservation Owner

Add a scalar reservation owner under `linxcore.lsu`, tentatively
`ScalarLrScReservationOwner`.

State per STID:

| Field | Meaning |
|---|---|
| `valid` | A live local reservation exists. |
| `lineAddr` | 64-byte line address. |
| `size` | Original LR size for diagnostics and optional first-slice guard. |
| `lrBid`, `lrGid`, `lrRid`, `lrLsIdFull` | Exact LR identity for debug and recovery audit. |

Inputs:

- `lrSetValid`, `lrSetStid`, `lrSetLineAddr`, `lrSetSize`, `lrSetIdentity`.
- `scCheckValid`, `scCheckStid`, `scCheckLineAddr`, `scCheckSize`.
- `storeInvalidateValid`, `storeInvalidateStid`, `storeInvalidateLineAddr`,
  `storeInvalidateMask`.
- `flushValid`, `flushStid`, `flushAll`, and precise ROB/LSID recovery fields.
- `contextInvalidateValid` for ACR/context switch, privilege transition, or
  global core reset.

Outputs:

- `scSuccess`.
- `scCheckAccepted`.
- `reservationValidByStid`.
- `protocolError` for out-of-range STID, impossible size, or unaccepted
  replacement of a retained side-effect request.

Rules:

- LR replaces the previous reservation for the same STID only when the LR
  completion transaction is accepted.
- SC clears the reservation whether it succeeds or fails, but only when the SC
  completion transaction is accepted.
- Any accepted store or atomic store to the same STID and same 64-byte line
  clears the reservation. The invalidation event must come from the committed
  store side-effect boundary, not from a speculative STA/STD enqueue.
- A precise recovery that kills an LR before completion must not set a
  reservation. A recovery that kills a resident SC before commit must not clear
  a reservation or mutate memory.
- A context switch, nuke flush, or privilege/ACR transition clears all local
  reservations for affected STIDs.

## LSU Integration

Use existing scalar LSU owner boundaries:

- `ScalarLSU` owns store path, load path, and recovery source wiring.
- `ScalarLSULoadPath` already has LIQ, LRET, line data, sign-extension, and
  ROB/writeback return boundaries.
- `STQEntryBank` and `STQSCBCommitPath` own speculative store residency,
  `WAIT -> COMMIT`, SCB admission, and committed-store free.
- `ReducedRobCompletionArbiter` arbitrates execute, replay, service, and
  template completion sources. LR/SC completion must enter through a named LSU
  completion source or a widened arbiter source, not be hidden as ordinary ALU
  completion.

LR.W path:

1. Decode classifies LR as AMO/load-like and routes it to LSU.
2. Rename allocates one destination and records `aq/rl/far`, size, and AMO
   kind in the uop.
3. Issue reads `SrcL` as address and dispatches to LSU load allocation.
4. Load return data uses existing `LoadReplayReturnDataExtract`-style
   extraction with `returnSignExtend = true` for LR.W.
5. The accepted load-return side effect sets the reservation and completes the
   ROB row atomically.

SC.W path:

1. Decode classifies SC as AMO/store-like and routes it to LSU.
2. Rename allocates one destination for status and reads `SrcL` data plus
   `SrcR` address.
3. Issue sends a single SC request carrying full identity, address, data,
   size, destination ptag, `aq/rl/far`, and full LSID.
4. The SC request waits until its ROB row is the commit head or until an
   equivalent exact commit authorization is present.
5. At commit, reservation check, optional store enqueue, status writeback,
   wakeup, ROB completion, and reservation clear are accepted through one
   retained transaction. For a successful SC, acceptance of the canonical STQ
   insert is the irreversible architectural store-buffer boundary because the
   SC issue path is already full commit-head gated.

SC must not be implemented by enqueuing a normal speculative store and later
guessing status. A failed SC has no STQ/SCB memory side effect. A successful SC
may use the existing committed-store path, but the commit path must revalidate
full identity and release the ROB/status transaction after every required
store fragment is accepted into the canonical STQ insert boundary. Waiting for
SCB acceptance before ROB completion is rejected: ROB commit is what marks the
STQ row committed, and the normal STQ drain then reaches SCB. Matching SCB
acceptance remains useful post-commit diagnostic/probe evidence, but it is not
the SC completion gate.

## Ordering and aq/rl

For the first scalar slice:

- `aq` on LR/SC blocks younger memory issue for the same STID until the LR/SC
  completes.
- `rl` on SC blocks the SC commit side effect until all older memory operations
  for the same STID have completed or reached the committed-store boundary.
- `aqrl` applies both rules.
- `far` is decoded and carried as metadata, but it is not promoted as a remote
  coherence feature until the platform memory model defines remote invalidation.

These rules are conservative and hardware-feasible. They can be weakened only
after ISA memory-model text and QEMU/model behavior agree on the weaker rule.

## Recovery and Backpressure

- LR/SC requests retain full `(STID, BID, GID, RID, LSID full, PC, opcode)`.
- Backpressure holds the full request stable. It must not recompute source
  values after issue acceptance.
- Unsupported LR/SC forms must trap or report explicit unsupported completion.
  They must not release a ROB row without writeback/completion.
- Flush before LR completion suppresses both load return and reservation set.
- Flush before SC commit suppresses memory mutation, status writeback, ROB
  completion, and reservation clear.
- Flush after successful SC STQ insert acceptance preserves the store side
  effect and does not undo SC status. SCB progress after that point is normal
  committed-store drain progress, not part of SC ROB completion.

## Hardware Feasibility

The first implementation is small:

- one per-STID reservation register file;
- one line compare for SC;
- one committed-store invalidation compare;
- one LSU completion/ROB arbitration source;
- conservative memory-order gates from existing LSID/ROB commit-head signals.

The critical hardware rule learned from LinxCoreModel and SuperNPU RTL is to
keep side effects behind retained, full-identity boundaries. Slot-only STQ
free, mask-only commit, or speculative-store invalidation is not sufficient for
LR/SC because SC success has both a memory side effect and a destination
writeback side effect.

## Interfaces to Add

### Decode/Rename Uop Fields

Add to `DecodedUop` / `RenamedUop` or an LSU sideband bundle:

| Field | Width | Meaning |
|---|---:|---|
| `amoValid` | 1 | LR/SC/AMO row. |
| `amoKind` | enum | `LR`, `SC`, later swap/CAS/RMW. |
| `amoSizeBytes` | 4 | 1, 2, 4, or 8. |
| `amoAq`, `amoRl`, `amoFar` | 1 each | Encoded modifiers. |
| `amoStoreData` | 64 | SC store data after source read. |

### LSU Request Ports

Add to `ScalarLSUIO`:

```text
lrReq:  Decoupled(ScalarLrReq)
lrResp: Valid(ScalarLrResp)
scReq:  Decoupled(ScalarScReq)
scResp: Valid(ScalarScResp)
```

`ScalarLrReq` carries address, size, destination, source trace, full identity,
and modifiers. `ScalarScReq` carries address, data, byte mask, destination,
full identity, and modifiers. Responses carry completion row data, status/data,
and diagnostics.

### Store Commit Invalidation

Expose from `STQSCBCommitPath` or `SCBCommitBridge`:

```text
committedStoreInvalidateValid
committedStoreInvalidateStid
committedStoreInvalidateLineAddr
committedStoreInvalidateMask
```

This invalidation fires only at the irreversible committed-store boundary.

### ROB Completion

Extend `ReducedRobCompletionArbiter` or add a named LSU completion arbiter:

```text
lsuCompleteValid
lsuCompleteRobValue
lsuCompleteRowValid
lsuCompleteRow
```

Priority must preserve exact side effects. If LSU completion contends with
execute/replay/service/template for the same ROB row, the selected source must
be deterministic and protocol errors must flag different-RID contention.

## Test Plan

Focused unit tests:

1. `LR.W` sign-extends `0x80000000` to `0xffffffff80000000`.
2. `LR.W` sets a 64-byte reservation for the same STID.
3. `SC.W` succeeds on same-line reservation and writes status `0`.
4. `SC.W` fails without reservation and writes status `1`.
5. `SC.W` clears reservation on success and failure.
6. Different-STID stores do not clear this STID's reservation.
7. Same-STID committed store to same line clears the reservation.
8. Same-STID committed store to different line does not clear it.
9. Flush before LR completion suppresses reservation set.
10. Flush before SC commit suppresses status, store, and reservation clear.
11. Backpressure holds SC request stable.
12. Unsupported size/opcode reports trap/unsupported and does not complete
    silently.

Integration gates:

```sh
bash tools/chisel/run_chisel_tests.sh --only ScalarLrScReservationOwner
bash tools/chisel/run_chisel_tests.sh --only ScalarLSU
bash tools/chisel/run_chisel_tests.sh --only ReducedRobCompletionArbiter
bash tools/chisel/build_chisel.sh
```

Natural lock-window gate:

- Compile a scalar lock loop using `LR.W`/`SC.W`.
- Run it through QEMU and Chisel natural mode.
- Require at least one SC success and one SC failure.
- Require matching committed rows for LR sign extension, SC status, and final
  memory value.
- Require no release-without-complete rows and no unsupported LR/SC rows.

## Open Divergences and Blockers

- QEMU `LR.W` zero-extends today; Sail sign-extends. Chisel must implement
  Sail and file QEMU divergence evidence if cross-check fails on negative
  32-bit LR values.
- QEMU reservation matching is exact address plus size; Sail uses 64-byte line.
  Chisel first slice may keep a diagnostic same-size guard, but promotion must
  converge on line-granularity unless ISA changes.
- Coherence invalidation by other cores or DMA is not modeled in current Sail
  or QEMU. First Chisel slice is local STID/core only.
- `far` remote behavior is not specified enough for hardware promotion.

## Promotion Criteria

The scalar LR/SC slice is promotable only when:

- unit and integration gates pass;
- `LR.W` sign-extension matches Sail;
- SC success/failure status is correct;
- successful SC memory mutation is irreversible only after committed-store
  acceptance;
- failed SC never mutates STQ/SCB/L1D;
- recovery and backpressure tests prove no side effect is dropped or duplicated;
- the natural lock-window gate passes with nonzero success and failure counts.

## 2026-07-24 Standalone Owner Packet

Implemented packet:

- `chisel/src/main/scala/linxcore/lsu/ScalarLrScReservationOwner.scala`
- `chisel/src/test/scala/linxcore/lsu/ScalarLrScReservationOwnerSpec.scala`

The standalone owner is deliberately memory-side and is not an ALU shortcut.
It retains one SC request under backpressure, sets reservations only on
accepted LR completion, clears reservations only on accepted SC completion or a
matching committed-store invalidation, and suppresses LR/SC side effects under
precise flush. Context invalidation or nuke flush clears all local reservations.

Verified evidence:

```sh
bash tools/chisel/run_chisel_tests.sh --only ScalarLrScReservationOwner
bash tools/chisel/run_chisel_tests.sh --only ScalarLSU
bash tools/chisel/run_chisel_tests.sh --only ReducedRobCompletionArbiter
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
bash tools/chisel/build_chisel.sh
```

All commands passed. The owner unit gate covers LR.W sign-extension,
same-STID 64-byte reservation, SC success/failure status, clear on every
accepted SC attempt, committed-store invalidation, precise identity flush
suppression, same-cycle invalidate-over-SC-success priority, and SC request
backpressure retention.

`tools/generate/opcode_catalog_lib.py` now classifies `lr_*` as `LOAD/atomic`
and `sc_*` as `STORE/atomic`, while preserving the historical opcode IDs with
`OP_ID_CATEGORY_OVERRIDES`. Regenerated frontend decode therefore routes
`OP_LR_*` and `OP_SC_*` to LSU dispatch (`dispatch = 4`) without renumbering
the ISA/QEMU/core shared IDs.

Live integration remains blocked on explicit interfaces, not on hardware
feasibility:

- `chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala` exposes ordinary store
  and load paths, but no dedicated `lrReq/scReq` accepted-transaction contract
  that can atomically join LR data/reservation or SC status/reservation/store.
- `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
  still has a reduced commit-bypass store path where
  `reducedStoreMemoryAcceptedVec(base) := commitStoreValid`. That path has no
  downstream ready/valid acceptance proof, so SC status 0 cannot be made
  conditional on an irreversible accepted store through this bypass.
- `chisel/src/main/scala/linxcore/backend/ReducedRobCompletionArbiter.scala`
  currently arbitrates execute, replay, service, and template completion
  sources. LR/SC needs either a named LSU completion source or a widened
  arbiter contract.
- `chisel/src/main/scala/linxcore/top/ScalarLoadCompletionROBBridge.scala`
  covers ordinary load completion ownership; it does not cover SC status
  writeback plus conditional committed-store acceptance as one transaction.

Do not run CoreMark/Dhrystone natural as LR/SC progress evidence until those
interfaces are wired. The expected next live step is to add a named LSU
completion and route SC through a conditional committed-store transaction
boundary that reports success only on real store acceptance.
