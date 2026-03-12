# LinxCore Top-Level Composition Design

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/overview.md`
- `rtl/LinxCore/docs/architecture/microarchitecture.md`
- `rtl/LinxCore/docs/architecture/module-catalog.md`
- `rtl/LinxCore/docs/architecture/pipeline-stage-catalog.md`

## Scope

This document is the composition-focused deep dive for the canonical LinxCore
top hierarchy implemented in:

- `rtl/LinxCore/src/linxcore_top.py`
- `rtl/LinxCore/src/top/top.py`
- `rtl/LinxCore/src/top/modules/export_core.py`

It explains how the top-level modules compose the architectural stages and
subsystems defined by the canonical LinxCore spec. It does not redefine stage
semantics; those remain normative in the canonical contract pages.

## Top hierarchy roles

### `linxcore_top.py`

- Exports the canonical top module name `linxcore_top`.
- Instantiates the top export shell and attaches the commit, block, and
  pipeview probe modules.
- Owns top-level bring-up parameters such as memory size, instruction-fetch
  bundle width, and cache geometry aliases.

### `top/modules/export_core.py`

- Provides the top export shell used by the current pyCircuit and testbench
  flows.
- Composes backend, memory, probes, block-control, LSU, and engine boundaries.
- Supports the host-fed instruction-buffer path used by QEMU/runner-driven
  bring-up while preserving canonical downstream stage ownership.

### `top/top.py`

- Provides the full explicit IFU composition path.
- Instantiates the canonical `F0`, `F1`, `F2`, `F3`, `F4` stage modules, memory
  wrappers, backend, block-control path, LSU, and engine integrations.
- Serves as the reference stage-to-stage wiring map for stage-connectivity and
  trace contract alignment.

## Composition rules

- Top-level composition may wrap, export, or probe stage owners, but it must
  not collapse architecturally visible stages into anonymous glue.
- If a bring-up shell bypasses a producer path, the replacement path must still
  preserve the same downstream named stage boundary seen by decode, trace, and
  compare tooling.
- Probe/export modules must consume real owner state. They must not invent a
  second synthetic pipeline.

## Frontend composition

In the full IFU path, the top-level composition is:

- `F0`: PC-select
- `F1`: I-cache lookup control
- `F2`: I-cache data staging and ECC
- `F3`: stitch/predict/boundary/template control
- `IB`: instruction-buffer decoupling
- `F4`: 4-slot decode window

In the export/bring-up path, the native IFU source may be replaced by a
host-fed instruction-buffer module. That substitution is allowed only because
the downstream architectural stage ownership remains intact at `IB/F4`.

## Decode, dispatch, and backend composition

The top-level composition must preserve the promoted stage ownership from `F0`
through the baseline issue/wakeup slice:

- `D1`: decode and ordering-id allocation
- `D2`: rename request/translation and ROB-visible boundary resolution
- `D3`: renamed-uop latch
- `S1`: post-rename dispatch preparation
- `S2`: IQ entry write
- `P1`: IQ pick
- `I1`: operand-read planning and RF arbitration
- `I2`: issue-confirm and IQ deallocation
- `E1`: first execute stage
- `W1`: late wakeup and resolve

The backend family may realize these through finer-grained submodules, but the
named architectural boundaries must remain visible to connectivity, trace, and
tooling.

## Block-control composition

The top-level composition must preserve explicit boundaries for:

- `BISQ`
- `BCTRL`
- `BROB`

Required composition consequences:

- `BID` is allocated by `BROB`.
- `cmd_tag == bid[7:0]` across the command fabric.
- Flush and rollback use full-width `BID` ordering.
- Block completion remains `scalar_done && (needs_engine ? engine_done : 1)`.

## Memory and LSU composition

The top-level composition must preserve:

- split instruction/data memory visibility through the memory wrappers,
- LSU ownership of ordered `LSID` issue behavior,
- committed-store drain behavior as a subordinate implementation of the
  architecturally visible commit/memory contract.

Load/store ordering, replay, forwarding, and MMIO visibility are defined by the
canonical microarchitecture contract and must not be redefined here.

## Engine composition

The top-level composition integrates `VEC`, `TMA`, `CUBE`, `TAU`, and the TMU
subsystems under the same block/BID retirement model as scalar work.

No engine defines a second architectural command or retirement machine. Engine
issue, completion, exception, and flush behavior must remain visible through
the canonical block-control and ROB-facing interfaces.

## Trace and observability composition

Top-level observability is built from dedicated probe/export modules:

- commit probe
- block probe
- pipeview probe

These modules exist to expose canonical owner state to testbench and LinxTrace
tooling. They are observability consumers, not architectural stage owners.

## Related deep dives

Use these for mechanism-specific detail without treating them as replacement
contracts:

- `branch_recovery_rules.md`
- `linxisa_block_control_flow.md`
- `block_fabric_contract.md`
- `lsid_memory_ordering.md`
- `code_template_unit.md`
- `stages/BROB.md`
