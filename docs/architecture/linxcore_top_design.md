# LinxCore Top Design (Memory-First Bring-up)

## Scope

This document describes the current canonical LinxCore top-level graph and the
architecture-visible ownership boundaries that matter for block control, jump
handling, memory, and trace/export behavior.

Authority order for implementation mapping:

- `/Users/zhoubot/LinxCore/src/linxcore_top.py`
- `/Users/zhoubot/LinxCore/src/top/top.py`
- `/Users/zhoubot/LinxCore/src/top/modules/export_core.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/backend.py`
- `/Users/zhoubot/LinxCore/src/bcc/backend/modules/trace_export_core.py`

Older references to a monolithic `backend/engine.py` are obsolete for the
current design. Legacy Janus stage modules still exist for stage-map continuity,
DFX, and migration tracking, but they are not the architectural authority for
commit, redirect, block metadata, or trace export.

## Canonical module graph

Top-level structure:

- `linxcore_top.py` is the canonical wrapper used by emit/build flows
- `top/top.py` instantiates the real top module hierarchy
- `top/modules/export_core.py` is the active integration root for:
  - host/QEMU instruction supply
  - IB
  - backend composition
  - memory bridge
  - block fabric
  - DFX and LinxTrace export
- `bcc/backend/backend.py` is a thin shell
- `bcc/backend/modules/trace_export_core.py` owns the real backend composition
  and the architecture-visible control-state plumbing

Supporting ownership blocks:

- block issue fabric:
  `/Users/zhoubot/LinxCore/src/bcc/bctrl/{bisq.py,bctrl.py,brob.py}`
- top-level IB:
  `/Users/zhoubot/LinxCore/src/top/modules/ib.py`
- trace/DFX composition:
  `/Users/zhoubot/LinxCore/src/top/modules/{trace_event.py,stage_probe.py,dfx_pipeview.py}`
- UID allocation:
  `/Users/zhoubot/LinxCore/src/common/uid_allocator.py`
- memory model:
  `/Users/zhoubot/LinxCore/src/mem/mem2r1w.py`

## Frontend and instruction supply

The active bring-up lane uses a hard-cut frontend.

- the functional IFU and I-cache are not the canonical instruction source in the
  current top-level graph
- host/QEMU supplies F4-like packets into the on-chip IB
- backend consumes the IB output as its instruction stream
- backend redirect flushes the IB so control-flow recovery still uses the same
  architectural redirect path

This is why architecture docs must not describe the current LinxCore path as
"IFU prediction authoritative." Prediction may exist in auxiliary code or older
modules, but architectural control flow is committed by the backend boundary
logic.

## Memory-first behavior

The current bring-up path still follows the memory-first split introduced during
the OOO refactor:

- `LinxCoreMem2R1W` provides separate instruction and data views, with host
  writes mirrored into both memories
- committed stores are decoupled from retire and drain through the LSU path
- MMIO commit behavior remains explicit for the bring-up addresses used by the
  testbench/runtime harness

This memory-first policy is still a bring-up choice, but it is part of the
current canonical top graph.

## Block identity and engine-backed block lifecycle

LinxCore tracks two related block identities:

- `block_uid`: dynamic block instance identity used for trace/debug
- `block_bid`: BROB/BISQ/engine identity used for block-fabric routing and flush

Current block-fabric rules:

- `BSTART` dispatch allocates a new BID from BROB
- CMD enqueue into BISQ carries `cmd_bid`
- `bctrl` forwards that BID to PE paths and derives `cmd_tag = bid[7:0]`
- `cmd_epoch` is currently a compatibility field and is driven as zero
- BROB tracks `allocated`, `ready`, `retired`, and `exception` per live BID
- `BSTOP` retirement is gated by the active BROB state for engine-backed blocks

Flush semantics are BID-based throughout the active path:

- keep `bid <= flush_bid`
- kill `bid > flush_bid`

That rule applies to BROB, BISQ, and any other queue that carries BID.

## Block and jump control design

The current implementation matches the Linx ISA block-structure contract:

- `BSTART` and `BSTOP` are ROB-visible boundary uops
- boundary redirect is chosen at commit, not directly by execute-stage BRU
- BRU only records deferred correction when `setc.cond` mismatches committed
  block metadata
- committed block metadata is updated by
  `/Users/zhoubot/LinxCore/src/bcc/backend/modules/block_meta_step.py`
- redirect packaging is done by
  `/Users/zhoubot/LinxCore/src/bcc/backend/modules/commit_redirect.py`

Call/return and dynamic-target rules:

- `BSTART.CALL` has no implicit `ra` write
- returning call headers require adjacent `SETRET` or `C.SETRET`
- `RET`, `IND`, and `ICALL` require explicit `SETC.TGT` in the same block
- `SETRET` and `SETC.TGT` are the architectural source of dynamic control-flow
  targets

Macro boundary rules:

- `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK` are treated as standalone macro
  blocks
- committed live block metadata treats those macro boundaries as fall-through
  blocks until the next explicit `BSTART` head installs new metadata

Recovery safety rule:

- a dynamic target used for correction or recovery must resolve to a legal block
  start in the PC buffer
- a known target that is not a legal block start raises the precise trap
  `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`

## DFX, LinxTrace, and cross-check ownership

Current DFX ownership is top-export centric.

- `m.debug_occ(...)` sites are emitted from
  `/Users/zhoubot/LinxCore/src/top/modules/export_core.py`
  through reusable child modules in
  `/Users/zhoubot/LinxCore/src/top/modules/trace_event.py`
- backend per-uop identity, commit payload, replay, and block metadata come from
  `/Users/zhoubot/LinxCore/src/bcc/backend/modules/trace_export_core.py`
- ROB lineage storage remains in
  `/Users/zhoubot/LinxCore/src/bcc/backend/state.py`
- template child-uop identity comes from
  `/Users/zhoubot/LinxCore/src/bcc/backend/code_template_unit.py`

LinxTrace v1 ownership:

- raw/testbench emission:
  `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp`
- canonical builder:
  `/Users/zhoubot/LinxCore/tools/trace/build_linxtrace_view.py`
- contract lint:
  `/Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py`

## Related design documents

These documents hold the architecture-visible details that this top-design note
depends on:

- `/Users/zhoubot/LinxCore/docs/architecture/block_fabric_contract.md`
- `/Users/zhoubot/LinxCore/docs/architecture/branch_recovery_rules.md`
- `/Users/zhoubot/LinxCore/docs/architecture/linxisa_block_control_flow.md`
- `/Users/zhoubot/LinxCore/docs/architecture/lsid_memory_ordering.md`
- `/Users/zhoubot/linx-isa/docs/reference/linxisa-call-ret-contract.md`

If wording diverges, Linx ISA architecture docs remain normative and LinxCore
implementation docs must be updated to match them.
