# Janus Stage Migration Map

This map records ownership migration from legacy LinxCore blocks into explicit Janus stage/component files.

## Frontend / IFU

- Legacy `src/bcc/frontend/ifetch.py` -> `src/bcc/ifu/f0.py`, `src/bcc/ifu/f2.py`
- Legacy `src/bcc/frontend/ibuffer.py` -> `src/bcc/ifu/f3.py`
- Legacy predictor/control split -> `src/bcc/ifu/f1.py`, `src/bcc/ifu/ctrl.py`
- IFU output align -> `src/bcc/ifu/f4.py`

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

## Block Control + PEs + TMU

- BISQ -> `src/bcc/bctrl/bisq.py`
- BRENU -> `src/bcc/bctrl/brenu.py`
- BROB -> `src/bcc/bctrl/brob.py`
- BCTRL -> `src/bcc/bctrl/bctrl.py`
- TMU NOC -> `src/tmu/noc/node.py`, `src/tmu/noc/pipe.py`
- TMU tile register -> `src/tmu/sram/tilereg.py`
- TMA -> `src/tma/tma.py`
- CUBE -> `src/cube/cube.py`
- TAU -> `src/tau/tau.py`

## Integration

- Canonical stage-linked integration: `src/top/top.py`
- Canonical wrapper entrypoint retained: `src/top/top.py`
