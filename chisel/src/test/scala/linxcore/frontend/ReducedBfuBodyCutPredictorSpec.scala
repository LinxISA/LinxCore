package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuBodyCutPredictorReference {
  final case class Result(cutActive: Boolean, cutPc: BigInt, restartPc: BigInt, advanceBytes: Int, validMask: Int)

  private val WindowBytes = 8

  def predict(
      geometryValid: Boolean,
      headerPc: BigInt,
      hsizeBytes: BigInt,
      bsizeBytes: BigInt,
      f4Valid: Boolean,
      f4Pc: BigInt,
      f4TotalLenBytes: Int,
      slotPcs: Seq[BigInt],
      f4ValidMask: Int): Result = {
    val bodyEndPc = headerPc + 2 + bsizeBytes
    val restartPc = headerPc + hsizeBytes
    val cutActive = geometryValid && f4Valid && bodyEndPc > f4Pc && bodyEndPc <= f4Pc + WindowBytes
    val validMask =
      if (!cutActive) {
        f4ValidMask
      } else {
        slotPcs.zipWithIndex.foldLeft(0) { case (mask, (pc, slot)) =>
          val bit = 1 << slot
          if ((f4ValidMask & bit) != 0 && pc < bodyEndPc) mask | bit else mask
        }
      }

    Result(
      cutActive = cutActive,
      cutPc = bodyEndPc,
      restartPc = restartPc,
      advanceBytes = if (cutActive) (bodyEndPc - f4Pc).toInt else f4TotalLenBytes,
      validMask = validMask
    )
  }
}

class ReducedBfuBodyCutPredictorProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val geometryValid = Input(Bool())
  val headerPc = Input(UInt(p.pcWidth.W))
  val hsizeBytes = Input(UInt(p.pcWidth.W))
  val bsizeBytes = Input(UInt(p.pcWidth.W))
  val f4Valid = Input(Bool())
  val f4Pc = Input(UInt(p.pcWidth.W))
  val f4Slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val f4ValidMask = Input(UInt(p.decodeWidth.W))
  val f4TotalLenBytes = Input(UInt(4.W))

  val cutActive = Output(Bool())
  val cutPc = Output(UInt(p.pcWidth.W))
  val restartPc = Output(UInt(p.pcWidth.W))
  val advanceBytes = Output(UInt(4.W))
  val validMask = Output(UInt(p.decodeWidth.W))
}

class ReducedBfuBodyCutPredictorProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuBodyCutPredictorProbeIO(p))
  val predictor = Module(new ReducedBfuBodyCutPredictor(p))

  predictor.io.geometryValid := io.geometryValid
  predictor.io.headerPc := io.headerPc
  predictor.io.hsizeBytes := io.hsizeBytes
  predictor.io.bsizeBytes := io.bsizeBytes
  predictor.io.f4Valid := io.f4Valid
  predictor.io.f4Pc := io.f4Pc
  predictor.io.f4Slots := io.f4Slots
  predictor.io.f4ValidMask := io.f4ValidMask
  predictor.io.f4TotalLenBytes := io.f4TotalLenBytes

  io.cutActive := predictor.io.cutActive
  io.cutPc := predictor.io.cutPc
  io.restartPc := predictor.io.restartPc
  io.advanceBytes := predictor.io.advanceBytes
  io.validMask := predictor.io.validMask
}

class ReducedBfuBodyCutPredictorSpec extends AnyFunSuite {
  test("reference computes CoreMark FALL body boundary from BFU hsize and bsize geometry") {
    val result = ReducedBfuBodyCutPredictorReference.predict(
      geometryValid = true,
      headerPc = BigInt("4000630c", 16),
      hsizeBytes = 0,
      bsizeBytes = 0x20,
      f4Valid = true,
      f4Pc = BigInt("4000632a", 16),
      f4TotalLenBytes = 8,
      slotPcs = Seq(BigInt("4000632a", 16), BigInt("4000632c", 16), BigInt("4000632e", 16), BigInt(0)),
      f4ValidMask = 0x7
    )

    assert(result.cutActive)
    assert(result.cutPc == BigInt("4000632e", 16))
    assert(result.restartPc == BigInt("4000630c", 16))
    assert(result.advanceBytes == 4)
    assert(result.validMask == 0x3)
  }

  test("reference passes through when the body boundary is outside the current F4 window") {
    val result = ReducedBfuBodyCutPredictorReference.predict(
      geometryValid = true,
      headerPc = 0x1000,
      hsizeBytes = 0,
      bsizeBytes = 0x40,
      f4Valid = true,
      f4Pc = 0x1010,
      f4TotalLenBytes = 8,
      slotPcs = Seq(BigInt(0x1010), BigInt(0x1012), BigInt(0x1014), BigInt(0x1016)),
      f4ValidMask = 0xf
    )

    assert(!result.cutActive)
    assert(result.advanceBytes == 8)
    assert(result.validMask == 0xf)
  }

  test("ReducedBfuBodyCutPredictor elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuBodyCutPredictorProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuBodyCutPredictorProbe"))
    assert(sv.contains("module ReducedBfuBodyCutPredictor"))
    assert(sv.contains("io_cutActive"))
    assert(sv.contains("io_advanceBytes"))
  }
}
