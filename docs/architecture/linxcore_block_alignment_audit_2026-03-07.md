# LinxCore Block and Jump Alignment Audit

Audit date: 2026-03-07

## Scope

This note records the implementation-versus-document audit used to realign
LinxCore architecture docs with the current Linx ISA block-structure contract.

## Current authority

Implementation authority for the current LinxCore path:

- `/Users/zhoubot/LinxCore/src/top/modules/export_core.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/trace_export_core.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/commit_slot_step.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/block_meta_step.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/recovery_checks.py`
- `/Users/zhoubot/LinxCore/src/bcc/bctrl/{bisq.py,bctrl.py,brob.py}`

ISA authority for the same topics:

- `/Users/zhoubot/linx-isa/docs/architecture/v0.3-architecture-contract.md`
- `/Users/zhoubot/linx-isa/docs/architecture/linxcore/microarchitecture.md`
- `/Users/zhoubot/linx-isa/docs/reference/linxisa-call-ret-contract.md`

## Outdated assumptions removed from docs

- `backend/engine.py` is no longer the authoritative backend composition root
- `brenu.py` is not the active tag or epoch authority in the canonical top path
- current command routing is BID-based with `cmd_tag = bid[7:0]`
- `cmd_epoch` is a compatibility field driven as zero in the active block-fabric
  path
- the active top-level bring-up lane uses host/QEMU input into the IB rather
  than a functional IFU path as the architectural control-flow authority

## Confirmed alignment points

- architectural redirect is boundary-authoritative and happens at commit
- `BSTART` and `BSTOP` are ROB-visible boundary uops
- `BSTART.CALL` has no implicit `ra` write
- returning call headers require adjacent `SETRET` or `C.SETRET`
- `RET`, `IND`, and `ICALL` require explicit `SETC.TGT`
- `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` are treated as standalone macro
  block boundaries
- dynamic recovery targets must resolve to legal block starts
- known non-block targets raise the precise trap
  `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`
- BID-based flush keeps `bid <= flush_bid` and kills `bid > flush_bid`

## Follow-up guidance

If a future milestone restores a functional IFU as the canonical instruction
source, the architecture docs must be updated again to describe exactly how
frontend prediction metadata composes with the existing commit-authoritative
block redirect contract. Until then, the backend boundary path remains the
authoritative control-flow owner.
