#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


THIS = Path(__file__).resolve()
HELPER = THIS.with_name("test_exec_uop_srcrtype.py")
SPEC = importlib.util.spec_from_file_location("exec_uop_test_support", HELPER)
assert SPEC is not None and SPEC.loader is not None
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)

from common.exec_uop import exec_uop  # noqa: E402
from common.isa import OP_BSTART_ICALL  # noqa: E402


def main() -> int:
    pc = 0x4000
    uimm5 = 13
    scaled_delta = uimm5 << 1
    m = support.ValueCircuit()
    out = exec_uop(
        m,
        op=support.Value(OP_BSTART_ICALL, width=12),
        pc=support.Value(pc, width=64),
        imm=support.Value(scaled_delta, width=64),
        srcl_val=support.Value(0, width=64),
        srcr_val=support.Value(0, width=64),
        srcr_type=support.Value(0, width=2),
        shamt=support.Value(0, width=6),
        srcp_val=support.Value(0, width=64),
        consts=support._value_consts(m),
    )
    expected = pc + 2 + scaled_delta
    actual = int(out.alu)
    assert actual == expected, (
        f"BSTART.ICALL return label mismatch: got 0x{actual:x}, "
        f"expected 0x{expected:x}"
    )
    print("ok: BSTART.ICALL returns P+2+(uimm5<<1)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
