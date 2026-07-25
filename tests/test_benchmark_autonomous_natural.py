import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TB = ROOT / "tools" / "chisel" / "benchmark_autonomous_top_natural_tb.cpp"
RUNNER = ROOT / "tools" / "chisel" / "run_chisel_benchmark_autonomous_top_natural.sh"


class BenchmarkAutonomousNaturalTests(unittest.TestCase):
    def compile_self_test(self, directory: Path) -> Path:
        exe = directory / "natural_self_test"
        subprocess.run(
            [
                "c++",
                "-std=c++17",
                "-DLINXCORE_BENCHMARK_AUTONOMOUS_NATURAL_SELF_TEST",
                "-I",
                str(ROOT / "tools" / "chisel"),
                str(TB),
                "-o",
                str(exe),
            ],
            check=True,
            cwd=ROOT,
        )
        return exe

    def load_manifest_without_duplicate_keys(self, manifest: Path):
        def reject_duplicates(pairs):
            seen = set()
            result = {}
            for key, value in pairs:
                if key in seen:
                    raise AssertionError(f"duplicate manifest key: {key}")
                seen.add(key)
                result[key] = value
            return result

        return json.loads(manifest.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)

    def test_cpp_helpers_self_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = self.compile_self_test(Path(tmp))
            result = subprocess.run([str(exe), "--self-test"], check=True, text=True, capture_output=True)
            self.assertIn("benchmark-autonomous-natural-self-test: ok", result.stdout)

    def test_cpp_rejects_forbidden_oracle_options(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = self.compile_self_test(Path(tmp))
            result = subprocess.run(
                [
                    str(exe),
                    "--memory-hex",
                    "m.mem",
                    "--reset-pc",
                    "0x1000",
                    "--reset-sp",
                    "0x2000",
                    "--commit-trace",
                    "c.jsonl",
                    "--uart-output",
                    "u.txt",
                    "--manifest",
                    "m.json",
                    "--expected-rows",
                    "rows.jsonl",
                ],
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("forbidden", result.stderr)

    def test_cpp_terminal_self_tests_publish_manifest_fields(self):
        cases = {
            "finisher-pass": (0, "finisher_pass", 0x5555, True, ""),
            "finisher-fail": (1, "finisher_fail", 0x3333, False, ""),
            "exit-pass": (0, "finisher_pass", 0x5555, True, ""),
            "exit-fail": (1, "finisher_fail", 0, False, ""),
            "trap": (1, "trap", 0, False, ""),
            "timeout": (1, "timeout", 0, False, ""),
            "uart": (1, "halted", 0, False, "OK\n"),
        }
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            exe = self.compile_self_test(tmp_path)
            for case, (returncode, status, code, passed, uart) in cases.items():
                manifest = tmp_path / f"{case}.json"
                uart_output = tmp_path / f"{case}.uart"
                result = subprocess.run(
                    [
                        str(exe),
                        "--self-test-case",
                        case,
                        "--manifest",
                        str(manifest),
                        "--uart-output",
                        str(uart_output),
                    ],
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(result.returncode, returncode, (case, result.stderr, result.stdout))
                payload = self.load_manifest_without_duplicate_keys(manifest)
                self.assertEqual(payload["terminal_status"], status)
                self.assertIn("ipc", payload)
                self.assertAlmostEqual(payload["ipc"], payload["commits"] / payload["cycles"])
                self.assertEqual(payload["performance"], {})
                self.assertEqual(payload["finisher_code"], code)
                self.assertEqual(payload["finisher_pass"], passed)
                self.assertEqual(payload["uart"], uart)
                if case == "exit-pass":
                    self.assertEqual(payload["service_requests"], 1)
                    self.assertEqual(payload["last_service_nr"], 93)
                    self.assertEqual(payload["sys_exit_code"], 0)
                elif case == "exit-fail":
                    self.assertEqual(payload["service_requests"], 1)
                    self.assertEqual(payload["last_service_nr"], 93)
                    self.assertEqual(payload["sys_exit_code"], 7)
                else:
                    self.assertEqual(payload["service_requests"], 0)
                    self.assertIsNone(payload["last_service_nr"])
                    self.assertIsNone(payload["sys_exit_code"])
                self.assertEqual(payload["service_responses"], 0)
                self.assertIsNone(payload["unsupported_service_nr"])
                self.assertEqual(uart_output.read_text(encoding="utf-8"), uart)
                self.assertEqual(payload["schema"], "linxcore.benchmark_autonomous_natural.v1")
                self.assertEqual(payload["artifacts"]["event_trace"], "self-test.events.jsonl")
                self.assertEqual(payload["artifacts"]["uart_output"], str(uart_output))

    def test_cpp_service_responder_self_test_publishes_manifest_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            exe = self.compile_self_test(tmp_path)
            subprocess.run([str(exe), "--self-test"], check=True, text=True, capture_output=True)
            payload = self.load_manifest_without_duplicate_keys(
                Path("/tmp/linxcore-natural-self-test-manifest.json")
            )
            self.assertEqual(payload["service_requests"], 1)
            self.assertEqual(payload["service_responses"], 1)
            self.assertEqual(payload["last_service_nr"], 96)
            self.assertAlmostEqual(payload["ipc"], 3 / 9)
            self.assertEqual(payload["performance"], {})
            self.assertIsNone(payload["unsupported_service_nr"])
            self.assertIsNone(payload["sys_exit_code"])

    def test_cpp_exit_self_tests_use_no_response_and_manifest_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            exe = self.compile_self_test(tmp_path)
            pass_manifest = tmp_path / "exit-pass.json"
            fail_manifest = tmp_path / "exit-fail.json"
            pass_uart = tmp_path / "exit-pass.uart"
            fail_uart = tmp_path / "exit-fail.uart"

            pass_result = subprocess.run(
                [
                    str(exe),
                    "--self-test-case",
                    "exit-pass",
                    "--manifest",
                    str(pass_manifest),
                    "--uart-output",
                    str(pass_uart),
                ],
                text=True,
                capture_output=True,
            )
            fail_result = subprocess.run(
                [
                    str(exe),
                    "--self-test-case",
                    "exit-fail",
                    "--manifest",
                    str(fail_manifest),
                    "--uart-output",
                    str(fail_uart),
                ],
                text=True,
                capture_output=True,
            )

            self.assertEqual(pass_result.returncode, 0, pass_result.stderr)
            self.assertEqual(fail_result.returncode, 1, fail_result.stderr)
            passing = self.load_manifest_without_duplicate_keys(pass_manifest)
            failing = self.load_manifest_without_duplicate_keys(fail_manifest)
            self.assertEqual(passing["terminal_status"], "finisher_pass")
            self.assertIs(passing["finisher_pass"], True)
            self.assertEqual(passing["sys_exit_code"], 0)
            self.assertEqual(passing["last_service_nr"], 93)
            self.assertEqual(passing["service_requests"], 1)
            self.assertEqual(passing["service_responses"], 0)
            self.assertEqual(failing["terminal_status"], "finisher_fail")
            self.assertIs(failing["finisher_pass"], False)
            self.assertEqual(failing["sys_exit_code"], 7)
            self.assertEqual(failing["last_service_nr"], 93)
            self.assertEqual(failing["service_requests"], 1)
            self.assertEqual(failing["service_responses"], 0)

    def test_cpp_finisher_pass_uses_first_cycle_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            exe = self.compile_self_test(tmp_path)
            manifest = tmp_path / "pass.json"
            uart_output = tmp_path / "pass.uart"
            subprocess.run(
                [
                    str(exe),
                    "--self-test-case",
                    "finisher-pass",
                    "--manifest",
                    str(manifest),
                    "--uart-output",
                    str(uart_output),
                ],
                check=True,
                text=True,
                capture_output=True,
            )
            payload = self.load_manifest_without_duplicate_keys(manifest)
            self.assertEqual(payload["finisher_code"], 0x5555)
            self.assertIs(payload["finisher_pass"], True)
            self.assertEqual(payload["terminal_status"], "finisher_pass")

    def test_runner_rejects_forbidden_oracle_options_before_tools(self):
        result = subprocess.run(
            [
                "bash",
                str(RUNNER),
                "--elf",
                "tests/benchmarks/build/dhrystone_real.elf",
                "--expected-rows",
                "rows.jsonl",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("forbidden", result.stderr)

    def test_runner_has_no_expected_or_qemu_harness_inputs(self):
        text = RUNNER.read_text(encoding="utf-8")
        self.assertNotIn("--qemu-trace \"", text)
        self.assertNotIn("--expected-rows \"", text)
        self.assertIn("frontend_fetch_elf_memory.py", text)
        self.assertIn("EmitLinxCoreBenchmarkAutonomousTop", text)
        self.assertIn("--event-trace \"${EVENT_TRACE}\"", text)
        self.assertIn("\"event_trace\": {\"path\": str(event), \"sha256\": sha256(event)}", text)

    def test_runner_uses_canonical_direct_boot_stack_by_default(self):
        text = RUNNER.read_text(encoding="utf-8")
        self.assertIn('DIRECT_BOOT_SP="0x0000000007fefff0"', text)
        self.assertIn('RESET_SP="${RESET_SP:-${DIRECT_BOOT_SP}}"', text)
        self.assertNotIn('RESET_SP="${RESET_SP:-${ELF_STACK}}"', text)
        self.assertIn("--reset-sp)", text)

    def test_cpp_iterates_both_commit_lanes_in_slot_order(self):
        driver = TB.read_text(encoding="utf-8")

        self.assertIn("read_commit_row(\n    const VLinxCoreBenchmarkAutonomousTop &dut,\n    int slot)", driver)
        self.assertIn("dut.io_commit_rows_1_valid", driver)
        self.assertIn("commit_rows_this_cycle", driver)
        self.assertIn("for (int slot = 0; slot < 2; ++slot)", driver)
        self.assertIn("const auto row = read_commit_row(dut, slot);", driver)
        self.assertIn("commit_rows_per_cycle_hist[clamp_hist_index(commit_rows", driver)
        self.assertNotIn("commit_rows_per_cycle_hist[commit_valid ? 1 : 0]++", driver)

    def test_head_issue_observability_is_passive_and_serialized(self):
        live_top = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreFrontendFetchRfAluTraceTop.scala"
        ).read_text(encoding="utf-8")
        top = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreBenchmarkAutonomousTop.scala"
        ).read_text(encoding="utf-8")
        driver = TB.read_text(encoding="utf-8")

        forwarded = {
            "io.debugCommitHeadStatus := live.io.commitHeadStatus.asUInt",
            "io.debugCommitHeadRobValue := live.io.commitHeadRobValue",
            "io.debugRobOccupiedMask := live.io.occupiedMask",
            "io.debugRobCompletedMask := live.io.completedMask",
            "io.debugTuRenameSourceUnderflowMask := live.io.tuRenameSourceUnderflowMask",
            "io.debugTuRenameBlockedByTAlloc := live.io.tuRenameBlockedByTAlloc",
            "io.debugTuRenameBlockedByUAlloc := live.io.tuRenameBlockedByUAlloc",
            "io.debugTuRenameTUsedEntries := live.io.tuRenameTUsedEntries",
            "io.debugTuRenameUUsedEntries := live.io.tuRenameUUsedEntries",
            "io.debugGprReservationCount := live.io.gprReservationCount",
            "io.debugGprReservationNeed := live.io.gprReservationNeed",
            "io.debugGprFreeCount := live.io.gprFreeCount",
            "io.debugGprMapQValidCount := live.io.gprMapQValidCount",
            "io.debugGprMapQFreeCount := live.io.gprMapQFreeCount",
            "io.debugGprFreeListMismatchCount := live.io.gprFreeListMismatchCount",
            "io.debugGprCommitAccepted := live.io.gprCommitAccepted",
            "io.debugGprCommitBlockBid := live.io.gprCommitBlockBid",
            "io.debugGprCommittedMapQCount := live.io.gprCommittedMapQCount",
            "io.debugGprReleasedPhysCount := live.io.gprReleasedPhysCount",
            "io.debugRobRenameUpdateAttemptValid := live.io.robRenameUpdateAttemptValid",
            "io.debugRobRenameUpdateReady := live.io.robRenameUpdateReady",
            "io.debugRobRenameUpdateFire := live.io.robRenameUpdateFire",
            "io.debugRobRenameUpdateIgnored := live.io.robRenameUpdateIgnored",
            "io.debugIssueHeadPc := live.io.issueQueueHeadPc",
            "io.debugIssueHeadStid := live.io.issueQueueHeadStid",
            "io.debugIssueHeadBidValue := live.io.issueQueueHeadBidValue",
            "io.debugIssueHeadRidValue := live.io.issueQueueHeadRidValue",
            "io.debugIssueHeadSrcPhysTag := live.io.issueQueueHeadSrcPhysTag",
            "io.debugIssueSourceReadyMask := live.io.issueQueueSourceReadyMask",
            "io.debugIssueScalarSpOrderBlocked := live.io.issueQueueScalarSpOrderBlocked",
            "io.debugIssueBankScalarSpOrderBlockedMask := live.io.issueQueueBankScalarSpOrderBlockedMask",
            "io.debugScalarSpStid0IssueHeadValid := live.io.scalarSpStid0IssueHeadValid",
            "io.debugScalarSpStid0IssueHeadBidValue := live.io.scalarSpStid0IssueHeadBidValue",
            "io.debugScalarSpStid0IssueHeadRidValue := live.io.scalarSpStid0IssueHeadRidValue",
            "io.debugRfReadyMask := live.io.rfReadyMask",
            "io.debugPWakeupValid := live.io.rfWriteValid",
            "io.debugPWakeupTag := live.io.rfWriteTag",
            "io.debugExecuteUnsupported := live.io.executeUnsupported",
            "io.debugExecuteCompleteRobValue := live.io.executeCompleteRobValue",
            "io.debugRobCompleteResultBits := Cat(live.io.completeIgnored, live.io.completeAccepted)",
        }
        for connection in forwarded:
            self.assertIn(connection, top)
        self.assertNotIn("live.io.", top.split("private val row =", 1)[0].split("Input(", 1)[0])

        identity_sources = {
            "io.issueQueueInputPc := Mux(inputValid, issue.io.in.pc, 0.U)",
            "io.issueQueueInputRidValue := Mux(inputValid, issue.io.in.rid.value, 0.U)",
            "io.issueQueueOutputPc := Mux(outputValid, issue.io.issueUop.pc, 0.U)",
            "io.issueQueueOutputOpcode := Mux(outputValid, issue.io.issueUop.opcode, 0.U)",
            "io.issueQueueOutputRidValue := Mux(outputValid, issue.io.issueUop.rid.value, 0.U)",
            "io.executeAcceptedIdentityValid := accepted",
            "io.executeAcceptedPc := Mux(accepted, issue.io.issueUop.pc, 0.U)",
            "io.executeAcceptedRidValue := Mux(accepted, issue.io.issueUop.rid.value, 0.U)",
            "io.issueQueueHeadStid := issue.io.headStid",
            "io.issueQueueHeadBidValue := Mux(issue.io.headValid, issue.io.headBid.value, 0.U)",
            "io.issueQueueHeadRidValue := Mux(issue.io.headValid, issue.io.headRid.value, 0.U)",
            "io.issueQueueScalarSpOrderBlocked := issue.io.scalarSpOrderBlocked",
            "io.issueQueueBankScalarSpOrderBlockedMask := issue.io.bankScalarSpOrderBlockedMask",
            "io.scalarSpStid0IssueHeadValid := scalarSpOrder.io.issueHeadValidByStid(0)",
            "scalarSpOrder.io.issueHeadBidByStid(0).value",
            "scalarSpOrder.io.issueHeadRidByStid(0).value",
        }
        for connection in identity_sources:
            self.assertIn(connection, live_top)
        self.assertIn("val accepted = executeAccepted", live_top)
        self.assertIn(
            "LinxCoreFrontendFetchRfAluTraceTopIssueDiagnosticsWiring.connect(",
            live_top,
        )
        self.assertIn("scalarSpOrder,", live_top)
        self.assertNotIn("issueQueueHeadPc := Mux", live_top)

        identity_forwarding = {
            "io.debugIssueInputPc := live.io.issueQueueInputPc",
            "io.debugIssueInputRidValue := live.io.issueQueueInputRidValue",
            "io.debugIssueOutputPc := live.io.issueQueueOutputPc",
            "io.debugIssueOutputRidValue := live.io.issueQueueOutputRidValue",
            "io.debugExecuteAcceptedPc := live.io.executeAcceptedPc",
            "io.debugExecuteAcceptedRidValue := live.io.executeAcceptedRidValue",
        }
        for connection in identity_forwarding:
            self.assertIn(connection, top)

        serialized = {
            "perf_manifest_json",
            "performance",
            "ipc",
            "frontend",
            "decode_rename",
            "issue",
            "execute",
            "completion",
            "commit",
            "rob_head",
            "load_store_service",
            "blocked_bits_hist",
            "rows_per_cycle_hist",
            "commit_head_status",
            "commit_head_rob_value",
            "rob_occupied_mask",
            "rob_completed_mask",
            "decode_block_bits",
            "decode_ready_bits",
            "local_pending_counts",
            "local_ready_masks",
            "tu_rename_source_underflow_mask",
            "tu_rename_blocked_by_t_alloc",
            "tu_rename_blocked_by_u_alloc",
            "tu_rename_t_used_entries",
            "tu_rename_u_used_entries",
            "gpr_reservation_count",
            "gpr_reservation_need",
            "gpr_free_count",
            "gpr_mapq_valid_count",
            "gpr_mapq_free_count",
            "gpr_free_list_mismatch_count",
            "gpr_commit_accepted",
            "gpr_commit_block_bid",
            "gpr_committed_mapq_count",
            "gpr_released_phys_count",
            "rob_rename_update_attempt_valid",
            "rob_rename_update_ready",
            "rob_rename_update_fire",
            "rob_rename_update_ignored",
            "issue_head_pc",
            "issue_head_stid",
            "issue_head_bid_valid",
            "issue_head_bid_value",
            "issue_head_rid_valid",
            "issue_head_rid_value",
            "issue_head_src0_phys_tag",
            "issue_source_ready_mask",
            "issue_scalar_sp_order_blocked",
            "issue_bank_scalar_sp_order_blocked_mask",
            "scalar_sp_stid0_issue_head_valid",
            "scalar_sp_stid0_issue_head_bid_valid",
            "scalar_sp_stid0_issue_head_bid_value",
            "scalar_sp_stid0_issue_head_rid_valid",
            "scalar_sp_stid0_issue_head_rid_value",
            "rf_ready_mask",
            "p_wakeup_tag",
            "p_wakeup_head_match",
            "execute_unsupported",
            "execute_complete_rob_value",
            "rob_complete_arbiter_bits",
            "rob_complete_result_bits",
            "issue_input_pc",
            "issue_input_rid_value",
            "issue_output_valid",
            "issue_output_pc",
            "issue_output_opcode",
            "issue_output_rid_value",
            "execute_accepted_identity_valid",
            "execute_accepted_pc",
            "execute_accepted_rid_value",
        }
        for field in serialized:
            self.assertIn(field, driver)

    def test_tu_occupancy_width_preserves_31_and_32(self):
        top = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreBenchmarkAutonomousTop.scala"
        ).read_text(encoding="utf-8")
        driver = TB.read_text(encoding="utf-8")

        self.assertIn("private val tuCountWidth = log2Ceil(mapQDepth + 1)", top)
        self.assertIn("coreParams.scalarLsu.mapQDepth", top)
        self.assertIn("debugTuRenameTUsedEntries = Output(UInt(tuCountWidth.W))", top)
        self.assertIn("debugTuRenameUUsedEntries = Output(UInt(tuCountWidth.W))", top)
        self.assertNotIn("debugTuRenameTUsedEntries = Output(UInt(3.W))", top)
        self.assertNotIn("debugTuRenameUUsedEntries = Output(UInt(3.W))", top)

        alias_mask = (1 << 3) - 1
        self.assertEqual(31 & alias_mask, 7)
        self.assertEqual(32 & alias_mask, 0)
        self.assertNotEqual(31, 32)
        self.assertIn("std::uint64_t tu_rename_t_used_entries = 0;", driver)
        self.assertIn("std::uint64_t tu_rename_u_used_entries = 0;", driver)

    def test_tu_retire_lifecycle_observability_is_forwarded_and_serialized(self):
        top = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreBenchmarkAutonomousTop.scala"
        ).read_text(encoding="utf-8")
        driver = TB.read_text(encoding="utf-8")

        forwarded = {
            "io.debugTuRetireCommandValid := live.io.tuRetireCommandValid",
            "io.debugTuRetireCommandFire := live.io.tuRetireCommandFire",
            "io.debugTuRetireLocalBlockCommitPending := live.io.tuRetireLocalBlockCommitPending",
            "io.debugTuRetireLocalBlockCommitValid := live.io.tuRetireLocalBlockCommitValid",
            "io.debugTuRetireLocalBlockCommitReady := live.io.tuRetireLocalBlockCommitReady",
            "io.debugTuRetireLocalBlockCommitFire := live.io.tuRetireLocalBlockCommitFire",
            "io.debugTuRetireAccepted := live.io.tuRetireAccepted",
            "io.debugTuRetireMiss := live.io.tuRetireMiss",
            "io.debugTuRetireReleaseMismatch := live.io.tuRetireReleaseMismatch",
            "io.debugTuRetireUnsupported := live.io.tuRetireUnsupported",
            "io.debugRobDeallocValidMask := live.io.deallocValidMask",
            "io.debugRobDeallocCount := live.io.deallocCount",
            "io.debugRobDeallocBlockLastValid := live.io.robDeallocBlockLastValid",
            "io.debugRobDeallocBlockLastBlockBid := live.io.robDeallocBlockLastBlockBid",
            "io.debugBlockScalarDoneFire := live.io.blockScalarDoneFire",
            "io.debugBlockScalarDoneBid := live.io.blockScalarDoneBid",
            "io.debugBlockRetireFire := live.io.blockRetireFire",
            "io.debugBlockRetireBid := live.io.blockRetireBid",
        }
        for connection in forwarded:
            self.assertIn(connection, top)

        serialized = {
            "tu_retire_command_valid",
            "tu_retire_command_fire",
            "tu_retire_local_block_commit_pending",
            "tu_retire_local_block_commit_valid",
            "tu_retire_local_block_commit_ready",
            "tu_retire_local_block_commit_fire",
            "tu_retire_accepted",
            "tu_retire_miss",
            "tu_retire_release_mismatch",
            "tu_retire_unsupported",
            "rob_dealloc_valid_mask",
            "rob_dealloc_count",
            "rob_dealloc_block_last_valid",
            "rob_dealloc_block_last_block_bid",
            "block_scalar_done_fire",
            "block_scalar_done_bid",
            "block_retire_fire",
            "block_retire_bid",
        }
        for field in serialized:
            self.assertIn(field, driver)

    def test_autonomous_top_opts_into_reduced_sc_store_execution(self):
        top_path = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreBenchmarkAutonomousTop.scala"
        )
        live_top_path = (
            ROOT
            / "chisel"
            / "src"
            / "main"
            / "scala"
            / "linxcore"
            / "top"
            / "LinxCoreFrontendFetchRfAluTraceTop.scala"
        )
        top = top_path.read_text(encoding="utf-8")
        live_top = live_top_path.read_text(encoding="utf-8")

        self.assertIn("useReducedStoreDispatchStq = true", top)
        self.assertIn("useReducedStoreStaAddressExecBridge = true", top)
        self.assertIn("val useReducedStoreDispatchStq: Boolean = false", live_top)
        self.assertIn("val useReducedStoreStaAddressExecBridge: Boolean = false", live_top)
        self.assertIn(
            "storeStaAddressExecBridge.io.enable := useReducedStoreStaAddressExecBridge && useReducedStoreDispatchStq",
            live_top,
        )
        self.assertIn("scalarScIssueGate.ready", live_top)
        self.assertNotIn("val ready = false.B", live_top)


if __name__ == "__main__":
    unittest.main()
