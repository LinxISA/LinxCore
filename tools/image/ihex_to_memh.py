#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Iterable, Iterator


def _map_bringup_addr(addr: int, *, mem_bytes: int) -> int:
    # Keep mapping consistent with:
    # - src/mem/mem2r1w.py (_map_addr)
    # - tb/tb_linxcore_top.cpp (mapBringupMemAddrEff)
    #
    # This maps the ELF/QEMU address space into a bounded bring-up memory.
    if mem_bytes <= 0:
        return 0
    low_mask = mem_bytes - 1
    stack_window = max(1, mem_bytes // 4)
    data_window = max(1, mem_bytes // 4)
    stack_offset = mem_bytes - stack_window
    data_offset = max(0, mem_bytes - stack_window - data_window)
    stack_base = 0x0000_0000_07FE_0000
    data_base = 0x0000_0000_0100_0000
    if addr >= stack_base:
        return (((addr - stack_base) & (stack_window - 1)) + stack_offset) & low_mask
    if addr >= data_base:
        return (((addr - data_base) & (data_window - 1)) + data_offset) & low_mask
    return addr & low_mask


def _iter_ihex_data(path: Path) -> Iterator[tuple[int, bytes]]:
    base = 0
    for lineno, raw in enumerate(path.read_text().splitlines(), start=1):
        line = raw.strip()
        if not line:
            continue
        if not line.startswith(":"):
            raise ValueError(f"{path}:{lineno}: expected ':'")
        if len(line) < 11:
            raise ValueError(f"{path}:{lineno}: short record")

        count = int(line[1:3], 16)
        addr = int(line[3:7], 16)
        rectype = int(line[7:9], 16)
        data_hex = line[9 : 9 + count * 2]
        if len(data_hex) != count * 2:
            raise ValueError(f"{path}:{lineno}: truncated data")

        if rectype == 0x00:
            yield base + addr, (bytes.fromhex(data_hex) if count else b"")
            continue
        if rectype == 0x01:
            break
        if rectype == 0x04:
            if count != 2:
                raise ValueError(f"{path}:{lineno}: type 04 expects 2 data bytes")
            base = int(data_hex, 16) << 16
            continue
        if rectype == 0x02:
            if count != 2:
                raise ValueError(f"{path}:{lineno}: type 02 expects 2 data bytes")
            base = int(data_hex, 16) << 4
            continue


def _write_memh(records: Iterable[tuple[int, bytes]], out_path: Path, *, mem_bytes: int) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        run_addr: int | None = None
        run_next: int = 0
        run_bytes: list[int] = []

        def flush() -> None:
            nonlocal run_addr, run_next, run_bytes
            if run_addr is None:
                return
            f.write(f"@{run_addr:08x}\n")
            for b in run_bytes:
                f.write(f"{b:02x}\n")
            run_addr = None
            run_next = 0
            run_bytes = []

        for addr, data in records:
            if not data:
                continue
            for i, b in enumerate(data):
                eff = _map_bringup_addr(addr + i, mem_bytes=mem_bytes)
                if run_addr is None:
                    run_addr = eff
                    run_next = eff
                if eff != run_next:
                    flush()
                    run_addr = eff
                    run_next = eff
                run_bytes.append(int(b))
                run_next = eff + 1
        flush()


def main() -> int:
    ap = argparse.ArgumentParser(description="Convert Intel HEX to tb-compatible memh (@ADDR + byte tokens).")
    ap.add_argument("input_hex", type=Path)
    ap.add_argument("output_memh", type=Path)
    ap.add_argument("--mem-bytes", type=int, default=(1 << 20), help="Bring-up memory size in bytes (default: 1MiB).")
    args = ap.parse_args()

    _write_memh(_iter_ihex_data(args.input_hex), args.output_memh, mem_bytes=int(args.mem_bytes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
