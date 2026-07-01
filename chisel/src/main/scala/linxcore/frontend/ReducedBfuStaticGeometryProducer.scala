package linxcore.frontend

import chisel3._
import chisel3.util.{PriorityEncoder, log2Ceil}
import linxcore.common.InterfaceParams

class ReducedBfuStaticGeometryProducerIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val f4UpdateFire = Input(Bool())
  val f4Valid = Input(Bool())
  val f4Slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val f4ValidMask = Input(UInt(p.decodeWidth.W))
  val resolvedBodyEndValid = Input(Bool())
  val resolvedHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Input(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))

  val headerActive = Output(Bool())
  val learnedFire = Output(Bool())
  val eventLearnedFire = Output(Bool())
  val resolvedLearnedFire = Output(Bool())
  val eventValid = Output(Bool())
  val eventSlot = Output(UInt(log2Ceil(p.decodeWidth).W))
  val eventPc = Output(UInt(p.pcWidth.W))
  val eventIsBoundary = Output(Bool())
  val eventIsStop = Output(Bool())
}

class ReducedBfuStaticGeometryProducer(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "ReducedBfuStaticGeometryProducer assumes the current 4-slot F4 window")

  val io = IO(new ReducedBfuStaticGeometryProducerIO(p))

  val headerActiveReg = RegInit(false.B)
  val headerPcReg = RegInit(0.U(p.pcWidth.W))
  val hsizeReg = RegInit(0.U(p.pcWidth.W))

  val boundaryVec = Wire(Vec(p.decodeWidth, Bool()))
  val stopVec = Wire(Vec(p.decodeWidth, Bool()))
  val eventVec = Wire(Vec(p.decodeWidth, Bool()))

  for (slot <- 0 until p.decodeWidth) {
    val slotActive = io.f4Valid && io.f4Slots(slot).valid && io.f4ValidMask(slot)
    val meta = FrontendOpcodeDecodeTable.decode(p, io.f4Slots(slot).insnRaw, io.f4Slots(slot).lenBytes)
    boundaryVec(slot) := slotActive && meta.valid && meta.isBlockBoundary
    stopVec(slot) := slotActive && meta.valid && meta.isBlockStop
    eventVec(slot) := boundaryVec(slot) || stopVec(slot)
  }

  val eventValid = eventVec.asUInt.orR
  val eventSlot = PriorityEncoder(eventVec)
  val eventPc = io.f4Slots(eventSlot).pc
  val eventLen = io.f4Slots(eventSlot).lenBytes
  val eventIsBoundary = boundaryVec(eventSlot)
  val eventIsStop = stopVec(eventSlot)
  val eventBodyEndPc = Mux(eventIsStop, (eventPc + eventLen)(p.pcWidth - 1, 0), eventPc)
  val bodyBasePc = (headerPcReg + 2.U)(p.pcWidth - 1, 0)
  val learnedBsize = Mux(eventBodyEndPc > bodyBasePc, (eventBodyEndPc - bodyBasePc)(p.pcWidth - 1, 0), 0.U)
  val learnAllowed = !io.flushValid
  val eventLearnedFire = learnAllowed && headerActiveReg && eventValid
  val resolvedLearnedFire = learnAllowed && headerActiveReg && io.resolvedBodyEndValid && io.resolvedHeaderPc === headerPcReg
  val resolvedBsize = Mux(io.resolvedBodyEndPc > bodyBasePc, (io.resolvedBodyEndPc - bodyBasePc)(p.pcWidth - 1, 0), 0.U)
  val learnedFire = resolvedLearnedFire || eventLearnedFire

  io.geometryValid := learnedFire
  io.headerPc := headerPcReg
  io.hsizeBytes := Mux(resolvedLearnedFire, io.resolvedHSizeBytes, hsizeReg)
  io.bsizeBytes := Mux(resolvedLearnedFire, resolvedBsize, learnedBsize)
  io.headerActive := headerActiveReg
  io.learnedFire := learnedFire
  io.eventLearnedFire := eventLearnedFire
  io.resolvedLearnedFire := resolvedLearnedFire
  io.eventValid := eventValid
  io.eventSlot := eventSlot
  io.eventPc := Mux(eventValid, eventPc, 0.U)
  io.eventIsBoundary := eventValid && eventIsBoundary
  io.eventIsStop := eventValid && eventIsStop

  when(io.flushValid) {
    headerActiveReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
  }.elsewhen(io.f4UpdateFire && eventValid) {
    when(eventIsBoundary) {
      headerActiveReg := true.B
      headerPcReg := eventPc
      hsizeReg := 0.U
    }.otherwise {
      headerActiveReg := false.B
      headerPcReg := 0.U
      hsizeReg := 0.U
    }
  }
}
