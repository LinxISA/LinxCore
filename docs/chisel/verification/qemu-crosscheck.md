# QEMU Cross-Check Adapter

## Current Contract

The supported generated-RTL comparison entrypoint is
`tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`. It emits
`LinxCoreFrontendTraceTop`, builds `tools/chisel/frontend_trace_top_tb.cpp`,
produces QEMU-shaped reference and DUT JSONL, and calls the neutral
`tools/chisel/run_chisel_qemu_crosscheck.sh` comparator.

Displaced reduced-top, trace-replay, fetch/RF/ALU, and fixture-builder wrappers
were deleted. Historical evidence that names them is not a current command
contract and must not be copied into a gate.

## Tools

- `tools/chisel/run_chisel_frontend_trace_top_xcheck.sh`
- `tools/chisel/frontend_trace_top_tb.cpp`
- `tools/chisel/commit_trace_jsonl.h`
- `tools/chisel/trace_schema_adapter.py`
- `tools/chisel/run_chisel_qemu_crosscheck.sh`
- `tools/trace/crosscheck_qemu_linxcore.py`

## Evidence Manifest

The common comparator writes `crosscheck_manifest.json` beside its normalized
traces and reports. The manifest records raw and normalized inputs, selected
row bounds, mismatch status, first divergence, and repository provenance.
Failure still emits a manifest, so a nonzero result remains diagnosable but is
never passing evidence.

## Current Gates

```bash
bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh --dry-run
python3 tools/chisel/check_crosscheck_wrapper_dependencies.py
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_frontend_trace_top_lint.sh
bash tools/chisel/run_chisel_frontend_trace_top_xcheck.sh
```

The `--dry-run` gate is intentionally non-RTL: it validates the canonical
emitter object, emitted top name, C++ harness binding, and exact common
cross-check arguments without invoking SBT, FIRRTL/CIRCT, or Verilator.

## Normalized Fields

The adapter requires the architectural commit fields:

```text
pc insn len wb_valid wb_rd wb_data
src0_valid src0_reg src0_data
src1_valid src1_reg src1_data
dst_valid dst_reg dst_data
mem_valid mem_is_store mem_addr mem_wdata mem_rdata mem_size
trap_valid trap_cause traparg0 next_pc
```

It also preserves fixed DUT identity sidebands when present. The shared C++
writer owns field spelling and default values; harnesses own only their pin to
row projection.

## Proof Boundary

The canonical frontend trace top is a bounded reduced verification surface.
It is useful for frontend-packet to commit-row plumbing and comparator closure;
it is not evidence for a bootable full core, cache hierarchy, or unrestricted
QEMU/CoreMark execution.
