# LinxCore Top-Level Composition Design

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/overview.md`
- `rtl/LinxCore/docs/architecture/microarchitecture.md`
- `rtl/LinxCore/docs/architecture/module-catalog.md`
- `rtl/LinxCore/docs/architecture/pipeline-stage-catalog.md`
- `rtl/LinxCore/docs/architecture/ifu.md`

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
- Instantiates I-SIDE, B-SIDE, memory wrappers, backend, block-control path,
  LSU, and engine integrations. The required IFU order is
  `I-F0 -> I-F1 -> I-F2 -> I-F3 -> I-F4 -> Instruction Buffer -> D1`
  plus decoupled `B-F0 -> B-F1 -> B-F2 -> B-F3 -> B-F4`.
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

The full IFU has two decoupled engines.

I-SIDE composition:

- `I-F0`: PC/request capture and identity allocation
- `I-F1`: parallel ITLB and L1I lookup launch
- `I-F2`: translation/cache result join, inner flush, and miss/refill handling
- `I-F3`: cache-line capture, byte-stream alignment, and cross-line carry
- `I-F4`: 2/4/6/8-byte instruction assembly, BSTART/BSTOP-only predecode,
  64-bit expansion, and Instruction Buffer write
- `Instruction Buffer`: per-STID queue after I-F4
- `D1`: reads up to four fixed 64-bit entries, carries the complete prediction
  record on every valid lane, and performs the first full
  opcode/operand/immediate decode

B-SIDE composition:

- `B-F0`: L0/NLP and history snapshot
- `B-F1`: uBTB and RAS
- `B-F2`: PBTB/BTB and BIM
- `B-F3`: short/medium TAGE and IBTB lookup
- `B-F4`: static prediction from matched I-F4 boundary metadata, long TAGE,
  final IBTB/loop result, final arbitration, retained response, and the last
  prediction-driven correction
- B-F4 correction inner flush marks predictor recovery pending; its returned
  canonical prune restores request-owned history, applies the corrected
  conditional delta, and restarts I-F0. After the final record is sealed,
  Dispatch/BRU mismatch uses BRU flush/recover and I-F0 restart

In the export/bring-up path, the native IFU source may be replaced by a
host-fed Instruction Buffer writer. That substitution must preserve fixed
64-bit Instruction Buffer entries and the four-wide D1 input contract.

Current `f1.py`/`f2.py`/`f3.py` responsibilities do not yet match those target
boundaries one-to-one. Implementation promotion requires five observable
I-SIDE stage owners, a distinct Instruction Buffer, and independently
backpressured B-SIDE state.

## Decode, dispatch, and backend composition

The top-level composition must preserve this ownership:

- `D1`: four-wide fixed-64-bit full decode, exception detection, split/fuse
  recognition, and group formation
- `D2`: boundary resolution and resource-demand preparation
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
