#!/usr/bin/env python3
"""Emit the dense instruction bytes used by the frontend fetch RF/ALU xcheck."""

import argparse
from pathlib import Path


def put_le(buf: bytearray, offset: int, value: int, size: int) -> None:
    end = offset + size
    if end > len(buf):
        buf.extend(b"\x00" * (end - len(buf)))
    buf[offset:end] = value.to_bytes(size, "little")


def fixture_bytes() -> bytes:
    add = 0x00000005 | (3 << 7) | (4 << 15) | (5 << 20)
    addi = 0x00000015 | (6 << 7) | (3 << 15) | (0x7FF << 20)
    c_movr = 0x0006 | (6 << 6) | (5 << 11)

    buf = bytearray()
    put_le(buf, 0, add, 4)
    put_le(buf, 4, addi, 4)
    put_le(buf, 8, c_movr, 2)
    return bytes(buf)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="binary image to write")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    data = fixture_bytes()
    output.write_bytes(data)
    print(f"frontend-fetch-rf-alu-fixture-memory={output} bytes={len(data)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
