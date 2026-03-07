# Janus Stage Migration Map

This map records ownership migration from older LinxCore blocks into explicit
Janus stage and component files.

## Frontend and IFU

- Legacy `src/bcc/frontend/ifetch.py` -> `src/bcc/ifu/f0.py`, `src/bcc/ifu/f2.py`
- Legacy `src/bcc/frontend/ibuffer.py` -> `src/bcc/ifu/f3.py`
- Legacy predictor/control split -> `src/bcc/ifu/f1.py`, `src/bcc/ifu/ctrl.py`
- IFU output align -> `src/bcc/ifu/f4.py`

Important current-top note:

- these IFU files remain useful bring-up modules
- the active top-level bring-up lane currently uses host/QEMU input into
  `src/top/modules/ib.py` rather than a functional IFU path

## OOO

- Decode entry -> `src/bcc/ooo/dec1.py`
- UOP refine -> `src/bcc/ooo/dec2.py`
- Rename entry -> `src/bcc/ooo/ren.py`
- Scheduler stage1 -> `src/bcc/ooo/s1.py`
- Scheduler stage2 -> `src/bcc/ooo/s2.py`
- ROB lifecycle -> `src/bcc/ooo/rob.py`
- PC side buffer -> `src/bcc/ooo/pc_buffer.py`
- Flush authority -> `src/bcc/ooo/flush_ctrl.py`
- Commit-side rename update -> `src/bcc/ooo/renu.py`

## IEX

- IEX lane orchestrator -> `src/bcc/iex/iex.py`
- ALU lane -> `src/bcc/iex/iex_alu.py`
- BRU lane -> `src/bcc/iex/iex_bru.py`
- FSU lane -> `src/bcc/iex/iex_fsu.py`
- AGU lane -> `src/bcc/iex/iex_agu.py`
- STD lane -> `src/bcc/iex/iex_std.py`

## LSU

- LIQ -> `src/bcc/lsu/liq.py`
- LHQ -> `src/bcc/lsu/lhq.py`
- STQ -> `src/bcc/lsu/stq.py`
- SCB -> `src/bcc/lsu/scb.py`
- MDB -> `src/bcc/lsu/mdb.py`
- L1D interface -> `src/bcc/lsu/l1d.py`

## Block Control, PE, and TMU

- BISQ -> `src/bcc/bctrl/bisq.py`
- BROB -> `src/bcc/bctrl/brob.py`
- BCTRL -> `src/bcc/bctrl/bctrl.py`
- BRENU -> `src/bcc/bctrl/brenu.py` (legacy helper, not instantiated in the
  canonical top-level path)
- TMU NOC -> `src/tmu/noc/node.py`, `src/tmu/noc/pipe.py`
- TMU tile register -> `src/tmu/sram/tilereg.py`
- TMA -> `src/tma/tma.py`
- CUBE -> `src/cube/cube.py`
- TAU -> `src/tau/tau.py`

## Integration

- Canonical top wrapper -> `src/linxcore_top.py`
- Canonical stage-linked integration -> `src/top/top.py`
- Active top export and DFX integration root -> `src/top/modules/export_core.py`
- Thin backend shell -> `src/bcc/backend/backend.py`
- Backend authoritative composition body ->
  `src/bcc/backend/modules/trace_export_core.py`
- Block/jump authority docs ->
  `docs/architecture/{block_fabric_contract.md,branch_recovery_rules.md,linxisa_block_control_flow.md}`

## Frontend milestone notes

- Real I-cache ownership still exists in `src/bcc/ifu/icache.py`
- explicit IB stage handoff remains valid for auxiliary stage-map work
- the active top-level bring-up lane exports a hard-cut host/QEMU instruction
  supply path instead of using the IFU chain as the canonical functional source
