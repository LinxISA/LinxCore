#!/usr/bin/env python3
"""Build reduced scalar RF seed rows from a QEMU commit JSONL prefix."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any


SCHEMA = "linxcore.frontend_fetch_rf_seed.v1"
MASK64 = (1 << 64) - 1


def parse_int(value: Any) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"expected integer-like value, got {value!r}")


def row_bool(row: dict[str, Any], key: str) -> bool:
    value = row.get(key)
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value != 0
    if isinstance(value, str):
        return value.lower() in {"1", "true", "yes"}
    return False


def row_int(row: dict[str, Any], key: str) -> int:
    return parse_int(row.get(key, 0))


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_no}: expected JSON object")
            if row.get("type") == "META":
                continue
            if row.get("valid") in (0, False, "0", "false", "False"):
                continue
            rows.append(row)
    return rows


def boundary_index(rows: list[dict[str, Any]], *, before_row: int | None, before_pc: int | None) -> int:
    if before_row is not None:
        if before_row < 0 or before_row > len(rows):
            raise ValueError(f"--before-row out of range: {before_row}")
        return before_row
    if before_pc is not None:
        for index, row in enumerate(rows):
            if row_int(row, "pc") == before_pc:
                return index
        raise ValueError(f"--before-pc did not match any row: 0x{before_pc:x}")
    raise ValueError("one of --before-row or --before-pc is required")


def observe_source(
    state: dict[int, int],
    corrections: list[str],
    *,
    reg: int,
    data: int,
    row_index: int,
    source_name: str,
    max_arch_reg: int,
) -> None:
    if reg >= max_arch_reg:
        return
    data &= MASK64
    prior = state.get(reg)
    if prior is not None and prior != data:
        corrections.append(
            f"row {row_index} {source_name} reg {reg} observed 0x{data:x} after state 0x{prior:x}"
        )
    state[reg] = data


def apply_write(state: dict[int, int], row: dict[str, Any], *, max_arch_reg: int) -> None:
    dst_valid = row_bool(row, "dst_valid") or row_bool(row, "wb_valid")
    if not dst_valid:
        return
    reg = row_int(row, "dst_reg") if "dst_reg" in row else row_int(row, "wb_rd")
    if reg >= max_arch_reg:
        return
    data = row_int(row, "dst_data") if "dst_data" in row else row_int(row, "wb_data")
    state[reg] = data & MASK64


def build_seed(rows: list[dict[str, Any]], *, stop_index: int, max_arch_reg: int) -> tuple[dict[int, int], list[str]]:
    state: dict[int, int] = {}
    corrections: list[str] = []
    for row_index, row in enumerate(rows[:stop_index]):
        if row_bool(row, "src0_valid"):
            observe_source(
                state,
                corrections,
                reg=row_int(row, "src0_reg"),
                data=row_int(row, "src0_data"),
                row_index=row_index,
                source_name="src0",
                max_arch_reg=max_arch_reg,
            )
        if row_bool(row, "src1_valid"):
            observe_source(
                state,
                corrections,
                reg=row_int(row, "src1_reg"),
                data=row_int(row, "src1_data"),
                row_index=row_index,
                source_name="src1",
                max_arch_reg=max_arch_reg,
            )
        apply_write(state, row, max_arch_reg=max_arch_reg)
    return state, corrections


def write_seed(path: Path, *, state: dict[int, int], source: Path, stop_index: int, stop_pc: int | None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        f.write(
            json.dumps(
                {
                    "type": "META",
                    "schema": SCHEMA,
                    "source": str(source),
                    "stop_index": stop_index,
                    "stop_pc": None if stop_pc is None else f"0x{stop_pc:x}",
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        )
        for reg, data in sorted(state.items()):
            f.write(
                json.dumps(
                    {
                        "arch_reg": reg,
                        "data": f"0x{data & MASK64:x}",
                        "source": "qemu-prefix",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            )


def validate_seed(path: Path, *, max_arch_reg: int) -> list[str]:
    errors: list[str] = []
    seen: dict[int, int] = {}
    saw_meta = False
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                errors.append(f"{line_no}: row is not an object")
                continue
            if row.get("type") == "META":
                saw_meta = saw_meta or row.get("schema") == SCHEMA
                continue
            if "arch_reg" not in row:
                errors.append(f"{line_no}: missing arch_reg")
                continue
            if "data" not in row:
                errors.append(f"{line_no}: missing data")
                continue
            try:
                reg = row_int(row, "arch_reg")
                data = row_int(row, "data") & MASK64
            except (KeyError, TypeError, ValueError) as exc:
                errors.append(f"{line_no}: invalid seed row: {exc}")
                continue
            if reg < 0 or reg >= max_arch_reg:
                errors.append(f"{line_no}: arch_reg out of reduced GPR range: {reg}")
            if reg in seen and seen[reg] != data:
                errors.append(f"{line_no}: conflicting data for arch_reg {reg}")
            seen[reg] = data
    if not saw_meta:
        errors.append(f"missing {SCHEMA} metadata row")
    if not seen:
        errors.append("seed contains no register rows")
    return errors


def self_test() -> None:
    rows = [
        {"pc": 0x1000, "src0_valid": 1, "src0_reg": 1, "src0_data": 0x80},
        {"pc": 0x1002, "dst_valid": 1, "dst_reg": 2, "dst_data": 0x90},
        {"pc": 0x1004, "src0_valid": 1, "src0_reg": 2, "src0_data": 0x90},
        {"pc": 0x2000, "dst_valid": 1, "dst_reg": 1, "dst_data": 0x99},
    ]
    idx = boundary_index(rows, before_row=None, before_pc=0x2000)
    assert idx == 3
    state, corrections = build_seed(rows, stop_index=idx, max_arch_reg=24)
    assert corrections == []
    assert state == {1: 0x80, 2: 0x90}
    corrected_state, corrected = build_seed(
        [
            {"pc": 0x1000, "dst_valid": 1, "dst_reg": 1, "dst_data": 0x70},
            {"pc": 0x1004, "src0_valid": 1, "src0_reg": 1, "src0_data": 0x80},
        ],
        stop_index=2,
        max_arch_reg=24,
    )
    assert corrected_state == {1: 0x80}
    assert len(corrected) == 1
    with tempfile.TemporaryDirectory(prefix="linxcore-rf-seed-") as tmp_s:
        out = Path(tmp_s) / "seed.jsonl"
        write_seed(out, state=state, source=Path("qemu.jsonl"), stop_index=idx, stop_pc=0x2000)
        assert validate_seed(out, max_arch_reg=24) == []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Input QEMU commit JSONL trace")
    parser.add_argument("--output", type=Path, help="Output RF seed JSONL")
    parser.add_argument("--before-row", type=int, default=None, help="Stop before this zero-based raw row index")
    parser.add_argument("--before-pc", default=None, help="Stop before the first row with this PC")
    parser.add_argument("--max-arch-reg", type=int, default=24)
    parser.add_argument("--strict-source-conflicts", action="store_true")
    parser.add_argument("--validate-only", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("frontend-fetch-rf-seed self-test: ok")
        return 0
    if args.validate_only is not None:
        errors = validate_seed(args.validate_only, max_arch_reg=args.max_arch_reg)
        if errors:
            for error in errors:
                print(f"error: {error}")
            return 1
        print(f"frontend-fetch-rf-seed: ok seed={args.validate_only}")
        return 0
    if args.input is None or args.output is None:
        parser.error("--input and --output are required unless --self-test or --validate-only is used")

    rows = load_jsonl(args.input)
    stop_pc = parse_int(args.before_pc) if args.before_pc is not None else None
    stop_index = boundary_index(rows, before_row=args.before_row, before_pc=stop_pc)
    state, corrections = build_seed(rows, stop_index=stop_index, max_arch_reg=args.max_arch_reg)
    if corrections and args.strict_source_conflicts:
        for correction in corrections:
            print(f"error: {correction}")
        return 1
    write_seed(args.output, state=state, source=args.input, stop_index=stop_index, stop_pc=stop_pc)
    print(
        f"frontend-fetch-rf-seed={args.output} regs={len(state)} "
        f"stop_index={stop_index} source_corrections={len(corrections)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
