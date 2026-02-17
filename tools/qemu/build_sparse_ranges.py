#!/usr/bin/env python3
import argparse
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

SECTION_RE = re.compile(
    r"^\s*\[\s*\d+\]\s+(\S+)\s+(\S+)\s+([0-9A-Fa-f]+)\s+[0-9A-Fa-f]+\s+([0-9A-Fa-f]+)\s+\S+\s+([A-Z]+)"
)


@dataclass
class Range:
    base: int
    size: int



def run_readelf(args: list[str]) -> str:
    tool = (
        os.environ.get("LINX_READELF")
        or shutil.which("readelf")
        or shutil.which("llvm-readelf")
        or shutil.which("greadelf")
    )
    if not tool:
        fallback = Path("/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf")
        if fallback.is_file():
            tool = str(fallback)
    if not tool:
        raise RuntimeError("no readelf tool found")
    proc = subprocess.run([tool, *args], check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise RuntimeError(f"readelf {' '.join(args)} failed")
    return proc.stdout


def run_objdump(args: list[str]) -> str:
    tool = shutil.which("objdump") or shutil.which("gobjdump")
    if not tool:
        raise RuntimeError("no objdump tool found")
    proc = subprocess.run([tool, *args], check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise RuntimeError(f"objdump {' '.join(args)} failed")
    return proc.stdout



def parse_elf_type(elf_path: Path) -> str:
    try:
        out = run_readelf(["-h", str(elf_path)])
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("Type:"):
                parts = line.split()
                if len(parts) >= 2:
                    return parts[1]
    except RuntimeError:
        pass
    try:
        out = run_objdump(["-f", str(elf_path)])
        lowered = out.lower()
        if "file format elf" not in lowered:
            return "NON_ELF"
        if "relocatable" in lowered:
            return "REL"
    except RuntimeError:
        pass
    return "UNKNOWN"



def page_align_down(value: int, page_size: int) -> int:
    return value & ~(page_size - 1)



def page_align_up(value: int, page_size: int) -> int:
    return (value + page_size - 1) & ~(page_size - 1)



def collect_alloc_ranges(elf_path: Path, page_size: int) -> list[Range]:
    ranges: list[Range] = []
    try:
        out = run_readelf(["-W", "-S", str(elf_path)])
        for line in out.splitlines():
            m = SECTION_RE.match(line)
            if not m:
                continue
            _name, _stype, addr_s, size_s, flags = m.groups()
            if "A" not in flags:
                continue
            addr = int(addr_s, 16)
            size = int(size_s, 16)
            if size == 0:
                continue
            base = page_align_down(addr, page_size)
            end = page_align_up(addr + size, page_size)
            if end > base:
                ranges.append(Range(base, end - base))
        return ranges
    except RuntimeError:
        pass

    # Fallback when readelf is unavailable: parse objdump section table and
    # include all non-zero VMA sections (safe over-approximation).
    out = run_objdump(["-h", str(elf_path)])
    for line in out.splitlines():
        toks = line.split()
        if len(toks) < 4 or not toks[0].isdigit():
            continue
        try:
            size = int(toks[2], 16)
            addr = int(toks[3], 16)
        except ValueError:
            continue
        if size == 0 or addr == 0:
            continue
        base = page_align_down(addr, page_size)
        end = page_align_up(addr + size, page_size)
        if end > base:
            ranges.append(Range(base, end - base))
    return ranges



def add_range(ranges: list[Range], base: int, size: int, page_size: int) -> None:
    if size <= 0:
        return
    aligned_base = page_align_down(base, page_size)
    aligned_end = page_align_up(base + size, page_size)
    if aligned_end > aligned_base:
        ranges.append(Range(aligned_base, aligned_end - aligned_base))



def merge_ranges(ranges: list[Range]) -> list[Range]:
    if not ranges:
        return []
    ranges = sorted(ranges, key=lambda r: r.base)
    merged = [ranges[0]]
    for cur in ranges[1:]:
        prev = merged[-1]
        prev_end = prev.base + prev.size
        cur_end = cur.base + cur.size
        if cur.base <= prev_end:
            prev.size = max(prev_end, cur_end) - prev.base
        else:
            merged.append(Range(cur.base, cur.size))
    return merged



def format_ranges(ranges: list[Range]) -> str:
    return ",".join(f"0x{r.base:x}:0x{r.size:x}" for r in ranges)


def is_elf_file(path: Path) -> bool:
    try:
        with path.open("rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False



def main() -> int:
    ap = argparse.ArgumentParser(description="Build QEMU LINX_COSIM_MEM_RANGES from an ELF.")
    ap.add_argument("--elf", required=True, help="Input ELF image")
    ap.add_argument("--boot-sp", type=lambda x: int(x, 0), default=0x20000, help="Boot SP for stack window (default: 0x20000)")
    ap.add_argument("--stack-window", type=lambda x: int(x, 0), default=0x8000, help="Stack window bytes around boot SP (default: 0x8000)")
    ap.add_argument("--page-size", type=lambda x: int(x, 0), default=4096, help="Page size for alignment (default: 4096)")
    ap.add_argument("--et-rel-low-bss-base", type=lambda x: int(x, 0), default=0x10000, help="Synthetic low BSS base for ET_REL (default: 0x10000)")
    ap.add_argument("--et-rel-low-bss-size", type=lambda x: int(x, 0), default=0x20000, help="Synthetic low BSS size for ET_REL (default: 0x20000)")
    ap.add_argument("--force-et-rel", action="store_true", help="Force ET_REL low-BSS window even when ELF type cannot be determined")
    ap.add_argument("--out", help="Output file; default stdout")
    args = ap.parse_args()

    elf_path = Path(args.elf)
    if not elf_path.exists():
        print(f"error: ELF not found: {elf_path}", file=sys.stderr)
        return 1
    if not is_elf_file(elf_path):
        print(f"error: not an ELF file: {elf_path}", file=sys.stderr)
        return 1
    if args.page_size <= 0 or (args.page_size & (args.page_size - 1)) != 0:
        print("error: --page-size must be a positive power of two", file=sys.stderr)
        return 1

    ranges = collect_alloc_ranges(elf_path, args.page_size)

    elf_type = parse_elf_type(elf_path)
    if elf_type == "REL" or args.force_et_rel:
        add_range(ranges, args.et_rel_low_bss_base, args.et_rel_low_bss_size, args.page_size)

    half_stack = args.stack_window // 2
    stack_base = args.boot_sp - half_stack if args.boot_sp >= half_stack else 0
    add_range(ranges, stack_base, args.stack_window, args.page_size)

    merged = merge_ranges(ranges)
    out = format_ranges(merged)

    if args.out:
        Path(args.out).write_text(out + "\n", encoding="utf-8")
    else:
        print(out)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
