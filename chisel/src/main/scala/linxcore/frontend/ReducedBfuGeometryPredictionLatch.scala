package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuGeometryPredictionLatchIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val learnValid = Input(Bool())
  val learnHeaderPc = Input(UInt(p.pcWidth.W))
  val learnHSizeBytes = Input(UInt(p.pcWidth.W))
  val learnBSizeBytes = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
}

class ReducedBfuGeometryPredictionLatch(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuGeometryPredictionLatchIO(p))

  val validReg = RegInit(false.B)
  val headerPcReg = RegInit(0.U(p.pcWidth.W))
  val hsizeReg = RegInit(0.U(p.pcWidth.W))
  val bsizeReg = RegInit(0.U(p.pcWidth.W))

  io.geometryValid := validReg
  io.headerPc := headerPcReg
  io.hsizeBytes := hsizeReg
  io.bsizeBytes := bsizeReg

  when(io.flushValid) {
    validReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bsizeReg := 0.U
  }.elsewhen(io.learnValid) {
    validReg := true.B
    headerPcReg := io.learnHeaderPc
    hsizeReg := io.learnHSizeBytes
    bsizeReg := io.learnBSizeBytes
  }
}
