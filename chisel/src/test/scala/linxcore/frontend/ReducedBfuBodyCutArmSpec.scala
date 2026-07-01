package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuBodyCutArmReference {
  final case class Inputs(
      predictionValid: Boolean = false,
      predictionHeaderPc: BigInt = 0,
      predictionHSizeBytes: BigInt = 0,
      predictionBSizeBytes: BigInt = 0,
      armValid: Boolean = false,
      armHeaderPc: BigInt = 0,
      armHSizeBytes: BigInt = 0,
      armBSizeBytes: BigInt = 0)
  final case class Result(
      geometryValid: Boolean,
      headerPc: BigInt,
      hsizeBytes: BigInt,
      bsizeBytes: BigInt,
      comparable: Boolean,
      accepted: Boolean,
      headerMismatch: Boolean,
      hsizeMismatch: Boolean,
      bsizeMismatch: Boolean)

  def apply(in: Inputs): Result = {
    val comparable = in.predictionValid && in.armValid
    val headerMatch = in.predictionHeaderPc == in.armHeaderPc
    val hsizeMatch = in.predictionHSizeBytes == in.armHSizeBytes
    val bsizeMatch = in.predictionBSizeBytes == in.armBSizeBytes
    val accepted = comparable && headerMatch && hsizeMatch && bsizeMatch

    Result(
      geometryValid = accepted,
      headerPc = in.predictionHeaderPc,
      hsizeBytes = in.predictionHSizeBytes,
      bsizeBytes = in.predictionBSizeBytes,
      comparable = comparable,
      accepted = accepted,
      headerMismatch = comparable && !headerMatch,
      hsizeMismatch = comparable && !hsizeMatch,
      bsizeMismatch = comparable && !bsizeMatch)
  }
}

class ReducedBfuBodyCutArmProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val predictionValid = Input(Bool())
  val predictionHeaderPc = Input(UInt(p.pcWidth.W))
  val predictionHSizeBytes = Input(UInt(p.pcWidth.W))
  val predictionBSizeBytes = Input(UInt(p.pcWidth.W))
  val armValid = Input(Bool())
  val armHeaderPc = Input(UInt(p.pcWidth.W))
  val armHSizeBytes = Input(UInt(p.pcWidth.W))
  val armBSizeBytes = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val comparable = Output(Bool())
  val accepted = Output(Bool())
  val headerMismatch = Output(Bool())
  val hsizeMismatch = Output(Bool())
  val bsizeMismatch = Output(Bool())
}

class ReducedBfuBodyCutArmProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuBodyCutArmProbeIO(p))
  val arm = Module(new ReducedBfuBodyCutArm(p))

  arm.io.predictionValid := io.predictionValid
  arm.io.predictionHeaderPc := io.predictionHeaderPc
  arm.io.predictionHSizeBytes := io.predictionHSizeBytes
  arm.io.predictionBSizeBytes := io.predictionBSizeBytes
  arm.io.armValid := io.armValid
  arm.io.armHeaderPc := io.armHeaderPc
  arm.io.armHSizeBytes := io.armHSizeBytes
  arm.io.armBSizeBytes := io.armBSizeBytes

  io.geometryValid := arm.io.geometryValid
  io.headerPc := arm.io.headerPc
  io.hsizeBytes := arm.io.hsizeBytes
  io.bsizeBytes := arm.io.bsizeBytes
  io.comparable := arm.io.comparable
  io.accepted := arm.io.accepted
  io.headerMismatch := arm.io.headerMismatch
  io.hsizeMismatch := arm.io.hsizeMismatch
  io.bsizeMismatch := arm.io.bsizeMismatch
}

class ReducedBfuBodyCutArmSpec extends AnyFunSuite {
  test("reference accepts an external arm only when it matches the latched prediction") {
    val result = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = true,
        predictionHeaderPc = BigInt("4000630c", 16),
        predictionHSizeBytes = 0,
        predictionBSizeBytes = 0x20,
        armValid = true,
        armHeaderPc = BigInt("4000630c", 16),
        armHSizeBytes = 0,
        armBSizeBytes = 0x20))

    assert(result.geometryValid)
    assert(result.accepted)
    assert(result.comparable)
    assert(result.headerPc == BigInt("4000630c", 16))
    assert(result.hsizeBytes == 0)
    assert(result.bsizeBytes == 0x20)
    assert(!result.headerMismatch)
    assert(!result.hsizeMismatch)
    assert(!result.bsizeMismatch)
  }

  test("reference forwards prediction payload rather than candidate arm payload") {
    val result = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = true,
        predictionHeaderPc = 0x5000,
        predictionHSizeBytes = 6,
        predictionBSizeBytes = 0x12,
        armValid = false,
        armHeaderPc = 0x6000,
        armHSizeBytes = 8,
        armBSizeBytes = 0x20))

    assert(!result.geometryValid)
    assert(result.headerPc == 0x5000)
    assert(result.hsizeBytes == 6)
    assert(result.bsizeBytes == 0x12)
    assert(!result.comparable)
  }

  test("reference reports per-field mismatches only when both sides are comparable") {
    val headerMismatch = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = true,
        predictionHeaderPc = 0x1000,
        predictionHSizeBytes = 2,
        predictionBSizeBytes = 0x10,
        armValid = true,
        armHeaderPc = 0x2000,
        armHSizeBytes = 2,
        armBSizeBytes = 0x10))
    val hsizeMismatch = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = true,
        predictionHeaderPc = 0x1000,
        predictionHSizeBytes = 4,
        predictionBSizeBytes = 0x10,
        armValid = true,
        armHeaderPc = 0x1000,
        armHSizeBytes = 2,
        armBSizeBytes = 0x10))
    val bsizeMismatch = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = true,
        predictionHeaderPc = 0x1000,
        predictionHSizeBytes = 2,
        predictionBSizeBytes = 0x12,
        armValid = true,
        armHeaderPc = 0x1000,
        armHSizeBytes = 2,
        armBSizeBytes = 0x10))
    val idle = ReducedBfuBodyCutArmReference(
      ReducedBfuBodyCutArmReference.Inputs(
        predictionValid = false,
        predictionHeaderPc = 0x1000,
        predictionHSizeBytes = 2,
        predictionBSizeBytes = 0x12,
        armValid = true,
        armHeaderPc = 0x2000,
        armHSizeBytes = 4,
        armBSizeBytes = 0x10))

    assert(headerMismatch.headerMismatch)
    assert(!headerMismatch.hsizeMismatch)
    assert(!headerMismatch.bsizeMismatch)
    assert(hsizeMismatch.hsizeMismatch)
    assert(bsizeMismatch.bsizeMismatch)
    assert(!idle.comparable)
    assert(!idle.headerMismatch)
    assert(!idle.hsizeMismatch)
    assert(!idle.bsizeMismatch)
  }

  test("ReducedBfuBodyCutArm elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuBodyCutArmProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuBodyCutArmProbe"))
    assert(sv.contains("module ReducedBfuBodyCutArm"))
    assert(sv.contains("io_accepted"))
    assert(sv.contains("io_bsizeMismatch"))
  }
}
