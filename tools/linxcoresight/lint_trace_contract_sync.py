#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from common.stage_tokens import LINXTRACE_PIPELINE_SCHEMA_ID, LINXTRACE_STAGE_ID_ORDER, LINXTRACE_STAGE_ORDER_CSV


def _read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing file: {path}")
    return path.read_text(encoding="utf-8", errors="replace")


def _parse_cpp_stage_array(text: str, name: str) -> list[str]:
    m = re.search(rf"{re.escape(name)}\s*=\s*\{{(.*?)\}};", text, flags=re.S)
    if not m:
        raise SystemExit(f"cannot find C++ stage array: {name}")
    return re.findall(r'"([^"]+)"', m.group(1))


def _must_contain(path: Path, text: str, needle: str) -> None:
    if needle not in text:
        raise SystemExit(f"{path}: missing required token '{needle}'")


def _assert_equal(label: str, got, exp) -> None:
    if got != exp:
        raise SystemExit(f"{label} mismatch:\n  got: {got}\n  exp: {exp}")


def main() -> int:
    stage_order = list(LINXTRACE_STAGE_ID_ORDER)

    tb_cpp = ROOT / "tb" / "tb_linxcore_top.cpp"
    tb_text = _read(tb_cpp)
    tb_order = _parse_cpp_stage_array(tb_text, "kTraceStageNames")
    _assert_equal("tb kTraceStageNames", tb_order, stage_order)

    builder_py = ROOT / "tools" / "trace" / "build_linxtrace_view.py"
    builder_text = _read(builder_py)
    _must_contain(builder_py, builder_text, "LINXTRACE_STAGE_ID_ORDER")
    _must_contain(builder_py, builder_text, "LINXTRACE_PIPELINE_SCHEMA_ID")
    _must_contain(builder_py, builder_text, "\"format\": \"linxtrace.v1\"")

    linter_py = ROOT / "tools" / "linxcoresight" / "lint_linxtrace.py"
    linter_text = _read(linter_py)
    _must_contain(linter_py, linter_text, "linxtrace.v1")
    _must_contain(linter_py, linter_text, "contract mismatch")

    sight_root = Path("/Users/zhoubot/LinxCoreSight")
    parser_ts = sight_root / "src" / "lib" / "linxtrace.ts"
    parser_text = _read(parser_ts)
    _must_contain(parser_ts, parser_text, "linxtrace.v1")
    _must_contain(parser_ts, parser_text, "contract_id")

    print(
        "linxtrace contract sync passed: "
        f"schema={LINXTRACE_PIPELINE_SCHEMA_ID} stages={len(stage_order)} order={LINXTRACE_STAGE_ORDER_CSV}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

