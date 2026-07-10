# Block Fabric Contract

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/microarchitecture.md`
- `rtl/LinxCore/docs/architecture/interfaces.md`

This document defines the target authoritative BCC block-command fabric
interfaces for:

- `src/bcc/bctrl/{bisq,brenu,bctrl,brob}.py`
- `src/tmu/*`
- `src/{tma,cube,vec,tau}/*`

Current pyCircuit/Chisel lanes that omit STID, carry a fixed 64-bit BID, or use
numeric BID flush comparisons are legacy, non-conforming interfaces. They must
migrate all producers, queues, engines, response routes, and tests together;
their present port shape does not weaken this contract.

## Command Envelope

All PE lanes use the same envelope:

- `cmd_valid`
- `cmd_ready`
- `cmd_kind[2:0]`
- `cmd_stid[STID_W-1:0]`
- `cmd_tag[BID_W-1:0]` = `bid`
- `cmd_tile[5:0]`
- `cmd_payload[63:0]`
- `cmd_src_rid[RID_W-1:0]`
- optional `cmd_epoch[TXN_EPOCH_W-1:0]`

`BROB_ENTRIES` is the depth of each STID's BROB ring. The default 256 entries
makes `BID_W=8`. A fixed 8-bit fabric therefore carries BID directly, while
STID selects the ring. A smaller configuration zero-extends BID; a larger
configuration must widen both command and response tag interfaces.

The selected BISQ head drives one lane's `cmd_valid` and complete payload
independently of `cmd_ready`, and holds them stable until
`cmd_fire = cmd_valid && cmd_ready`. BISQ dequeue, BROB issue, and outstanding
transaction allocation occur only on `cmd_fire`. The current pulse-on-ready
`bctrl.py` behavior is legacy and must not be copied into the target interface.

`cmd_src_rid` is the ROB slot identity within `cmd_stid`; the owning ROB row
must not be reused until the command's response/completion no longer needs the
mapping, unless a separate generation/transaction identity protects reuse.

## Response Envelope

Each PE returns:

- `rsp_valid`
- `rsp_ready`
- `rsp_stid[STID_W-1:0]`
- `rsp_tag[BID_W-1:0]`
- optional `rsp_epoch[TXN_EPOCH_W-1:0]`
- `rsp_status[3:0]`
- `rsp_data0[63:0]`
- `rsp_data1[63:0]`
- `rsp_trap_valid`
- `rsp_trapno[63:0]`
- `rsp_traparg0[63:0]`
- `rsp_trap_bi`

Each lane holds response payload stable until `rsp_valid && rsp_ready`.
`bctrl` uses a per-lane skid/collector structure that can either accept every
simultaneous lane response or backpressure lanes independently before
serializing up to `BROB_COMPLETE_PER_CYCLE` events. A priority mux that drops
unselected valid responses is forbidden.

The response routing identity is `(rsp_stid, rsp_tag)`, where `rsp_tag` is BID,
not an independently allocated command ID. Omitting STID is legal only on a
physically STID-dedicated lane whose context cannot be confused. An interface
that returns no separate transaction identity is legal only when that STID's
BROB does not reuse the BID slot until every command and possible
late/duplicate response for the old occupant has drained.
If the transport needs earlier reuse or can produce late responses, it must
echo a separate transaction epoch (or equivalent transaction ID) in both
directions. That field protects transport reuse but never becomes part of BID.
BROB rejects a response whose `(STID,BID)` is not live or whose echoed
transaction identity does not match the live outstanding command.

The trap fields carry the complete engine-originated precise-trap envelope.
BROB may add owner context, but it must not reconstruct a missing architectural
trap number, `TRAPARG0`, or BI state from a truncated private cause.

The baseline permits one outstanding top-level non-scalar command per
`(STID,BID)`. The selected engine aggregates any internal micro-operations and
returns one non-scalar-done response. If an implementation allows multiple
simultaneous commands for one block, every command/response carries a separate
transaction ID (for example epoch plus sequence), BROB tracks the expected
response count, and duplicates cannot advance completion. Bare BID is not
sufficient to distinguish same-block transactions.

## BISQ

- true head/tail queue storage
- enqueue from backend CMD pipe via `cmd_to_bisq_stage_*`
- backend receives `bisq_enq_ready` and only marks CMD uops done when enqueue fire occurs (`cmd_valid & bisq_enq_ready`)
- dequeue only when bctrl issues (`cmd_fire_bctrl`)

## BRENU

- derives command routing identity directly from `(STID,BID)`; it does not
  allocate an independent BID/tag namespace
- tracks issue availability and any separate transport epoch needed to
  disambiguate transaction reuse
- exposes issue readiness

## BROB

- tracks outstanding command tags
- stores `src_rob` mapping per issued `(STID,BID)` slot
- prevents per-STID BID-slot reuse until response and cleanup quiescence, unless a
  separately echoed transaction identity proves stale-response rejection
- emits `brob_to_rob_stage_*` completion info

## TMU

- NOC pipe exposes ready/valid transport
- tile register file provides tile-indexed read/write behavior
- PEs can use tile data from TMU path while keeping command envelope stable

## Functional-first PE Policy

Current phase is deterministic functional behavior, not final production datapaths:

- TMA/CUBE/VEC/TAU implement fixed-latency command execution
- each lane has command accept state and delayed response
- arithmetic kernels remain deterministic and lane-specific for debugability
