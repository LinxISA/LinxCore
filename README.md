# LinxCore RTL

## Scope
`rtl/LinxCore` is the RTL and co-simulation implementation lane for LinxISA core behavior and trace parity.

## Upstream
- Repository: `https://github.com/LinxISA/LinxCore`
- Merge-back target branch: `main`

## What This Submodule Owns
- RTL and generated model sources
- Co-sim execution harnesses and trace/debug tooling
- Microarchitecture tests for pipeline/ROB/branch behavior

## Canonical Build and Test Commands
Run from `/Users/zhoubot/linx-isa/rtl/LinxCore`.

```bash
cmake -S . -B build
cmake --build build -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

bash tests/test_cosim_smoke.sh
bash tests/test_opcode_parity.sh
bash tests/test_trace_schema_and_mem.sh
```

## LinxISA Integration Touchpoints
- Co-sim and trace parity loops with emulator/compiler outputs
- Architectural alignment with `docs/bringup/contracts/trace_schema.md`
- Cross-stack bring-up visibility through shared test artifacts

## Related Docs
- `/Users/zhoubot/linx-isa/docs/project/navigation.md`
- `/Users/zhoubot/linx-isa/docs/bringup/`
- `/Users/zhoubot/linx-isa/docs/bringup/contracts/trace_schema.md`
