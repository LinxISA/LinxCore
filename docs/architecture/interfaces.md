# LinxCore External Interface Contracts

This document defines the externally consumed LinxCore interface contracts for
model integration, trace generation, and cross-tool synchronization.

The contracts below are normative for pyCircuit, testbench, trace tooling,
viewer tooling, and any other producer or consumer that observes LinxCore
through non-ISA interfaces.

## pyCircuit-LinxCore interface contract (LC-IF-PYC-001)

The pyCircuit/LinxCore integration contract is versioned and gate-enforced.

Contract artifacts:

- `docs/bringup/contracts/pyc_linxcore_interface_contract.json`
- `docs/bringup/contracts/pyc_linxcore_interface_contract.md`

Rules:

- Contract version follows `MAJOR.MINOR`.
- Backward-compatible additions increment `MINOR`.
- Breaking field removals, renames, or semantic redefinitions increment
  `MAJOR`.
- Gate tooling rejects unversioned interface breaks.

The pyCircuit interface contract is the required compatibility boundary for:

- pyCircuit-generated DUTs,
- C++ testbench integration,
- compare tooling,
- commit-trace consumers used by QEMU correlation and bring-up gates.

## Required commit payload contract (LC-IF-PYC-002)

Required commit fields from `pyc_linxcore_interface_contract.json`:

- `cycle`, `pc`, `insn`
- `src0_valid`, `src0_reg`, `src0_data`
- `src1_valid`, `src1_reg`, `src1_data`
- `dst_valid`, `dst_reg`, `dst_data`
- `wb_valid`, `wb_rd`, `wb_data`
- `mem_valid`, `mem_is_store`, `mem_addr`, `mem_wdata`, `mem_rdata`, `mem_size`
- `trap_valid`, `trap_cause`, `traparg0`, `next_pc`

Required environment controls:

- `PYC_COMMIT_TRACE`
- `PYC_BOOT_PC`
- `PYC_MEM_BYTES`
- `PYC_MAX_CYCLES`

Canonical producers and consumers live under:

- `rtl/LinxCore/tb/`
- `rtl/LinxCore/tools/trace/`
- `tools/bringup/check_pycircuit_interface_contract.py`

### Commit stream rules

- Commit payloads describe architecturally retiring work only.
- Multi-commit cycles must retain per-slot ordering semantics.
- Empty placeholder rows are forbidden in the architecturally compared commit
  stream.
- Commit-side trap, memory, and writeback payloads must remain coherent with
  the retiring uop or macro event they describe.
- Source-operand validity, register identity, and value correlation are
  required contract payload, not optional debug metadata.
- Destination correlation must remain explicit even when `wb_*` aliases are
  also present for backward consumer convenience.

### DUT control rules

- Boot, memory-size, and commit-trace control environment variables are part of
  the external contract, not incidental implementation details.
- A contract-visible change in required environment controls must be versioned
  the same way as a field-level interface change.

## Block command fabric contract

The block-command interface remains part of the externally visible LinxCore
behavior because backend, `BISQ`, `BCTRL`, and engine paths must agree on a
single command envelope.

Required block-command fields:

- `cmd_valid`
- `cmd_kind[2:0]`
- `cmd_tag[7:0]`
- `cmd_tile[5:0]`
- `cmd_payload[63:0]`
- `cmd_src_rob`
- `cmd_bid`

Required rules:

- `cmd_tag == bid[7:0]`
- response tags route back to the correct `BROB` entry
- `BID` remains visible through enqueue, issue, completion, and flush rollback

### Block command response rules

- Command responses must preserve source-to-`BROB` routing correctness.
- Tag reuse must remain synchronized with `BID` lifetime and flush semantics.
- Engine-local completion metadata must not bypass block-level retirement rules.

Detailed command-lane behavior remains in:

- `rtl/LinxCore/docs/architecture/block_fabric_contract.md`

## Memory and MMIO visibility contract

The external LinxCore memory-visible contract includes:

- architecturally visible load/store commit payloads,
- committed-store drain visibility where applicable,
- MMIO-visible UART and exit signaling used by canonical bring-up flows.

Rules:

- MMIO-visible effects must remain explicit and traceable.
- Memory metadata exported through commit trace must remain sufficient for
  architectural compare and debugging flows.
- Memory-side debug metadata may be additive, but it must not redefine the
  required commit payload semantics.

## LinxTrace schema contract (LC-IF-TRACE-001)

Trace schema governance:

- canonical schema contract: `docs/bringup/contracts/trace_schema.md`
- runtime format contract: `rtl/LinxCore/docs/trace/linxtrace_v1.md`
- pipeline refresh rule: `rtl/LinxCore/docs/trace/linxtrace_pipeline_refresh_rule.md`
- producer-side schema validation: `tools/bringup/validate_trace_schema.py`
- SemVer compatibility gate: `tools/bringup/check_trace_semver_compat.py`

Rules:

- LinxTrace output is a single `*.linxtrace` JSONL file.
- The first non-empty record is `{"type":"META", ...}`.
- `MAJOR` schema mismatch is a hard failure.
- `MINOR` requires producer version to satisfy consumer expectations.
- Breaking trace changes require major bump and migration checks.

### Required trace content

- The trace must define stage, lane, and row catalogs in-band.
- Unknown stage, lane, or row identifiers are hard errors.
- Stage ownership must come from real producer-side stage state, not
  viewer-side reconstruction.
- Trace rows must preserve block and uop identity sufficiently for
  LinxCoreSight and compare tooling to render without inventing lifecycle
  state.

## Trace compatibility contract (LC-IF-TRACE-002)

- `linxtrace.v1` remains stable for additive changes.
- Major-version bump is mandatory for incompatible field or semantic changes.
- Compatibility checks must fail fast on major mismatch.
- Producer-side fixes are preferred over compare-tool masking when trace
  content diverges from architectural behavior.

Backward-compatible additions must not silently change the meaning of existing
fields or lifecycle ordering.

## Cross-tool synchronization contract (LC-IF-SYNC-001)

The following must stay synchronized when trace or pipeline contracts change:

- `rtl/LinxCore/src/common/stage_tokens.py`
- `rtl/LinxCore/tb/tb_linxcore_top.cpp`
- `rtl/LinxCore/tools/trace/build_linxtrace_view.py`
- `rtl/LinxCore/tools/linxcoresight/lint_linxtrace.py`
- `rtl/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py`

Viewer-side contract sync is validated through LinxTrace gates.

The published superproject LinxCore mirror pages are part of this
cross-tool/publication contract: they must remain synchronized with the
canonical submodule docs after contract edits.

## Scope boundary

This document covers external LinxCore interface governance only:

- pyCircuit contract,
- trace schema contract,
- cross-tool synchronization rules.

Detailed LinxCore microarchitectural interface contracts, including the
two-layer block machine, `BROB`-facing resolve behavior, raw engine fabric, and
engine/block-type mapping, belong under:

- `rtl/LinxCore/docs/architecture/`
- `rtl/LinxCore/docs/architecture/microarchitecture.md`

The promoted stage contracts from `F0` through the baseline issue/wakeup slice
(`S1`, `S2`, `P1`, `I1`, `I2`, `E1`, `W1`) are captured normatively in the
canonical microarchitecture page, while this document remains limited to
external and tool-facing governance.

## Interface change control

- Interface-visible changes must update contract artifacts first.
- Gate rows in `rtl/LinxCore/docs/architecture/verification-matrix.md` are the
  release blocker for interface promotion.
- Any contract-major bump must include migration notes and dual-lane evidence.
- Published mirrors in `docs/architecture/linxcore/` must be regenerated after
  contract edits.

Deep-dive implementation notes may expand a mechanism, but they must not create
an alternative compatibility contract outside this document and the referenced
machine-readable artifacts.
