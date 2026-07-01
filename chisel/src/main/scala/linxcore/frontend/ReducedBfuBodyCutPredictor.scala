package linxcore.frontend

import chisel3._
import chisel3.util.{PopCount, log2Ceil}
import linxcore.common.InterfaceParams

class ReducedBfuBodyCutPredictorIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
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
  val slotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
}

class ReducedBfuBodyCutPredictor(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "ReducedBfuBodyCutPredictor assumes the current 4-slot F4 window")

  val io = IO(new ReducedBfuBodyCutPredictorIO(p))

  // LinxCoreModel BFUUtils::NextBlockPC advances by one 16-bit bundle slot.
  val bodyBasePc = io.headerPc + 2.U
  val bodyEndPc = bodyBasePc + io.bsizeBytes
  val restartPc = io.headerPc + io.hsizeBytes
  val f4WindowEndPc = (io.f4Pc + F4DecodeWindow.WindowBytes.U)(p.pcWidth - 1, 0)

  val cutActive =
    io.geometryValid && io.f4Valid &&
      bodyEndPc > io.f4Pc && bodyEndPc <= f4WindowEndPc

  val cutMask = VecInit((0 until p.decodeWidth).map { slot =>
    !cutActive || (io.f4Slots(slot).valid && io.f4Slots(slot).pc < bodyEndPc)
  }).asUInt
  val validMask = io.f4ValidMask & cutMask

  io.cutActive := cutActive
  io.cutPc := bodyEndPc
  io.restartPc := restartPc
  io.advanceBytes := Mux(cutActive, (bodyEndPc - io.f4Pc)(3, 0), io.f4TotalLenBytes)
  io.validMask := validMask
  io.slotCount := PopCount(validMask)
}
