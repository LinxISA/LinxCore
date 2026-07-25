package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common.{DispatchTarget, InterfaceParams, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process._

class ReducedScalarAluFretConditionProbeIO extends Bundle {
  val inValid = Input(Bool())
  val opcode = Input(UInt(12.W))
  val pc = Input(UInt(64.W))
  val insnRaw = Input(UInt(64.W))
  val imm = Input(UInt(64.W))
  val src0Data = Input(UInt(64.W))
  val src1Data = Input(UInt(64.W))
  val stackPointerData = Input(UInt(64.W))
  val loadLookupData = Input(UInt(64.W))
  val fretStkContextValid = Input(Bool())
  val fretStkConditionValid = Input(Bool())
  val fretStkConditionTaken = Input(Bool())
  val fretStkFallbackTargetValid = Input(Bool())
  val fretStkFallbackTarget = Input(UInt(64.W))
  val liveConditionValid = Input(Bool())
  val liveConditionTaken = Input(Bool())
  val flushValid = Input(Bool())

  val inReady = Output(Bool())
  val accepted = Output(Bool())
  val busy = Output(Bool())
  val completeValid = Output(Bool())
  val branchConditionValid = Output(Bool())
  val branchConditionTaken = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(64.W))
  val redirectValid = Output(Bool())
  val redirectPc = Output(UInt(64.W))
  val unsupported = Output(Bool())
}

class ReducedScalarAluFretConditionProbe extends Module {
  private val p = InterfaceParams()
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)

  val io = IO(new ReducedScalarAluFretConditionProbeIO)
  val execute = Module(new ReducedScalarAluExecute(p, trace))
  execute.io.completeReady := true.B

  execute.io.inValid := io.inValid
  execute.io.in := 0.U.asTypeOf(execute.io.in)
  execute.io.in.valid := io.inValid
  execute.io.in.peId := 0.U
  execute.io.in.threadId := 0.U
  execute.io.in.pc := io.pc
  execute.io.in.opcode := io.opcode
  execute.io.in.dispatchTarget := DispatchTarget.Alu
  execute.io.in.imm := io.imm
  execute.io.in.immValid := true.B
  execute.io.in.rid.valid := true.B
  execute.io.in.rid.wrap := false.B
  execute.io.in.rid.value := 3.U
  execute.io.in.bid.valid := true.B
  execute.io.in.bid.wrap := false.B
  execute.io.in.bid.value := 1.U
  execute.io.in.gid.valid := true.B
  execute.io.in.gid.wrap := false.B
  execute.io.in.gid.value := 2.U
  execute.io.in.lsid := 0.U
  execute.io.in.isLoad := false.B
  execute.io.in.isStore := false.B
  execute.io.in.insnLen := 4.U
  execute.io.in.insnRaw := io.insnRaw
  execute.io.in.blockBidValid := true.B
  execute.io.in.blockBid := 0x55.U
  execute.io.in.fretStkContextValid := io.fretStkContextValid
  execute.io.in.fretStkConditionValid := io.fretStkConditionValid
  execute.io.in.fretStkConditionTaken := io.fretStkConditionTaken
  execute.io.in.fretStkFallbackTargetValid := io.fretStkFallbackTargetValid
  execute.io.in.fretStkFallbackTarget := io.fretStkFallbackTarget
  for (idx <- 0 until 2) {
    execute.io.in.src(idx).valid := true.B
    execute.io.in.src(idx).operandClass := OperandClass.P
    execute.io.in.src(idx).archTag := idx.U
    execute.io.in.src(idx).physTag := (idx + 10).U
    execute.io.in.src(idx).ready := true.B
  }

  execute.io.srcData(0) := io.src0Data
  execute.io.srcData(1) := io.src1Data
  execute.io.srcData(2) := 0.U
  execute.io.loadLookupData := io.loadLookupData
  execute.io.loadPairFirstLookupData := 0.U
  execute.io.loadLookupWaitBlocked := false.B
  execute.io.loadLiqEnable := false.B
  execute.io.loadLiqAccepted := false.B
  execute.io.stackPointerData := io.stackPointerData
  execute.io.flushValid := io.flushValid
  execute.io.fretStkFallbackTargetValid := io.fretStkFallbackTargetValid
  execute.io.fretStkFallbackTarget := io.fretStkFallbackTarget
  execute.io.fretStkConditionValid := io.liveConditionValid
  execute.io.fretStkConditionTaken := io.liveConditionTaken

  io.inReady := execute.io.inReady
  io.accepted := execute.io.accepted
  io.busy := execute.io.busy
  io.completeValid := execute.io.completeValid
  io.branchConditionValid := execute.io.branchConditionValid
  io.branchConditionTaken := execute.io.branchConditionTaken
  io.loadLookupValid := execute.io.loadLookupValid
  io.loadLookupAddr := execute.io.loadLookupAddr
  io.redirectValid := execute.io.redirectValid
  io.redirectPc := execute.io.redirectPc
  io.unsupported := execute.io.unsupported
}

object ReducedScalarAluFretConditionReference {
  final case class Result(loadReturn: Boolean, redirect: Boolean)

  def apply(
      contextValid: Boolean,
      snapshotConditionValid: Boolean,
      snapshotConditionTaken: Boolean,
      liveConditionValid: Boolean,
      liveConditionTaken: Boolean,
      fallbackTargetValid: Boolean): Result = {
    val conditionValid = if (contextValid) snapshotConditionValid else liveConditionValid
    val conditionTaken = if (contextValid) snapshotConditionTaken else liveConditionTaken
    val loadReturn = conditionValid && !conditionTaken && fallbackTargetValid
    val redirect = fallbackTargetValid && conditionValid && conditionTaken
    Result(loadReturn = loadReturn, redirect = redirect)
  }
}

class ReducedScalarAluFretConditionProbeSpec extends AnyFunSuite {
  private val fretStkRaInsn = BigInt("02a53041", 16)
  private val setcNeInsn = BigInt("00000000", 16)
  private val fallbackPc = BigInt("12988", 16)

  private def writeUtf8(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  private def compileAndRunHarness(harnessName: String, harnessBody: String): Unit = {
    val dir = Files.createTempDirectory("linxcore-fret-condition-probe-")
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarAluFretConditionProbe)
    writeUtf8(dir.resolve("ReducedScalarAluFretConditionProbe.sv"), sv)
    writeUtf8(dir.resolve("layers-ReducedScalarAluFretConditionProbe-Verification.sv"), "")
    writeUtf8(dir.resolve(s"$harnessName.cpp"), harnessBody)
    val cmd = Seq(
      "verilator",
      "--cc",
      "--exe",
      "--build",
      "--Mdir",
      dir.resolve("obj_dir").toString,
      "-Wno-fatal",
      dir.resolve("ReducedScalarAluFretConditionProbe.sv").toString,
      dir.resolve(s"$harnessName.cpp").toString,
      "--top-module",
      "ReducedScalarAluFretConditionProbe")
    val status = Process(cmd, dir.toFile).!
    assert(status == 0, s"verilator harness $harnessName failed with status $status in $dir")
  }

  private def baseHarness(body: String): String =
    s"""
#include "VReducedScalarAluFretConditionProbe.h"
#include "verilated.h"
#include <cstdint>

static void eval_cycle(VReducedScalarAluFretConditionProbe& top) {
  top.eval();
}

static void tick(VReducedScalarAluFretConditionProbe& top) {
  top.clock = 0;
  top.eval();
  top.clock = 1;
  top.eval();
  top.clock = 0;
  top.eval();
}

static void clear_inputs(VReducedScalarAluFretConditionProbe& top) {
  top.io_inValid = 0;
  top.io_opcode = 0;
  top.io_pc = 0;
  top.io_insnRaw = 0;
  top.io_imm = 0;
  top.io_src0Data = 0;
  top.io_src1Data = 0;
  top.io_stackPointerData = 0;
  top.io_loadLookupData = 0;
  top.io_fretStkContextValid = 0;
  top.io_fretStkConditionValid = 0;
  top.io_fretStkConditionTaken = 0;
  top.io_fretStkFallbackTargetValid = 0;
  top.io_fretStkFallbackTarget = 0;
  top.io_liveConditionValid = 0;
  top.io_liveConditionTaken = 0;
  top.io_flushValid = 0;
}

static void reset(VReducedScalarAluFretConditionProbe& top) {
  clear_inputs(top);
  top.reset = 1;
  tick(top);
  tick(top);
  top.reset = 0;
  tick(top);
}

static void drive_fret(VReducedScalarAluFretConditionProbe& top,
                       bool context_valid,
                       bool snapshot_valid,
                       bool snapshot_taken,
                       bool live_valid,
                       bool live_taken,
                       bool fallback_valid = true) {
  clear_inputs(top);
  top.io_inValid = 1;
  top.io_opcode = ${FrontendOpcodeDecodeTable.OP_FRET_STK};
  top.io_pc = 0x1299aULL;
  top.io_insnRaw = 0x${fretStkRaInsn.toString(16)}ULL;
  top.io_imm = 8;
  top.io_stackPointerData = 0x388d0ULL;
  top.io_loadLookupData = 0x12345678ULL;
  top.io_fretStkContextValid = context_valid;
  top.io_fretStkConditionValid = snapshot_valid;
  top.io_fretStkConditionTaken = snapshot_taken;
  top.io_fretStkFallbackTargetValid = fallback_valid;
  top.io_fretStkFallbackTarget = 0x${fallbackPc.toString(16)}ULL;
  top.io_liveConditionValid = live_valid;
  top.io_liveConditionTaken = live_taken;
}

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  VReducedScalarAluFretConditionProbe top;
  reset(top);
  $body
  top.final();
  return 0;
}
"""

  test("does not fire FRET when context is invalid and no live SETC condition exists") {
    val expected = ReducedScalarAluFretConditionReference(
      contextValid = false,
      snapshotConditionValid = false,
      snapshotConditionTaken = false,
      liveConditionValid = false,
      liveConditionTaken = false,
      fallbackTargetValid = true)
    assert(!expected.loadReturn)
    assert(!expected.redirect)

    compileAndRunHarness(
      "fret_no_condition",
      baseHarness("""
  drive_fret(top, false, false, false, false, false);
  eval_cycle(top);
  if (!top.io_inReady) return 10;
  tick(top);
  clear_inputs(top);
  bool saw_load = false;
  bool saw_redirect = false;
  for (int i = 0; i < 5; ++i) {
    eval_cycle(top);
    saw_load = saw_load || top.io_loadLookupValid;
    saw_redirect = saw_redirect || top.io_redirectValid;
    tick(top);
  }
  if (saw_load) return 11;
  if (saw_redirect) return 12;
"""))
  }

  test("allows FRET to fire from a same-cycle W2 SETC condition") {
    val expected = ReducedScalarAluFretConditionReference(
      contextValid = false,
      snapshotConditionValid = false,
      snapshotConditionTaken = false,
      liveConditionValid = true,
      liveConditionTaken = true,
      fallbackTargetValid = true)
    assert(!expected.loadReturn)
    assert(expected.redirect)

    compileAndRunHarness(
      "fret_w2_condition",
      baseHarness(s"""
  clear_inputs(top);
  top.io_inValid = 1;
  top.io_opcode = ${FrontendOpcodeDecodeTable.OP_C_SETC_NE};
  top.io_pc = 0x12998ULL;
  top.io_insnRaw = 0x${setcNeInsn.toString(16)}ULL;
  top.io_src0Data = 0x44ULL;
  top.io_src1Data = 0x0ULL;
  eval_cycle(top);
  if (!top.io_inReady) return 20;
  tick(top);
  clear_inputs(top);
  tick(top);
  drive_fret(top, false, false, false, false, false);
  eval_cycle(top);
  if (!top.io_inReady) return 21;
  tick(top);
  clear_inputs(top);
  eval_cycle(top);
  if (!top.io_branchConditionValid) return 22;
  if (!top.io_branchConditionTaken) return 23;
  bool saw_redirect = false;
  for (int i = 0; i < 5; ++i) {
    eval_cycle(top);
    saw_redirect = saw_redirect || (top.io_redirectValid && top.io_redirectPc == 0x${fallbackPc.toString(16)}ULL);
    tick(top);
  }
  if (!saw_redirect) return 24;
"""))
  }

  test("allows FRET to fire from a latched live condition") {
    val expected = ReducedScalarAluFretConditionReference(
      contextValid = false,
      snapshotConditionValid = false,
      snapshotConditionTaken = false,
      liveConditionValid = true,
      liveConditionTaken = true,
      fallbackTargetValid = true)
    assert(!expected.loadReturn)
    assert(expected.redirect)

    compileAndRunHarness(
      "fret_latched_condition",
      baseHarness(s"""
  drive_fret(top, false, false, false, true, true);
  eval_cycle(top);
  if (!top.io_inReady) return 30;
  tick(top);
  clear_inputs(top);
  bool saw_redirect = false;
  for (int i = 0; i < 5; ++i) {
    eval_cycle(top);
    saw_redirect = saw_redirect || (top.io_redirectValid && top.io_redirectPc == 0x${fallbackPc.toString(16)}ULL);
    tick(top);
  }
  if (!saw_redirect) return 31;
"""))
  }

  test("an explicit empty FRET snapshot ignores a younger live marker target") {
    compileAndRunHarness(
      "fret_empty_snapshot",
      baseHarness(s"""
  drive_fret(top, true, false, false, false, false, false);
  eval_cycle(top);
  if (!top.io_inReady) return 40;
  tick(top);

  // Model decode installing an unrelated younger marker in the cycle after
  // issue.  The accepted FRET snapshot says that neither a condition nor a
  // fallback target belonged to this return, so it must load RA instead of
  // redirecting to the new marker.
  clear_inputs(top);
  top.io_fretStkFallbackTargetValid = 1;
  top.io_fretStkFallbackTarget = 0x${fallbackPc.toString(16)}ULL;
  top.io_liveConditionValid = 1;
  top.io_liveConditionTaken = 1;
  top.io_loadLookupData = 0x12345678ULL;
  bool saw_load = false;
  bool saw_return = false;
  bool saw_younger_redirect = false;
  for (int i = 0; i < 6; ++i) {
    eval_cycle(top);
    saw_load = saw_load || top.io_loadLookupValid;
    saw_return = saw_return || (top.io_redirectValid && top.io_redirectPc == 0x12345678ULL);
    saw_younger_redirect = saw_younger_redirect ||
      (top.io_redirectValid && top.io_redirectPc == 0x${fallbackPc.toString(16)}ULL);
    tick(top);
  }
  if (!saw_load) return 41;
  if (!saw_return) return 42;
  if (saw_younger_redirect) return 43;
"""))
  }
}
