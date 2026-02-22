# Block Fabric Contract

This document defines the authoritative BCC block-command fabric interfaces used by:

- `src/bcc/bctrl/{bisq,brenu,bctrl,brob}.py`
- `src/tmu/*`
- `src/{tma,cube,vec,tau}/*`

## Command Envelope

All PE lanes use the same envelope:

- `cmd_valid`
- `cmd_ready`
- `cmd_kind[2:0]`
- `cmd_tag[7:0]`
- `cmd_tile[5:0]`
- `cmd_payload[63:0]`
- `cmd_src_rob[5:0]`
- `cmd_epoch[7:0]`

`bctrl` asserts one of `{cmd_tma_valid_bctrl, cmd_cube_valid_bctrl, cmd_vec_valid_bctrl, cmd_tau_valid_bctrl}` only when selected lane is ready.

## Response Envelope

Each PE returns:

- `rsp_valid`
- `rsp_tag[7:0]`
- `rsp_status[3:0]`
- `rsp_data0[63:0]`
- `rsp_data1[63:0]`
- `rsp_trap_valid`
- `rsp_trap_cause[31:0]`

`bctrl` muxes lane responses into a single BROB input stream.

## BISQ

- true head/tail queue storage
- enqueue from backend CMD pipe via `cmd_to_bisq_stage_*`
- backend receives `bisq_enq_ready` and only marks CMD uops done when enqueue fire occurs (`cmd_valid & bisq_enq_ready`)
- dequeue only when bctrl issues (`cmd_fire_bctrl`)

## BRENU

- allocates command tag and epoch
- epoch increments on tag wrap
- exposes issue readiness

## BROB

- tracks outstanding command tags
- stores `src_rob` mapping per issued tag slot
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
