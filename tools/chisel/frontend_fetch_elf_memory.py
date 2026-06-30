#!/usr/bin/env python3
"""Extract sparse fetch-memory bytes from ELF64 little-endian LOAD segments."""

import argparse
import struct
import tempfile
from pathlib import Path
from typing import BinaryIO, Iterable, Iterator, Tuple


ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
PT_LOAD = 1


class ElfFormatError(RuntimeError):
    pass


def parse_int(text: str) -> int:
    return int(text, 0)


def check_range(data: bytes, offset: int, size: int, what: str) -> None:
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ElfFormatError(f"{what} is outside the ELF file")


def iter_load_segments(data: bytes) -> Iterator[Tuple[int, bytes, int]]:
    if len(data) < 64:
        raise ElfFormatError("file is too small for an ELF64 header")
    if data[:4] != ELF_MAGIC:
        raise ElfFormatError("missing ELF magic")
    if data[4] != ELFCLASS64:
        raise ElfFormatError("only ELF64 is supported")
    if data[5] != ELFDATA2LSB:
        raise ElfFormatError("only little-endian ELF is supported")

    (
        _ident,
        _etype,
        _emachine,
        _eversion,
        _entry,
        phoff,
        _shoff,
        _flags,
        _ehsize,
        phentsize,
        phnum,
        _shentsize,
        _shnum,
        _shstrndx,
    ) = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)

    if phentsize < 56:
        raise ElfFormatError(f"program header entry is too small: {phentsize}")

    for index in range(phnum):
        offset = phoff + index * phentsize
        check_range(data, offset, 56, f"program header {index}")
        (
            p_type,
            _p_flags,
            p_offset,
            p_vaddr,
            p_paddr,
            p_filesz,
            p_memsz,
            _p_align,
        ) = struct.unpack_from("<IIQQQQQQ", data, offset)
        if p_type != PT_LOAD:
            continue
        if p_memsz < p_filesz:
            raise ElfFormatError(f"LOAD segment {index} has memsz < filesz")
        check_range(data, p_offset, p_filesz, f"LOAD segment {index}")
        base = p_paddr if p_paddr != 0 else p_vaddr
        payload = data[p_offset : p_offset + p_filesz]
        bss_zeros = p_memsz - p_filesz
        yield base, payload, bss_zeros


def write_sparse_memory(
    segments: Iterable[Tuple[int, bytes, int]],
    out: BinaryIO,
    include_bss: bool,
    max_bytes: int,
) -> Tuple[int, int]:
    total = 0
    segment_count = 0
    out.write(b"# linxcore.frontend_fetch_memory.v1\n")
    for base, payload, bss_zeros in segments:
        segment_count += 1
        for index, byte in enumerate(payload):
            total += 1
            if total > max_bytes:
                raise ElfFormatError(f"memory image exceeds --max-bytes={max_bytes}")
            out.write(f"0x{base + index:016x} 0x{byte:02x}\n".encode("ascii"))
        if include_bss:
            for index in range(bss_zeros):
                total += 1
                if total > max_bytes:
                    raise ElfFormatError(f"memory image exceeds --max-bytes={max_bytes}")
                out.write(f"0x{base + len(payload) + index:016x} 0x00\n".encode("ascii"))
    return segment_count, total


def extract_elf(elf_path: Path, output_path: Path, include_bss: bool, max_bytes: int) -> Tuple[int, int]:
    data = elf_path.read_bytes()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as out:
        segment_count, total = write_sparse_memory(iter_load_segments(data), out, include_bss, max_bytes)
    if segment_count == 0:
        raise ElfFormatError("ELF has no PT_LOAD segments")
    if total == 0:
        raise ElfFormatError("ELF PT_LOAD segments contain no memory bytes")
    return segment_count, total


def make_self_test_elf() -> bytes:
    payload = bytes([0x85, 0x01, 0x52, 0x00, 0x15, 0x83, 0xF1, 0x7F, 0x86, 0x29])
    header_size = 64
    ph_size = 56
    payload_offset = 0x100

    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        ELF_MAGIC + bytes([ELFCLASS64, ELFDATA2LSB, 1]) + b"\x00" * 9,
        2,
        0xF3,
        1,
        0x1000,
        header_size,
        0,
        0,
        header_size,
        ph_size,
        1,
        0,
        0,
        0,
    )
    phdr = struct.pack(
        "<IIQQQQQQ",
        PT_LOAD,
        0x5,
        payload_offset,
        0x1000,
        0x1000,
        len(payload),
        len(payload) + 2,
        0x1000,
    )
    image = bytearray(header + phdr)
    image.extend(b"\x00" * (payload_offset - len(image)))
    image.extend(payload)
    return bytes(image)


def run_self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        elf_path = tmp_path / "fixture.elf"
        mem_path = tmp_path / "fixture.mem"
        elf_path.write_bytes(make_self_test_elf())
        segments, total = extract_elf(elf_path, mem_path, include_bss=True, max_bytes=1024)
        lines = [line for line in mem_path.read_text().splitlines() if line and not line.startswith("#")]
        expected = [
            "0x0000000000001000 0x85",
            "0x0000000000001001 0x01",
            "0x0000000000001002 0x52",
            "0x0000000000001003 0x00",
            "0x0000000000001004 0x15",
            "0x0000000000001005 0x83",
            "0x0000000000001006 0xf1",
            "0x0000000000001007 0x7f",
            "0x0000000000001008 0x86",
            "0x0000000000001009 0x29",
            "0x000000000000100a 0x00",
            "0x000000000000100b 0x00",
        ]
        if segments != 1 or total != len(expected) or lines != expected:
            raise AssertionError(f"unexpected self-test extraction: segments={segments} total={total} lines={lines}")
    print("frontend-fetch-elf-memory-self-test: ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elf", help="input ELF64 little-endian program")
    parser.add_argument("--output", help="output sparse memory image")
    parser.add_argument("--max-bytes", type=parse_int, default=64 * 1024 * 1024)
    parser.add_argument("--no-bss-zero-fill", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()
    if not args.elf or not args.output:
        parser.error("--elf and --output are required unless --self-test is used")

    try:
        segments, total = extract_elf(
            Path(args.elf),
            Path(args.output),
            include_bss=not args.no_bss_zero_fill,
            max_bytes=args.max_bytes,
        )
    except ElfFormatError as exc:
        raise SystemExit(f"error: {exc}") from exc

    print(f"frontend-fetch-elf-memory={args.output} load_segments={segments} bytes={total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
