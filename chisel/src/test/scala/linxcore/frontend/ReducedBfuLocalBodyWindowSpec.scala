package linxcore.frontend

import chisel3._
import chisel3.util.log2Ceil
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuLocalBodyWindowReference {
  final case class Prediction(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bsizeBytes: BigInt = 0)
  final case class F4Header(valid: Boolean = false, pc: BigInt = 0, isBoundary: Boolean = false)
  final case class Result(
      geometryValid: Boolean,
      headerPc: BigInt,
      hsizeBytes: BigInt,
      bsizeBytes: BigInt,
      active: Boolean,
      armFire: Boolean,
      releaseFire: Boolean)

  final class State {
    private var activeReg = false
    private var headerPcReg = BigInt(0)
    private var hsizeReg = BigInt(0)
    private var bsizeReg = BigInt(0)

    def step(
        flush: Boolean = false,
        f4ScanValid: Boolean = false,
        cutFire: Boolean = false,
        prediction: Prediction = Prediction(),
        headers: Seq[F4Header] = Seq.empty): Result = {
      val headerMatch = headers.exists(h => h.valid && h.isBoundary && h.pc == prediction.headerPc)
      val armFire = !flush && f4ScanValid && !activeReg && prediction.valid && headerMatch
      val geometryValid = !flush && (activeReg || armFire)
      val result = Result(
        geometryValid = geometryValid,
        headerPc = if (activeReg) headerPcReg else prediction.headerPc,
        hsizeBytes = if (activeReg) hsizeReg else prediction.hsizeBytes,
        bsizeBytes = if (activeReg) bsizeReg else prediction.bsizeBytes,
        active = activeReg,
        armFire = armFire,
        releaseFire = !flush && activeReg && cutFire)

      if (flush || cutFire) {
        activeReg = false
        headerPcReg = 0
        hsizeReg = 0
        bsizeReg = 0
      } else if (armFire) {
        activeReg = true
        headerPcReg = prediction.headerPc
        hsizeReg = prediction.hsizeBytes
        bsizeReg = prediction.bsizeBytes
      }
      result
    }
  }
}

class ReducedBfuLocalBodyWindowProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val f4ScanValid = Input(Bool())
  val cutFire = Input(Bool())
  val predictionValid = Input(Bool())
  val predictionHeaderPc = Input(UInt(p.pcWidth.W))
  val predictionHSizeBytes = Input(UInt(p.pcWidth.W))
  val predictionBSizeBytes = Input(UInt(p.pcWidth.W))
  val f4Valid = Input(Bool())
  val f4SlotValid = Input(Vec(p.decodeWidth, Bool()))
  val f4SlotPc = Input(Vec(p.decodeWidth, UInt(p.pcWidth.W)))
  val f4SlotLenBytes = Input(Vec(p.decodeWidth, UInt(4.W)))
  val f4SlotInsnRaw = Input(Vec(p.decodeWidth, UInt(p.insnWidth.W)))
  val f4ValidMask = Input(UInt(p.decodeWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val active = Output(Bool())
  val armFire = Output(Bool())
  val releaseFire = Output(Bool())
  val armSlot = Output(UInt(log2Ceil(p.decodeWidth).W))
}

class ReducedBfuLocalBodyWindowProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuLocalBodyWindowProbeIO(p))
  val owner = Module(new ReducedBfuLocalBodyWindow(p))

  owner.io.flushValid := io.flushValid
  owner.io.f4ScanValid := io.f4ScanValid
  owner.io.cutFire := io.cutFire
  owner.io.predictionValid := io.predictionValid
  owner.io.predictionHeaderPc := io.predictionHeaderPc
  owner.io.predictionHSizeBytes := io.predictionHSizeBytes
  owner.io.predictionBSizeBytes := io.predictionBSizeBytes
  owner.io.f4Valid := io.f4Valid
  owner.io.f4ValidMask := io.f4ValidMask
  for (slot <- 0 until p.decodeWidth) {
    owner.io.f4Slots(slot).valid := io.f4SlotValid(slot)
    owner.io.f4Slots(slot).pc := io.f4SlotPc(slot)
    owner.io.f4Slots(slot).offsetBytes := 0.U
    owner.io.f4Slots(slot).lenBytes := io.f4SlotLenBytes(slot)
    owner.io.f4Slots(slot).insnRaw := io.f4SlotInsnRaw(slot)
    owner.io.f4Slots(slot).uopUid := 0.U
    owner.io.f4Slots(slot).isLastInBlock := false.B
  }

  io.geometryValid := owner.io.geometryValid
  io.headerPc := owner.io.headerPc
  io.hsizeBytes := owner.io.hsizeBytes
  io.bsizeBytes := owner.io.bsizeBytes
  io.active := owner.io.active
  io.armFire := owner.io.armFire
  io.releaseFire := owner.io.releaseFire
  io.armSlot := owner.io.armSlot
}

class ReducedBfuLocalBodyWindowSpec extends AnyFunSuite {
  test("reference arms on a matching local block header and holds geometry until cut") {
    val state = new ReducedBfuLocalBodyWindowReference.State
    val prediction = ReducedBfuLocalBodyWindowReference.Prediction(
      valid = true,
      headerPc = BigInt("4000630c", 16),
      hsizeBytes = 0,
      bsizeBytes = 0x20)
    val header = ReducedBfuLocalBodyWindowReference.F4Header(
      valid = true,
      pc = BigInt("4000630c", 16),
      isBoundary = true)

    val armed = state.step(f4ScanValid = true, prediction = prediction, headers = Seq(header))
    assert(armed.geometryValid)
    assert(armed.armFire)
    assert(!armed.active)
    assert(armed.headerPc == prediction.headerPc)
    assert(armed.bsizeBytes == 0x20)

    val held = state.step()
    assert(held.geometryValid)
    assert(held.active)
    assert(!held.armFire)
    assert(held.headerPc == prediction.headerPc)

    val released = state.step(cutFire = true)
    assert(released.geometryValid)
    assert(released.releaseFire)
    assert(!state.step().geometryValid)
  }

  test("reference rejects nonmatching headers and suppresses flush-cycle arming") {
    val state = new ReducedBfuLocalBodyWindowReference.State
    val prediction = ReducedBfuLocalBodyWindowReference.Prediction(
      valid = true,
      headerPc = 0x2000,
      hsizeBytes = 2,
      bsizeBytes = 0x10)
    val wrongHeader = ReducedBfuLocalBodyWindowReference.F4Header(valid = true, pc = 0x3000, isBoundary = true)
    val rightHeader = ReducedBfuLocalBodyWindowReference.F4Header(valid = true, pc = 0x2000, isBoundary = true)

    val rejected = state.step(f4ScanValid = true, prediction = prediction, headers = Seq(wrongHeader))
    assert(!rejected.geometryValid)
    assert(!rejected.armFire)

    val flushed = state.step(flush = true, f4ScanValid = true, prediction = prediction, headers = Seq(rightHeader))
    assert(!flushed.geometryValid)
    assert(!flushed.armFire)
    assert(!state.step().geometryValid)
  }

  test("ReducedBfuLocalBodyWindow elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuLocalBodyWindowProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuLocalBodyWindowProbe"))
    assert(sv.contains("module ReducedBfuLocalBodyWindow"))
    assert(sv.contains("io_armFire"))
    assert(sv.contains("io_releaseFire"))
  }
}
