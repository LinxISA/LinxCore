package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuBodyCutArmIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
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
  val headerMatch = Output(Bool())
  val hsizeMatch = Output(Bool())
  val bsizeMatch = Output(Bool())
  val headerMismatch = Output(Bool())
  val hsizeMismatch = Output(Bool())
  val bsizeMismatch = Output(Bool())
}

class ReducedBfuBodyCutArm(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuBodyCutArmIO(p))

  val comparable = io.predictionValid && io.armValid
  val headerMatch = io.predictionHeaderPc === io.armHeaderPc
  val hsizeMatch = io.predictionHSizeBytes === io.armHSizeBytes
  val bsizeMatch = io.predictionBSizeBytes === io.armBSizeBytes
  val accepted = comparable && headerMatch && hsizeMatch && bsizeMatch

  io.geometryValid := accepted
  io.headerPc := io.predictionHeaderPc
  io.hsizeBytes := io.predictionHSizeBytes
  io.bsizeBytes := io.predictionBSizeBytes

  io.comparable := comparable
  io.accepted := accepted
  io.headerMatch := comparable && headerMatch
  io.hsizeMatch := comparable && hsizeMatch
  io.bsizeMatch := comparable && bsizeMatch
  io.headerMismatch := comparable && !headerMatch
  io.hsizeMismatch := comparable && !hsizeMatch
  io.bsizeMismatch := comparable && !bsizeMatch
}
