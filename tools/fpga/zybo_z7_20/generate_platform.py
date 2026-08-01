#!/usr/bin/env python3
"""Validate and render the Zybo Z7-20 platform contract."""

from __future__ import annotations

import argparse
import json
import sys
from itertools import combinations
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_MANIFEST = Path(__file__).with_name("platform.json")
GENERATED_MARKER = "GENERATED FILE - DO NOT EDIT."

EXPECTED_TOP_LEVEL_KEYS = {
    "schema_version",
    "board",
    "clock_profiles_hz",
    "axi",
    "linx_memory",
    "mmio",
    "routing",
    "boot_profiles",
    "artifact_regions",
    "resource_budget",
}
EXPECTED_PART = "xc7z020clg400-1"
EXPECTED_ROUTING = ["mmio", "ddr", "fault"]


def load_manifest(path: Path) -> dict:
    """Load one platform manifest without silently normalizing its contract."""
    with path.open(encoding="utf-8") as manifest_file:
        data = json.load(manifest_file)
    if not isinstance(data, dict):
        raise ValueError("platform manifest must be a JSON object")
    return data


def _nested(data: dict, *keys: str) -> Any:
    current: Any = data
    for key in keys:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def _integer(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        return int(value, 0) if isinstance(value, str) else int(value)
    except (TypeError, ValueError):
        return None


def _expect(data: dict, errors: list[str], path: tuple[str, ...], value: Any) -> None:
    if _nested(data, *path) != value:
        errors.append(f"{'.'.join(path)} must be {value!r}")


def _half_open_region(data: dict, name: str, errors: list[str]) -> tuple[int, int] | None:
    base = _integer(_nested(data, "artifact_regions", name, "base"))
    size = _integer(_nested(data, "artifact_regions", name, "size"))
    if base is None or size is None or size <= 0:
        errors.append(f"artifact_regions.{name} must define a positive base and size")
        return None
    return base, base + size


def validate_manifest(data: dict) -> list[str]:
    """Return all contract violations, with address ranges interpreted as [base, end)."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["platform manifest must be a JSON object"]

    missing_keys = EXPECTED_TOP_LEVEL_KEYS - data.keys()
    if missing_keys:
        errors.append(f"missing top-level keys: {', '.join(sorted(missing_keys))}")

    _expect(data, errors, ("schema_version",), 1)
    _expect(data, errors, ("board", "name"), "zybo_z7_20")
    if _nested(data, "board", "part") != EXPECTED_PART:
        errors.append(f"unsupported board part: {_nested(data, 'board', 'part')!r}")
    for profile, frequency in {
        "safe_50": 50000000,
        "balanced_75": 75000000,
        "stretch_100": 100000000,
    }.items():
        _expect(data, errors, ("clock_profiles_hz", profile), frequency)

    _expect(data, errors, ("axi", "control_base"), "0x43c00000")
    _expect(data, errors, ("axi", "control_size"), "0x00010000")
    _expect(data, errors, ("axi", "data_width"), 64)
    line_bytes = _integer(_nested(data, "axi", "line_bytes"))
    if line_bytes is None or line_bytes <= 0 or line_bytes & (line_bytes - 1):
        errors.append("axi.line_bytes must be a positive power of two")
    elif line_bytes != 64:
        errors.append("axi.line_bytes must be 64")
    if _nested(data, "axi", "max_outstanding") != 1:
        errors.append("axi.max_outstanding must be 1 for the first profile")

    _expect(data, errors, ("linx_memory", "base"), "0x00000000")
    _expect(data, errors, ("linx_memory", "size"), "0x10000000")
    memory_base = _integer(_nested(data, "linx_memory", "base"))
    memory_size = _integer(_nested(data, "linx_memory", "size"))
    memory_end = None
    if memory_base is None or memory_size is None or memory_size <= 0:
        errors.append("linx_memory must define a positive base and size")
    else:
        memory_end = memory_base + memory_size

    for name, address in {
        "uart_data": "0x10000000",
        "uart_status_linux_exit": "0x10000004",
        "test_finisher": "0x10009000",
        "virtio_base": "0x30001000",
    }.items():
        _expect(data, errors, ("mmio", name), address)
        mmio_address = _integer(_nested(data, "mmio", name))
        if memory_end is not None and mmio_address is not None and memory_base <= mmio_address < memory_end:
            errors.append(f"mmio.{name} is inside Linx RAM")

    if _nested(data, "routing", "priority") != EXPECTED_ROUTING:
        errors.append("routing.priority must be MMIO-first: ['mmio', 'ddr', 'fault']")

    boot_profiles = _nested(data, "boot_profiles")
    if not isinstance(boot_profiles, dict):
        errors.append("boot_profiles must be an object")
    else:
        for profile, expected in {
            "smoke": {
                "pc": "0x00010000",
                "sp": "0x0003ff00",
                "a0": "0x00000000",
                "a1": "0x00000000",
            },
            "linux_nommu": {
                "pc": "0x00010000",
                "sp": "0x0ffef000",
                "a0": "0x00000000",
                "a1": "0x0f000000",
                "initramfs": "0x08000000",
            },
        }.items():
            for key, value in expected.items():
                _expect(data, errors, ("boot_profiles", profile, key), value)

    artifact_names = ("kernel", "initramfs", "dtb")
    regions = {name: _half_open_region(data, name, errors) for name in artifact_names}
    complete_regions = {name: region for name, region in regions.items() if region is not None}
    for first_name, second_name in combinations(complete_regions, 2):
        first_base, first_end = complete_regions[first_name]
        second_base, second_end = complete_regions[second_name]
        if first_base < second_end and second_base < first_end:
            errors.append(f"artifact_regions.{first_name} overlaps artifact_regions.{second_name}")
    if memory_end is not None:
        for name, (base, end) in complete_regions.items():
            if base < memory_base or end > memory_end:
                errors.append(f"artifact_regions.{name} is outside Linx RAM")
    if complete_regions.get("kernel") and _integer(_nested(data, "boot_profiles", "linux_nommu", "pc")) != complete_regions["kernel"][0]:
        errors.append("linux_nommu.pc must equal artifact_regions.kernel.base")
    if complete_regions.get("initramfs") and _integer(_nested(data, "boot_profiles", "linux_nommu", "initramfs")) != complete_regions["initramfs"][0]:
        errors.append("linux_nommu.initramfs must equal artifact_regions.initramfs.base")
    if complete_regions.get("dtb") and _integer(_nested(data, "boot_profiles", "linux_nommu", "a1")) != complete_regions["dtb"][0]:
        errors.append("linux_nommu.a1 must equal artifact_regions.dtb.base")

    for name, budget in {"lut": 40000, "ff": 80000, "bram36": 100, "dsp48": 64}.items():
        _expect(data, errors, ("resource_budget", name), budget)
    return errors


def _hex(value: str) -> str:
    return f"0x{int(value, 0):08x}"


def render_generated_files(data: dict) -> dict[Path, str]:
    """Render every checked-in consumer artifact in a stable path/content order."""
    axi = data["axi"]
    memory = data["linx_memory"]
    mmio = data["mmio"]
    smoke = data["boot_profiles"]["smoke"]
    linux = data["boot_profiles"]["linux_nommu"]
    artifacts = data["artifact_regions"]
    resource_budget = data["resource_budget"]
    clocks = data["clock_profiles_hz"]
    return {
        Path("chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720Generated.scala"): f'''// {GENERATED_MARKER}
package linxcore.fpga.zybo

object ZyboZ720Generated {{
  final val BoardName = "{data["board"]["name"]}"
  final val Part = "{data["board"]["part"]}"
  final val SafeClockHz = {clocks["safe_50"]}L
  final val BalancedClockHz = {clocks["balanced_75"]}L
  final val StretchClockHz = {clocks["stretch_100"]}L
  final val AxiControlBase = BigInt("{axi["control_base"][2:]}", 16)
  final val AxiControlSize = BigInt("{axi["control_size"][2:]}", 16)
  final val AxiDataWidth = {axi["data_width"]}
  final val LineBytes = {axi["line_bytes"]}
  final val MaxOutstanding = {axi["max_outstanding"]}
  final val LinxMemoryBase = BigInt("{memory["base"][2:]}", 16)
  final val LinxMemorySize = BigInt("{memory["size"][2:]}", 16)
  final val UartData = BigInt("{mmio["uart_data"][2:]}", 16)
  final val UartStatusLinuxExit = BigInt("{mmio["uart_status_linux_exit"][2:]}", 16)
  final val TestFinisher = BigInt("{mmio["test_finisher"][2:]}", 16)
  final val VirtioBase = BigInt("{mmio["virtio_base"][2:]}", 16)
  final val SmokePc = BigInt("{smoke["pc"][2:]}", 16)
  final val SmokeSp = BigInt("{smoke["sp"][2:]}", 16)
  final val LinuxPc = BigInt("{linux["pc"][2:]}", 16)
  final val LinuxSp = BigInt("{linux["sp"][2:]}", 16)
  final val LinuxA0 = BigInt("{linux["a0"][2:]}", 16)
  final val LinuxA1 = BigInt("{linux["a1"][2:]}", 16)
  final val LinuxInitramfs = BigInt("{linux["initramfs"][2:]}", 16)
  final val KernelArtifactBase = BigInt("{artifacts["kernel"]["base"][2:]}", 16)
  final val KernelArtifactSize = BigInt("{artifacts["kernel"]["size"][2:]}", 16)
  final val InitramfsArtifactBase = BigInt("{artifacts["initramfs"]["base"][2:]}", 16)
  final val InitramfsArtifactSize = BigInt("{artifacts["initramfs"]["size"][2:]}", 16)
  final val DtbArtifactBase = BigInt("{artifacts["dtb"]["base"][2:]}", 16)
  final val DtbArtifactSize = BigInt("{artifacts["dtb"]["size"][2:]}", 16)
  final val ResourceBudgetLut = {resource_budget["lut"]}
  final val ResourceBudgetFf = {resource_budget["ff"]}
  final val ResourceBudgetBram36 = {resource_budget["bram36"]}
  final val ResourceBudgetDsp48 = {resource_budget["dsp48"]}
}}
''',
        Path("tools/fpga/zybo_z7_20/generated/platform_constants.tcl"): f'''# {GENERATED_MARKER}
set LINX_ZYBO_BOARD "{data["board"]["name"]}"
set LINX_ZYBO_PART "{data["board"]["part"]}"
set LINX_ZYBO_FCLK_HZ {clocks["safe_50"]}
set LINX_ZYBO_AXI_CONTROL_BASE {_hex(axi["control_base"])}
set LINX_ZYBO_AXI_CONTROL_SIZE {_hex(axi["control_size"])}
set LINX_ZYBO_AXI_DATA_WIDTH {axi["data_width"]}
set LINX_ZYBO_LINE_BYTES {axi["line_bytes"]}
set LINX_ZYBO_MAX_OUTSTANDING {axi["max_outstanding"]}
set LINX_ZYBO_DDR_BASE {_hex(memory["base"])}
set LINX_ZYBO_DDR_SIZE {_hex(memory["size"])}
set LINX_ZYBO_UART_DATA {_hex(mmio["uart_data"])}
set LINX_ZYBO_UART_STATUS_LINUX_EXIT {_hex(mmio["uart_status_linux_exit"])}
set LINX_ZYBO_TEST_FINISHER {_hex(mmio["test_finisher"])}
set LINX_ZYBO_VIRTIO_BASE {_hex(mmio["virtio_base"])}
set LINX_ZYBO_LINUX_PC {_hex(linux["pc"])}
set LINX_ZYBO_LINUX_SP {_hex(linux["sp"])}
set LINX_ZYBO_LINUX_A0 {_hex(linux["a0"])}
set LINX_ZYBO_LINUX_A1 {_hex(linux["a1"])}
set LINX_ZYBO_LINUX_INITRAMFS {_hex(linux["initramfs"])}
set LINX_ZYBO_BUDGET_LUT {resource_budget["lut"]}
set LINX_ZYBO_BUDGET_FF {resource_budget["ff"]}
set LINX_ZYBO_BUDGET_BRAM36 {resource_budget["bram36"]}
set LINX_ZYBO_BUDGET_DSP48 {resource_budget["dsp48"]}
''',
        Path("tools/fpga/zybo_z7_20/generated/platform.h"): f'''/* {GENERATED_MARKER} */
#ifndef LINX_ZYBO_PLATFORM_H
#define LINX_ZYBO_PLATFORM_H

#define LINX_ZYBO_FCLK_HZ {clocks["safe_50"]}u
#define LINX_ZYBO_AXI_CONTROL_BASE {_hex(axi["control_base"])}u
#define LINX_ZYBO_AXI_CONTROL_SIZE {_hex(axi["control_size"])}u
#define LINX_ZYBO_AXI_DATA_WIDTH {axi["data_width"]}u
#define LINX_ZYBO_LINE_BYTES {axi["line_bytes"]}u
#define LINX_ZYBO_MAX_OUTSTANDING {axi["max_outstanding"]}u
#define LINX_ZYBO_DDR_BASE {_hex(memory["base"])}u
#define LINX_ZYBO_DDR_SIZE {_hex(memory["size"])}u
#define LINX_ZYBO_UART_DATA {_hex(mmio["uart_data"])}u
#define LINX_ZYBO_UART_STATUS_LINUX_EXIT {_hex(mmio["uart_status_linux_exit"])}u
#define LINX_ZYBO_TEST_FINISHER {_hex(mmio["test_finisher"])}u
#define LINX_ZYBO_VIRTIO_BASE {_hex(mmio["virtio_base"])}u
#define LINX_ZYBO_LINUX_PC {_hex(linux["pc"])}u
#define LINX_ZYBO_LINUX_SP {_hex(linux["sp"])}u
#define LINX_ZYBO_LINUX_A0 {_hex(linux["a0"])}u
#define LINX_ZYBO_LINUX_A1 {_hex(linux["a1"])}u
#define LINX_ZYBO_LINUX_INITRAMFS {_hex(linux["initramfs"])}u
#define LINX_ZYBO_KERNEL_ARTIFACT_BASE {_hex(artifacts["kernel"]["base"])}u
#define LINX_ZYBO_KERNEL_ARTIFACT_SIZE {_hex(artifacts["kernel"]["size"])}u
#define LINX_ZYBO_INITRAMFS_ARTIFACT_BASE {_hex(artifacts["initramfs"]["base"])}u
#define LINX_ZYBO_INITRAMFS_ARTIFACT_SIZE {_hex(artifacts["initramfs"]["size"])}u
#define LINX_ZYBO_DTB_ARTIFACT_BASE {_hex(artifacts["dtb"]["base"])}u
#define LINX_ZYBO_DTB_ARTIFACT_SIZE {_hex(artifacts["dtb"]["size"])}u
#define LINX_ZYBO_BUDGET_LUT {resource_budget["lut"]}u
#define LINX_ZYBO_BUDGET_FF {resource_budget["ff"]}u
#define LINX_ZYBO_BUDGET_BRAM36 {resource_budget["bram36"]}u
#define LINX_ZYBO_BUDGET_DSP48 {resource_budget["dsp48"]}u

#endif
''',
        Path("tools/fpga/zybo_z7_20/generated/linx-zybo-memory.dtsi"): f'''// {GENERATED_MARKER}
/ {{
  memory@0 {{
    device_type = "memory";
    reg = <0x0 {_hex(memory["base"])} 0x0 {_hex(memory["size"])}>;
  }};

  chosen {{
    linux,initrd-start = <0x0 {_hex(linux["initramfs"])}>;
    bootargs = "console=linx-uart";
  }};
}};
''',
    }


def generated_file_differences(data: dict, repository_root: Path = REPOSITORY_ROOT) -> list[Path]:
    """Return checked-in outputs that differ from the canonical render."""
    return [
        relative_path
        for relative_path, expected in render_generated_files(data).items()
        if not (repository_root / relative_path).is_file()
        or (repository_root / relative_path).read_text(encoding="utf-8") != expected
    ]


def write_generated_files(data: dict, repository_root: Path = REPOSITORY_ROOT) -> None:
    """Update checked-in generated files only when their deterministic contents changed."""
    for relative_path, contents in render_generated_files(data).items():
        output_path = repository_root / relative_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        if not output_path.is_file() or output_path.read_text(encoding="utf-8") != contents:
            output_path.write_text(contents, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--check", action="store_true", help="fail when checked-in outputs are stale")
    arguments = parser.parse_args(argv)
    try:
        data = load_manifest(arguments.manifest)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"platform manifest error: {error}", file=sys.stderr)
        return 2
    errors = validate_manifest(data)
    if errors:
        print("platform manifest validation failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 2
    if arguments.check:
        differences = generated_file_differences(data)
        if differences:
            print("generated platform files are stale:", file=sys.stderr)
            print("\n".join(f"- {path}" for path in differences), file=sys.stderr)
            return 1
        return 0
    write_generated_files(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
