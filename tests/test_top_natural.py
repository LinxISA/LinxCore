from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/chisel/run_top_natural.sh"
TB = ROOT / "tools/chisel/top_natural_tb.cpp"
VPI_CONFIG = ROOT / "tools/chisel/top_natural.vlt"


class TopNaturalHarnessTest(unittest.TestCase):
    def test_runner_reuses_one_emit_for_vpi_and_lint(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")
        self.assertIn('--lint) LINT=1', source)
        self.assertEqual(source.count('runMain ${EMIT_MAIN}'), 1)
        self.assertIn(
            'verilator --lint-only --top-module CoreTOPHarness', source
        )

    def test_runner_exposes_only_core_top_ports_to_vpi(self) -> None:
        runner = RUNNER.read_text(encoding="utf-8")
        self.assertTrue(VPI_CONFIG.is_file())
        config = VPI_CONFIG.read_text(encoding="utf-8")

        self.assertNotIn("--public-flat-rw", runner)
        self.assertIn("top_verilator_harness.sv", runner)
        self.assertIn("--top-module CoreTOPHarness", runner)
        self.assertIn('TOP_VPI_CONFIG="${ROOT_DIR}/tools/chisel/top_natural.vlt"', runner)
        self.assertIn('public_flat_rw -module "CoreTOP" -port "io_*"', config)
        self.assertIn('public_flat_rw -module "ROB" -port "io_headStatus*"', config)
        self.assertIn(
            'public_flat_rw -module "OooIexExecutionPipeline" '
            '-port "io_residentEntries*"',
            config,
        )
        self.assertIn(
            'public_flat_rw -module "OooIexExecutionPipeline" '
            '-port "io_inFlightEntries*"',
            config,
        )
        self.assertIn(
            'public_flat_rw -module "OooIexExecutionPipeline" '
            '-port "io_issueEmpty"',
            config,
        )
        self.assertIn(
            'public_flat_rw -module "OooIexExecutionPipeline" '
            '-port "io_executionEmpty"',
            config,
        )
        self.assertIn(
            'public_flat_rw -module "OooIexIssue" '
            '-var "scheduleRows_1_*_sources_?_ready"',
            config,
        )
        harness = TB.read_text(encoding="utf-8")
        self.assertIn('std::string("TOP.CoreTOPHarness.dut.")', harness)
        self.assertLess(
            harness.index('std::string("TOP.CoreTOPHarness.dut.")'),
            harness.index('std::string("TOP.")'),
        )

    def test_runner_discards_nonreusable_pch_after_link(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")
        build = source.index('--build-jobs "${BUILD_JOBS}"')
        discard = source.find(
            'find "${OBJ_DIR}" -type f -name \'*.gch\' -delete', build
        )
        self.assertNotEqual(discard, -1)
        execute = source.index('"${OBJ_DIR}/top_natural_tb"', discard)

        self.assertLess(build, discard)
        self.assertLess(discard, execute)

    def test_self_test_records_harness_ownership_without_an_oracle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            result = subprocess.run(
                [str(RUNNER), "--self-test", "--manifest", str(manifest)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            payload = json.loads(manifest.read_text(encoding="utf-8"))

        self.assertEqual(payload["schema"], 1)
        self.assertEqual(payload["top"], "TOP")
        self.assertEqual(payload["task"], 18)
        self.assertEqual(
            payload["harness_owns"],
            ["elf", "memory", "uart", "finisher", "manifest"],
        )
        self.assertFalse(payload["instruction_oracle"])
        self.assertFalse(payload["commit_oracle"])
        self.assertEqual(payload["memory_size_encoding"]["Bytes64"], 6)
        self.assertEqual(payload["build_jobs"], 1)

    def test_default_iteration_profile_bounds_generated_work(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")

        self.assertIn('WIDTH="2"', source)
        self.assertIn('BUILD_JOBS="1"', source)
        self.assertIn('MAX_CYCLES="20000"', source)
        self.assertIn('HEARTBEAT_CYCLES="2000"', source)
        self.assertIn('DEADLOCK_CYCLES="3000"', source)

    def test_self_test_records_explicit_parallel_build_jobs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            result = subprocess.run(
                [str(RUNNER), "--self-test", "--manifest", str(manifest),
                 "--build-jobs", "3"],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            payload = json.loads(manifest.read_text(encoding="utf-8"))

        self.assertEqual(payload["build_jobs"], 3)
        source = RUNNER.read_text(encoding="utf-8")
        self.assertIn('--build-jobs "${BUILD_JOBS}"', source)
        self.assertIn('finalize_harness_status "${TB_STATUS}" "${MANIFEST}"', source)

    def test_finalizer_preserves_tb_failure_and_rejects_bad_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            script = r'''
set -euo pipefail
runner="$1"
manifest="$2"
set --
source "${runner}"
printf '%s\n' stale > "${manifest}"
prepare_harness_manifest "${manifest}"
test ! -e "${manifest}"
printf '%s\n' '{malformed' > "${manifest}"
set +e
finalize_harness_status 37 "${manifest}"
tb_status=$?
finalize_harness_status 0 "${manifest}"
record_status=$?
set -e
test "${tb_status}" -eq 37
test "${record_status}" -ne 0
'''
            result = subprocess.run(
                ["bash", "-c", script, "bash", str(RUNNER), str(manifest)],
                cwd=ROOT,
                env={
                    **__import__("os").environ,
                    "LINX_TOP_NATURAL_SOURCE_FUNCTIONS_ONLY": "1",
                },
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_build_jobs_must_be_a_positive_integer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            result = subprocess.run(
                [str(RUNNER), "--self-test", "--manifest", str(manifest),
                 "--build-jobs", "0"],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertEqual(result.returncode, 2)
        self.assertIn("--build-jobs must be a positive integer", result.stderr)

    def test_cpp_harness_has_no_replay_or_expected_commit_input(self) -> None:
        source = TB.read_text(encoding="utf-8")
        forbidden = (
            "expected_commit",
            "expected_instruction",
            "replay_rows",
            "commit_replay",
            "instruction_replay",
        )
        for token in forbidden:
            self.assertNotIn(token, source.lower())

        self.assertIn("io_instructionMemoryRequest_ready", source)
        self.assertIn("arguments.data_lanes", source)
        self.assertIn("arguments.trace_width", source)
        self.assertIn("arguments.stid_count * arguments.gpr_arch_regs", source)
        self.assertIn("atag == arguments.sp_atag", source)
        self.assertNotIn("constexpr int kDataLanes", source)
        self.assertNotIn("constexpr int kTraceLanes", source)
        self.assertIn("kUartAddress", source)
        self.assertIn("kFinisherAddress", source)
        self.assertIn('\\"activation\\"', source)
        self.assertIn("top-vpi-port-validation=pass", source)
        self.assertIn("top-vpi-bootstrap-validation=pass", source)
        self.assertIn("pins.put(\"io_bootstrapComplete\", 1)", source)
        self.assertIn("observed_bootstrap_ready", source)
        self.assertIn('"port_validation_pass"', source)
        self.assertIn("5 + entries + 1", source)
        self.assertLess(source.index('"port_validation_pass"'), source.index("return 0;"))

    def test_instruction_translation_response_is_identity_mapped(self) -> None:
        source = TB.read_text(encoding="utf-8")

        self.assertIn("kInstructionTranslationAccessKind", source)
        self.assertIn('stem + "_bits_accessKind"', source)
        self.assertIn("response.access_kind", source)
        self.assertIn("response.address >> kPageOffsetBits", source)

    def test_system_issue_lane_count_is_metadata_driven_and_required(self) -> None:
        runner = RUNNER.read_text(encoding="utf-8")
        source = TB.read_text(encoding="utf-8")
        emitter = (
            ROOT / "chisel/src/main/scala/linxcore/top/EmitTOP.scala"
        ).read_text(encoding="utf-8")

        self.assertIn('s"systemIssueLanes=${p.iex.systemMulticycleQueues}"', emitter)
        self.assertIn('systemIssueLanes) SYSTEM_ISSUE_LANES="${value}"', runner)
        self.assertIn('--system-issue-lanes "${SYSTEM_ISSUE_LANES}"', runner)
        self.assertIn("int system_issue_lanes = 0", source)
        self.assertIn('option == "--system-issue-lanes"', source)
        self.assertIn("lane < arguments.system_issue_lanes", source)
        self.assertNotIn("lane < 8", source)
        self.assertNotIn("catch (const std::runtime_error &) { break; }", source)

    def test_optional_commit_trace_observes_only_fired_public_prefix(self) -> None:
        runner = RUNNER.read_text(encoding="utf-8")
        source = TB.read_text(encoding="utf-8")

        self.assertIn('--commit-trace) COMMIT_TRACE="${2:-}"', runner)
        self.assertIn('TB_ARGS+=(--commit-trace "${COMMIT_TRACE}")', runner)
        self.assertIn('option == "--commit-trace"', source)
        self.assertIn("const bool commit_fire", source)
        self.assertIn("std::min<std::uint64_t>", source)
        self.assertIn("arguments.retire_width", source)
        for field in (
            '"pc"', '"instructionBits"', '"resultValid"', '"result"',
            '"memoryValid"', '"memoryStore"',
        ):
            self.assertIn(field, source)
        self.assertNotIn('"storeData"', source)
        self.assertNotIn('"mem_wdata"', source)
        self.assertFalse("commit_trace" in source and "pins.put" in source[
            source.index("commit_trace"):source.index("commit_trace") + 400
        ])

    def test_natural_ram_is_noncacheable_and_only_real_write_carries_data(self) -> None:
        platform = (
            ROOT / "chisel/src/main/scala/linxcore/params/NaturalPlatformParams.scala"
        ).read_text(encoding="utf-8")
        source = TB.read_text(encoding="utf-8")
        memory = (
            ROOT / "chisel/src/main/scala/linxcore/top/interface/Memory.scala"
        ).read_text(encoding="utf-8")

        self.assertIn("normalMemoryAttributes", platform)
        self.assertIn("cacheable = false, device = false", platform)
        self.assertIn("defaultMemoryAttributes = normalMemoryAttributes", platform)
        self.assertIn("val Read, Write, AcquireRead, AcquireWrite", memory)
        self.assertIn("accepted_data[lane].command == 1", source)
        self.assertIn("if (request.command == 1)", source)
        self.assertNotIn("request.command == 3", source)
        self.assertNotIn("accepted_data[lane].command == 3", source)

    def test_port_validation_samples_bootstrap_ready_after_completion_edge(self) -> None:
        source = TB.read_text(encoding="utf-8")
        completion_drive = source.index('pins.put("io_bootstrapComplete", 1)')
        completion_edge = source.index(
            "dut.clock = 1; dut.eval(); VerilatedVpi::callValueCbs();",
            completion_drive,
        )
        completion_sample = source.find(
            'pins.get("io_bootstrapReady")', completion_edge
        )
        completion_clear = source.index(
            'pins.put("io_bootstrapComplete", 0)', completion_edge
        )

        self.assertNotEqual(completion_sample, -1)
        self.assertLess(completion_edge, completion_sample)
        self.assertLess(completion_sample, completion_clear)

    def test_watchdog_cli_and_progress_contract(self) -> None:
        runner = RUNNER.read_text(encoding="utf-8")
        source = TB.read_text(encoding="utf-8")

        self.assertIn("--heartbeat-cycles", runner)
        self.assertIn("--deadlock-cycles", runner)
        self.assertIn('--heartbeat-cycles "${HEARTBEAT_CYCLES}"', runner)
        self.assertIn('--deadlock-cycles "${DEADLOCK_CYCLES}"', runner)

        self.assertIn("heartbeat_cycles = 10000", source)
        self.assertIn("deadlock_cycles = 100000", source)
        self.assertIn('option == "--heartbeat-cycles"', source)
        self.assertIn('option == "--deadlock-cycles"', source)
        self.assertIn('"top-natural-heartbeat cycle="', source)
        self.assertIn("last_progress_cycle", source)
        self.assertIn("arguments.heartbeat_cycles != 0", source)
        self.assertIn("arguments.deadlock_cycles != 0", source)
        self.assertIn('status = "deadlock"', source)
        self.assertIn('"top-natural-deadlock cycle="', source)
        self.assertIn('" last-trace-source="', source)
        self.assertIn('" last-trace-kind="', source)
        self.assertIn('" last-trace-pc=0x"', source)
        self.assertIn('" last-trace-opcode=0x"', source)
        self.assertIn('"top-natural-trace-event index="', source)
        self.assertIn('"top-natural-rob-head stid="', source)
        self.assertIn('" completed="', source)
        self.assertIn('"top-natural-iex-state issue-empty="', source)
        self.assertIn('"top-natural-bru-resident bank="', source)
        self.assertIn('" resident="', source)
        self.assertIn('" in-flight="', source)
        self.assertIn("recent_trace", source)
        self.assertIn("instruction_response_fire", source)
        self.assertIn("data_response_fire", source)
        self.assertIn("trace_fire", source)
        self.assertIn("commit_fire", source)
        self.assertIn('return status == "finisher_pass" && activated ? 0 : 1;', source)


if __name__ == "__main__":
    unittest.main()
