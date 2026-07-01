package linxcore.frontend

import chisel3._
import chisel3.util.{PriorityEncoder, log2Ceil}
import linxcore.common.InterfaceParams

class ReducedBfuLocalBodyWindowIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val f4ScanValid = Input(Bool())
  val cutFire = Input(Bool())

  val predictionValid = Input(Bool())
  val predictionHeaderPc = Input(UInt(p.pcWidth.W))
  val predictionHSizeBytes = Input(UInt(p.pcWidth.W))
  val predictionBSizeBytes = Input(UInt(p.pcWidth.W))

  val f4Valid = Input(Bool())
  val f4Slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
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

class ReducedBfuLocalBodyWindow(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "ReducedBfuLocalBodyWindow assumes the current 4-slot F4 window")

  val io = IO(new ReducedBfuLocalBodyWindowIO(p))

  val activeReg = RegInit(false.B)
  val headerPcReg = RegInit(0.U(p.pcWidth.W))
  val hsizeReg = RegInit(0.U(p.pcWidth.W))
  val bsizeReg = RegInit(0.U(p.pcWidth.W))

  val headerMatchVec = Wire(Vec(p.decodeWidth, Bool()))
  for (slot <- 0 until p.decodeWidth) {
    val slotActive = io.f4Valid && io.f4Slots(slot).valid && io.f4ValidMask(slot)
    val meta = FrontendOpcodeDecodeTable.decode(p, io.f4Slots(slot).insnRaw, io.f4Slots(slot).lenBytes)
    headerMatchVec(slot) :=
      slotActive && meta.valid && meta.isBlockBoundary && io.f4Slots(slot).pc === io.predictionHeaderPc
  }

  val headerMatch = headerMatchVec.asUInt.orR
  val armFire = !io.flushValid && io.f4ScanValid && !activeReg && io.predictionValid && headerMatch
  val activeOrArming = !io.flushValid && (activeReg || armFire)

  io.geometryValid := activeOrArming
  io.headerPc := Mux(activeReg, headerPcReg, io.predictionHeaderPc)
  io.hsizeBytes := Mux(activeReg, hsizeReg, io.predictionHSizeBytes)
  io.bsizeBytes := Mux(activeReg, bsizeReg, io.predictionBSizeBytes)
  io.active := activeReg
  io.armFire := armFire
  io.releaseFire := !io.flushValid && activeReg && io.cutFire
  io.armSlot := PriorityEncoder(headerMatchVec)

  when(io.flushValid) {
    activeReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bsizeReg := 0.U
  }.elsewhen(io.cutFire) {
    activeReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bsizeReg := 0.U
  }.elsewhen(armFire) {
    activeReg := true.B
    headerPcReg := io.predictionHeaderPc
    hsizeReg := io.predictionHSizeBytes
    bsizeReg := io.predictionBSizeBytes
  }
}
