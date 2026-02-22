# QEMU vs LinxCore Cross-Check Report

## Summary

- Mode: `failfast`
- Compared commits: `356`
- QEMU rows loaded: `33024`
- LinxCore rows loaded: `1000`
- Metadata skipped (QEMU): `11`
- Metadata skipped (LinxCore): `2`
- Mismatches: `2`
- C.BSTOP count (QEMU): `1`
- C.BSTOP count (LinxCore): `2`
- C.BSTOP inflation ratio: `2.000`
- Inflation warning: `False`

## First Mismatch

- seq: `358`
- field: `pc`
- qemu: `0x15a4c`
- dut: `0x15a72`
- qemu_pc: `0x15a4c`
- dut_pc: `0x15a72`
- qemu_insn: `0x800`
- dut_insn: `0x0`
- qemu_row: `{"dst": {"data": 0, "reg": 0, "valid": 0}, "insn": 2048, "len": 2, "mem": {"addr": 0, "is_store": 0, "rdata": 0, "size": 0, "valid": 0, "wdata": 0}, "next_pc": 88654, "pc": 88652, "seq": 367, "src0": {"data": 0, "reg": 0, "valid": 0}, "src1": {"data": 0, "reg": 0, "valid": 0}, "trap": {"arg0": 0, "cause": 0, "valid": 0}, "wb": {"data": 0, "rd": 0, "valid": 0}}`
- dut_row: `{"dst": {"data": 0, "reg": 63, "valid": 0}, "insn": 0, "len": 2, "mem": {"addr": 0, "is_store": 0, "rdata": 0, "size": 0, "valid": 0, "wdata": 0}, "next_pc": 88692, "pc": 88690, "seq": 358, "src0": {"data": 0, "reg": 63, "valid": 0}, "src1": {"data": 0, "reg": 63, "valid": 0}, "trap": {"arg0": 0, "cause": 0, "valid": 0}, "wb": {"data": 0, "rd": 63, "valid": 0}}`

## Files

- Report JSON: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/verify/coremark/report/crosscheck_report.json`
- Mismatches JSON: `/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/verify/coremark/report/crosscheck_mismatches.json`
