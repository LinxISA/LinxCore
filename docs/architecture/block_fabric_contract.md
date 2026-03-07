# Block Fabric Contract

This document defines the authoritative block-command fabric used by the current
LinxCore top-level graph:

- `/Users/zhoubot/LinxCore/src/top/modules/export_core.py`
- `/Users/zhoubot/LinxCore/src/bcc/bctrl/bisq.py`
- `/Users/zhoubot/LinxCore/src/bcc/bctrl/bctrl.py`
- `/Users/zhoubot/LinxCore/src/bcc/bctrl/brob.py`
- `/Users/zhoubot/LinxCore/src/{tma,cube,vec,tau}/*`
- `/Users/zhoubot/LinxCore/src/tmu/*`

`/Users/zhoubot/LinxCore/src/bcc/bctrl/brenu.py` remains as a legacy helper,
but it is not instantiated by the canonical top-level graph and is not the
authority for current tag or epoch semantics.

## Command envelope

All PE lanes observe the same command envelope:

- `cmd_valid`
- `cmd_ready`
- `cmd_kind[2:0]`
- `cmd_tag[7:0]`
- `cmd_tile[5:0]`
- `cmd_payload[63:0]`
- `cmd_src_rob[5:0]`
- `cmd_bid[63:0]`
- `cmd_epoch[7:0]`

Current routing rules:

- `cmd_tag` is derived from the block identity: `cmd_tag = cmd_bid[7:0]`
- `cmd_bid` is carried end-to-end and is the authoritative block identity for
  issue, response routing, flush, and debug
- `cmd_epoch` is a compatibility field in the current path and is driven as
  zero by `/Users/zhoubot/LinxCore/src/bcc/bctrl/bctrl.py`
- `cmd_src_rob` is preserved for completion attribution and DFX; it is not the
  command routing key

`bctrl` asserts exactly one of
`{cmd_tma_valid_bctrl, cmd_cube_valid_bctrl, cmd_vec_valid_bctrl, cmd_tau_valid_bctrl}`
when the selected lane is ready.

## Response envelope

Each PE returns:

- `rsp_valid`
- `rsp_tag[7:0]`
- `rsp_status[3:0]`
- `rsp_data0[63:0]`
- `rsp_data1[63:0]`
- `rsp_trap_valid`
- `rsp_trap_cause[31:0]`

`bctrl` muxes lane responses into a single BROB input stream. Response routing
is still keyed by the low tag bits, but correctness depends on the live BROB
window and the BID-based rule `rsp_tag == live_bid[7:0]` for the matching entry.

## BISQ

`/Users/zhoubot/LinxCore/src/bcc/bctrl/bisq.py` is the canonical block issue
queue.

Behavioral contract:

- true FIFO storage for `kind/bid/payload/tile/src_rob`
- enqueue from backend CMD path only when `enq_valid && bisq_enq_ready`
- dequeue only when `bctrl` successfully issues to the selected PE lane
- flush freezes the queue while `flush_valid_bisq=1`
- on `flush_fire_bisq`, keep `bid <= flush_bid` and clear `bid > flush_bid`

The current BISQ implementation assumes the younger blocks occupy a dequeue-order
suffix, so flush trimming is implemented as "keep a live prefix, kill the
younger suffix."

## BCTRL

`/Users/zhoubot/LinxCore/src/bcc/bctrl/bctrl.py` owns lane selection and shared
command/response wiring.

Behavioral contract:

- consume only the BISQ head entry
- select one target lane from `cmd_kind`
- issue only when the selected lane is ready
- fan out the same command envelope to the shared PE stage taps and the
  lane-specific taps
- emit `issue_fire_brob`, `issue_bid_brob`, and `issue_src_rob_brob` for BROB
  tracking
- keep `cmd_tag == bid[7:0]` and `cmd_epoch == 0` on every issued command

There is no separate tag allocator in the active path.

## BROB

`/Users/zhoubot/LinxCore/src/bcc/bctrl/brob.py` is the canonical block reorder
buffer for engine-backed block completion.

Strict contract:

- default entries: `128`
- BID encoding:
  - `slot_id = BID[6:0]`
  - `uniq = BID[63:7]`
- allocation happens on `BSTART` dispatch
- issue is legal only if the queried BID is live and `issue_tag == issue_bid[7:0]`
- PE response marks the entry `ready` or `exception`, but the command remains
  allocated until block retire
- `BSTOP` retire clears the active entry
- flush keeps `bid <= flush_bid` and kills `bid > flush_bid`

BROB also provides the active block lifecycle view consumed by commit:

- `allocated`
- `ready`
- `retired`
- `exception`

That query interface is the reason `BSTOP` retirement can be blocked until the
engine-backed block is ready or known-exceptional.

## PE and TMU bring-up policy

Current PE/TMU behavior is still bring-up oriented, but the block-fabric
envelope is stable:

- ready/valid transport is explicit
- lane behavior is deterministic and debuggable
- the routing contract must stay stable even if the internal PE datapaths evolve

When wording diverges, this document and the block/BID rules in the LinxCore
skill are the authority for the active LinxCore path.
