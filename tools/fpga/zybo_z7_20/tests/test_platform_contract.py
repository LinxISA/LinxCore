"""Contract tests for the generated Zybo Z7-20 platform description."""

from __future__ import annotations

import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.fpga.zybo_z7_20.generate_platform import load_manifest, validate_manifest


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
MANIFEST = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/platform.json"


class PlatformContractTest(unittest.TestCase):
    def test_linux_regions_do_not_overlap(self):
        """Rejecting the valid artifact layout would make Linux boot impossible."""
        data = load_manifest(MANIFEST)
        self.assertEqual(validate_manifest(data), [])

    def test_mmio_decode_precedes_ddr(self):
        """Changing decode order would make MMIO addresses reach DDR."""
        data = load_manifest(MANIFEST)
        self.assertEqual(data["routing"]["priority"], ["mmio", "ddr", "fault"])

    def test_linux_boot_contract(self):
        """Changing a Linux entry register would break the monitor-to-kernel ABI."""
        linux = load_manifest(MANIFEST)["boot_profiles"]["linux_nommu"]
        self.assertEqual(linux["pc"], "0x00010000")
        self.assertEqual(linux["sp"], "0x0ffef000")
        self.assertEqual(linux["a0"], "0x00000000")
        self.assertEqual(linux["a1"], "0x0f000000")

    def test_rejects_non_power_of_two_line_size(self):
        """A non-power-of-two cache line cannot be represented by the AXI geometry."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["axi"]["line_bytes"] = 96
        self.assert_validation_error(invalid, "line_bytes")

    def test_rejects_unsupported_part(self):
        """Targeting another FPGA part would invalidate the resource and board contract."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["board"]["part"] = "xc7z010clg400-1"
        self.assert_validation_error(invalid, "unsupported board part")

    def test_rejects_overlapping_boot_artifacts(self):
        """An overlap would corrupt one of the kernel, DTB, or initramfs payloads."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["artifact_regions"]["initramfs"]["base"] = "0x01000000"
        self.assert_validation_error(invalid, "overlaps")

    def test_rejects_mmio_inside_linx_ram(self):
        """Mapping MMIO as RAM would violate the MMIO-first routing boundary."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["mmio"]["uart_data"] = "0x00001000"
        self.assert_validation_error(invalid, "inside Linx RAM")

    def test_rejects_multiple_outstanding_transactions(self):
        """More than one request exceeds the first-profile AXI contract."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["axi"]["max_outstanding"] = 2
        self.assert_validation_error(invalid, "max_outstanding")

    def test_rejects_non_mmio_first_priority(self):
        """Forwarding DDR first would hide every overlapping MMIO address."""
        data = load_manifest(MANIFEST)
        invalid = copy.deepcopy(data)
        invalid["routing"]["priority"] = ["ddr", "mmio", "fault"]
        self.assert_validation_error(invalid, "MMIO-first")

    def test_generated_artifacts_are_current_and_marked_generated(self):
        """A stale or hand-edited output would desynchronize downstream consumers."""
        generator = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/generate_platform.py"
        result = subprocess.run(
            ["python3", str(generator), "--check"],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        for relative_path in (
            "chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720Generated.scala",
            "tools/fpga/zybo_z7_20/generated/platform_constants.tcl",
            "tools/fpga/zybo_z7_20/generated/platform.h",
            "tools/fpga/zybo_z7_20/generated/linx-zybo-memory.dtsi",
        ):
            with self.subTest(relative_path=relative_path):
                contents = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8")
                self.assertIn("GENERATED FILE - DO NOT EDIT.", contents)

    def test_source_preflight_fails_for_an_invalid_manifest(self):
        """A broken source manifest must make the preflight fail nonzero."""
        data = load_manifest(MANIFEST)
        data["axi"]["line_bytes"] = 3
        checker = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/check_framework.py"
        with tempfile.TemporaryDirectory() as temporary_directory:
            invalid_manifest = Path(temporary_directory) / "platform.json"
            invalid_manifest.write_text(json.dumps(data), encoding="utf-8")
            result = subprocess.run(
                ["python3", str(checker), "--mode", "source", "--manifest", str(invalid_manifest)],
                cwd=REPOSITORY_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("line_bytes", result.stderr)

    def test_rejects_noncanonical_numeric_manifest_values(self):
        """Coercing floats or bools would make generation fail after preflight passes."""
        data = load_manifest(MANIFEST)
        invalid_cases = (
            ("axi line float", ("axi", "line_bytes"), 64.0),
            ("axi line hex string", ("axi", "line_bytes"), "0x00000040"),
            ("outstanding bool", ("axi", "max_outstanding"), True),
            ("artifact base float", ("artifact_regions", "kernel", "base"), 65536.0),
            ("artifact base integer", ("artifact_regions", "kernel", "base"), 65536),
            ("artifact size float", ("artifact_regions", "kernel", "size"), 16777216.5),
            ("resource float", ("resource_budget", "lut"), 40000.0),
            ("artifact size decimal", ("artifact_regions", "kernel", "size"), "16777216"),
        )
        for name, path, value in invalid_cases:
            with self.subTest(name=name):
                invalid = copy.deepcopy(data)
                target = invalid
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value
                self.assertTrue(validate_manifest(invalid), name)

    def test_rejects_hex_encoded_line_bytes_before_rendering(self):
        """The AXI geometry is an integer field, not an address-style hex string."""
        data = load_manifest(MANIFEST)
        data["axi"]["line_bytes"] = "0x00000040"
        generator = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/generate_platform.py"
        checker = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/check_framework.py"
        with tempfile.TemporaryDirectory() as temporary_directory:
            invalid_manifest = Path(temporary_directory) / "platform.json"
            invalid_manifest.write_text(json.dumps(data), encoding="utf-8")
            commands = (
                ["python3", str(generator), "--manifest", str(invalid_manifest), "--check"],
                ["python3", str(checker), "--mode", "source", "--manifest", str(invalid_manifest)],
                ["python3", str(checker), "--mode", "generated", "--manifest", str(invalid_manifest)],
                ["python3", str(checker), "--mode", "all", "--manifest", str(invalid_manifest)],
            )
            for command in commands:
                with self.subTest(command=command):
                    result = subprocess.run(
                        command,
                        cwd=REPOSITORY_ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("axi.line_bytes", result.stderr)
                    self.assertNotIn("stale", result.stderr)
                    self.assertNotIn("Traceback", result.stderr)

    def test_rejects_unknown_keys_in_every_schema_object(self):
        """Schema version 1 must fail fast instead of silently ignoring typos."""
        data = load_manifest(MANIFEST)
        invalid_cases = (
            ("top level", (), "typo"),
            ("board", ("board",), "typo"),
            ("clock profiles", ("clock_profiles_hz",), "typo"),
            ("axi", ("axi",), "typo"),
            ("memory", ("linx_memory",), "typo"),
            ("mmio", ("mmio",), "uart_dat"),
            ("routing", ("routing",), "typo"),
            ("boot profiles", ("boot_profiles",), "linx_nommu_typo"),
            ("smoke profile", ("boot_profiles", "smoke"), "typo"),
            ("linux profile", ("boot_profiles", "linux_nommu"), "typo"),
            ("artifact regions", ("artifact_regions",), "kernel_typo"),
            ("kernel artifact", ("artifact_regions", "kernel"), "end"),
            ("resource budget", ("resource_budget",), "typo"),
        )
        for name, path, key in invalid_cases:
            with self.subTest(name=name):
                invalid = copy.deepcopy(data)
                target = invalid
                for component in path:
                    target = target[component]
                target[key] = 1
                errors = validate_manifest(invalid)
                expected_path = ".".join((*path, key)) if path else key
                self.assertTrue(any(expected_path in error for error in errors), errors)

    def test_invalid_manifest_fails_all_preflight_modes_and_generator_cleanly(self):
        """Malformed source must fail before rendering in every public command mode."""
        data = load_manifest(MANIFEST)
        data["artifact_regions"]["kernel"]["base"] = 65536.0
        generator = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/generate_platform.py"
        checker = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/check_framework.py"
        with tempfile.TemporaryDirectory() as temporary_directory:
            invalid_manifest = Path(temporary_directory) / "platform.json"
            invalid_manifest.write_text(json.dumps(data), encoding="utf-8")
            generator_result = subprocess.run(
                ["python3", str(generator), "--manifest", str(invalid_manifest), "--check"],
                cwd=REPOSITORY_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(generator_result.returncode, 0)
            self.assertIn("platform manifest validation failed", generator_result.stderr)
            self.assertNotIn("Traceback", generator_result.stderr)
            for mode in ("source", "generated", "all"):
                with self.subTest(mode=mode):
                    result = subprocess.run(
                        [
                            "python3",
                            str(checker),
                            "--mode",
                            mode,
                            "--manifest",
                            str(invalid_manifest),
                        ],
                        cwd=REPOSITORY_ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("artifact_regions.kernel.base", result.stderr)
                    self.assertNotIn("Traceback", result.stderr)

    def test_preflight_supports_direct_and_bytecode_free_module_invocation(self):
        """The supported package form uses -B so it remains artifact-free."""
        checker = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/check_framework.py"
        commands = (
            ["python3", str(checker), "--mode", "source"],
            ["python3", "-B", "-m", "tools.fpga.zybo_z7_20.check_framework", "--mode", "source"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=REPOSITORY_ROOT,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_preflight_does_not_create_repository_files_in_tools_or_all_mode(self):
        """Read-only preflights must not leave Python bytecode or other artifacts behind."""
        checker = REPOSITORY_ROOT / "tools/fpga/zybo_z7_20/check_framework.py"
        platform_directory = checker.parent
        before = sorted(path.relative_to(platform_directory) for path in platform_directory.rglob("*"))
        command_prefixes = (
            ("direct", ["python3", str(checker)]),
            ("module", ["python3", "-B", "-m", "tools.fpga.zybo_z7_20.check_framework"]),
        )
        for invocation, prefix in command_prefixes:
            for mode in ("tools", "all"):
                with self.subTest(invocation=invocation, mode=mode):
                    result = subprocess.run(
                        [*prefix, "--mode", mode],
                        cwd=REPOSITORY_ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
        after = sorted(path.relative_to(platform_directory) for path in platform_directory.rglob("*"))
        self.assertEqual(after, before)

    def assert_validation_error(self, data: dict, expected: str) -> None:
        errors = validate_manifest(data)
        self.assertTrue(errors, "expected invalid platform data to be rejected")
        self.assertTrue(
            any(expected in error for error in errors),
            f"expected {expected!r} in validation errors: {errors}",
        )


if __name__ == "__main__":
    unittest.main()
