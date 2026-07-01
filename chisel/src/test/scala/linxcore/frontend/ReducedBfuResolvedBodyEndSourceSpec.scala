package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuResolvedBodyEndSourceReference {
  final case class RuntimeEvent(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bodyEndPc: BigInt = 0)
  final case class ReplayEvent(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bsizeBytes: BigInt = 0)
  final case class Result(
      resolvedValid: Boolean,
      resolvedHeaderPc: BigInt,
      resolvedHSizeBytes: BigInt,
      resolvedBodyEndPc: BigInt,
      replayBodyEndPc: BigInt,
      selectedRuntime: Boolean,
      selectedReplay: Boolean,
      comparable: Boolean,
      matchAll: Boolean,
      headerMismatch: Boolean,
      hsizeMismatch: Boolean,
      bodyEndMismatch: Boolean)

  private val PcMask = (BigInt(1) << 64) - 1

  def step(runtime: RuntimeEvent = RuntimeEvent(), replay: ReplayEvent = ReplayEvent()): Result = {
    val replayBodyEndPc = (replay.headerPc + 2 + replay.bsizeBytes) & PcMask
    val selectedRuntime = runtime.valid
    val selectedReplay = !runtime.valid && replay.valid
    val comparable = runtime.valid && replay.valid
    val headerMatch = runtime.headerPc == replay.headerPc
    val hsizeMatch = runtime.hsizeBytes == replay.hsizeBytes
    val bodyEndMatch = runtime.bodyEndPc == replayBodyEndPc
    Result(
      resolvedValid = selectedRuntime || selectedReplay,
      resolvedHeaderPc = if (selectedRuntime) runtime.headerPc else replay.headerPc,
      resolvedHSizeBytes = if (selectedRuntime) runtime.hsizeBytes else replay.hsizeBytes,
      resolvedBodyEndPc = if (selectedRuntime) runtime.bodyEndPc else replayBodyEndPc,
      replayBodyEndPc = replayBodyEndPc,
      selectedRuntime = selectedRuntime,
      selectedReplay = selectedReplay,
      comparable = comparable,
      matchAll = comparable && headerMatch && hsizeMatch && bodyEndMatch,
      headerMismatch = comparable && !headerMatch,
      hsizeMismatch = comparable && !hsizeMatch,
      bodyEndMismatch = comparable && !bodyEndMatch)
  }
}

class ReducedBfuResolvedBodyEndSourceProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val runtimeValid = Input(Bool())
  val runtimeHeaderPc = Input(UInt(p.pcWidth.W))
  val runtimeHSizeBytes = Input(UInt(p.pcWidth.W))
  val runtimeBodyEndPc = Input(UInt(p.pcWidth.W))
  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val resolvedValid = Output(Bool())
  val resolvedHeaderPc = Output(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Output(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Output(UInt(p.pcWidth.W))
  val selectedRuntime = Output(Bool())
  val selectedReplay = Output(Bool())
  val runtimeReplayComparable = Output(Bool())
  val runtimeReplayMatch = Output(Bool())
  val runtimeReplayBodyEndMismatch = Output(Bool())
}

class ReducedBfuResolvedBodyEndSourceProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndSourceProbeIO(p))
  val source = Module(new ReducedBfuResolvedBodyEndSource(p))

  source.io.runtimeValid := io.runtimeValid
  source.io.runtimeHeaderPc := io.runtimeHeaderPc
  source.io.runtimeHSizeBytes := io.runtimeHSizeBytes
  source.io.runtimeBodyEndPc := io.runtimeBodyEndPc
  source.io.replayValid := io.replayValid
  source.io.replayHeaderPc := io.replayHeaderPc
  source.io.replayHSizeBytes := io.replayHSizeBytes
  source.io.replayBSizeBytes := io.replayBSizeBytes

  io.resolvedValid := source.io.resolvedValid
  io.resolvedHeaderPc := source.io.resolvedHeaderPc
  io.resolvedHSizeBytes := source.io.resolvedHSizeBytes
  io.resolvedBodyEndPc := source.io.resolvedBodyEndPc
  io.selectedRuntime := source.io.selectedRuntime
  io.selectedReplay := source.io.selectedReplay
  io.runtimeReplayComparable := source.io.runtimeReplayComparable
  io.runtimeReplayMatch := source.io.runtimeReplayMatch
  io.runtimeReplayBodyEndMismatch := source.io.runtimeReplayBodyEndMismatch
}

class ReducedBfuResolvedBodyEndSourceSpec extends AnyFunSuite {
  test("reference converts replay bsize into a resolved body-end event") {
    val result = ReducedBfuResolvedBodyEndSourceReference.step(
      replay = ReducedBfuResolvedBodyEndSourceReference.ReplayEvent(
        valid = true,
        headerPc = BigInt("4000630c", 16),
        hsizeBytes = 0,
        bsizeBytes = 0x20))

    assert(result.resolvedValid)
    assert(result.selectedReplay)
    assert(!result.selectedRuntime)
    assert(result.resolvedHeaderPc == BigInt("4000630c", 16))
    assert(result.resolvedBodyEndPc == BigInt("4000632e", 16))
  }

  test("reference prioritizes runtime feedback and compares it with replay oracle") {
    val runtime = ReducedBfuResolvedBodyEndSourceReference.RuntimeEvent(
      valid = true,
      headerPc = BigInt("4000630c", 16),
      hsizeBytes = 0,
      bodyEndPc = BigInt("4000632e", 16))
    val replay = ReducedBfuResolvedBodyEndSourceReference.ReplayEvent(
      valid = true,
      headerPc = BigInt("4000630c", 16),
      hsizeBytes = 0,
      bsizeBytes = 0x20)
    val result = ReducedBfuResolvedBodyEndSourceReference.step(runtime = runtime, replay = replay)

    assert(result.resolvedValid)
    assert(result.selectedRuntime)
    assert(!result.selectedReplay)
    assert(result.comparable)
    assert(result.matchAll)
    assert(result.resolvedBodyEndPc == runtime.bodyEndPc)
  }

  test("reference reports runtime/replay body-end mismatches") {
    val runtime = ReducedBfuResolvedBodyEndSourceReference.RuntimeEvent(
      valid = true,
      headerPc = 0x1000,
      hsizeBytes = 0,
      bodyEndPc = 0x1010)
    val replay = ReducedBfuResolvedBodyEndSourceReference.ReplayEvent(
      valid = true,
      headerPc = 0x1000,
      hsizeBytes = 0,
      bsizeBytes = 0x20)
    val result = ReducedBfuResolvedBodyEndSourceReference.step(runtime = runtime, replay = replay)

    assert(result.comparable)
    assert(!result.matchAll)
    assert(result.bodyEndMismatch)
    assert(!result.headerMismatch)
    assert(!result.hsizeMismatch)
  }

  test("ReducedBfuResolvedBodyEndSource elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuResolvedBodyEndSourceProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuResolvedBodyEndSourceProbe"))
    assert(sv.contains("module ReducedBfuResolvedBodyEndSource"))
    assert(sv.contains("io_selectedRuntime"))
    assert(sv.contains("io_runtimeReplayBodyEndMismatch"))
  }
}
