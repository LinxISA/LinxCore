# CoreMark `C.BSTOP` Inflation Gap (Baseline + Fix)

## Baseline divergence (before fix)

- Workload:
  - ELF: `/Users/zhoubot/linx-isa/workloads/generated/elf/coremark.elf`
  - MEMH: `/Users/zhoubot/LinxCore/generated/konata/coremark/coremark_from_elf.memh`
- First mismatch occurred at commit `seq=0`.
- Mismatch field was `wb_data` on `FENTRY` SP writeback:
  - QEMU behavior: `sp -= stacksize`
  - LinxCore behavior (old): `sp -= (stacksize + 64)`
- After early divergence, LinxCore quickly reached `pc=0` and retired many `insn=0,len=2` records, which appear as `C.BSTOP` flood in Konata.
- First-1000 window symptom:
  - QEMU `C.BSTOP` count: `0`
  - LinxCore `C.BSTOP` count: very high (observed ~`966`)

## Root causes addressed

1. Backend template frame adjust used a hardcoded callframe addend (`+64`) in `/Users/zhoubot/LinxCore/src/bcc/backend/engine.py`, while QEMU defaults callframe addend to `0`.
2. Boundary/macro metadata filtering in cross-check tooling incorrectly dropped real architectural commits.
3. Block split control-flow around in-body `C.BSTART.STD` was not aligned with QEMU boundary restart semantics.

## Fix implemented

- LinxCore template frame addend now defaults to `0` and is configurable:
  - env: `LINXCORE_CALLFRAME_SIZE` (must be non-negative multiple of 8)
- Build/run scripts now propagate and stamp the configured value:
  - `/Users/zhoubot/LinxCore/tools/generate/update_generated_linxcore.sh`
  - `/Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh`

## Validation flow

1. Generate QEMU commit trace:
   - `/Users/zhoubot/LinxCore/tools/qemu/run_qemu_commit_trace.sh`
2. Run LinxCore TB with optional Konata and xcheck:
   - `/Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh`
3. Compare first 1000 commits:
   - `/Users/zhoubot/LinxCore/tools/trace/crosscheck_qemu_linxcore.py`
4. Gate tests:
   - `/Users/zhoubot/LinxCore/tests/test_coremark_crosscheck_1000.sh`
   - `/Users/zhoubot/LinxCore/tests/test_cbstop_inflation_guard.sh`

## Current status

- `test_coremark_crosscheck_1000.sh`: `ok` (0 mismatches).
- `test_cbstop_inflation_guard.sh`: `ok` (`qemu=0`, `dut=0`).
- `test_cosim_smoke.sh`: `ok`.
- No remaining CoreMark first-1000 branch divergence in the current canonical flow.
