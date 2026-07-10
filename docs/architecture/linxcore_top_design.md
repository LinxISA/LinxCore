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
- Instantiates the current frontend modules, memory wrappers, backend,
  block-control path, LSU, and engine integrations. The current module names
  are not yet one-to-one with canonical `F0 -> F1 -> F2 -> F3 -> F4/IB`
  ownership.
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

- `F0`: thread arbitration, redirect selection, and next-PC control
- `F1`: translation and I-cache request/lookup launch
- `F2`: fetch-return staging and integrity/ECC handling
- `F3`: variable-length assembly, cross-line carry, and byte-stream ordering
- `F4/IB`: final predecode/prediction and block-boundary metadata, plus the
  fourth fetch-stage instruction buffer

In the export/bring-up path, the native IFU source may be replaced by a
host-fed F4/IB module. That substitution must preserve the same F4/IB-to-D1
contract and must not create a serial `IB -> F4` stage.

Current `f1.py`/`f2.py`/`f3.py` responsibilities do not yet match those target
boundaries one-to-one. The `f3.py` internal IB contributes to F4/IB state;
`f4.py` and Chisel `F4DecodeWindow` are legacy names for a D1-ingress
continuous-view helper and do not define F4.

## Decode, dispatch, and backend composition

The top-level composition must preserve this ownership:

- `D1`: early decode, exception detection, split/fuse recognition, and group
  formation
- `D2`: operand extraction, boundary resolution, and resource-demand
  preparation
- `D3`: atomic admission, physical rename, and ordering-ID acceptance
- `S1`: admitted-uop speculative-buffer capture
- `S2`: IQ entry allocation/write
- `S3/IQ`: resident and pick-visible IQ state
- `P0`: optional registered preselect
- `P1`: final IQ pick
- `I1`: operand-read planning and RF arbitration
- `I2`: bypass selection and issue confirmation; only non-speculative,
  non-cancellable transfers deallocate here
- `E1..En`: absolute per-pipe execute cycles
- `W1..Wn`: producer-relative actual data-bypass/result/writeback ages overlaid
  on E stages, with earlier speculative wakeup separately E-qualified
- `R0..R4`: completion intake, R2 commit/flush publication, recovery, and R4
  restart

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
- `BROB_ENTRIES` is per STID; `BID_W = ceil(log2(BROB_ENTRIES))`; each default
  256-entry ring uses an 8-bit BID.
- Shared block interfaces carry `(STID,BID)` separately.
  `(cmd_stid,cmd_tag) == (stid,bid)` across the command fabric; narrower
  configurations zero-extend BID onto the default 8-bit tag bus.
- Wrap and age are separate per-STID BROB state. Flush and rollback consume an
  STID-qualified BROB kill set and never use unsigned BID comparisons.
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
