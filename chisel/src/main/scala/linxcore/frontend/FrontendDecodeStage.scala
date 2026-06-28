package linxcore.frontend

import chisel3._
import chisel3.util.{log2Ceil, PopCount}
import linxcore.common._

class FrontendOpcodeMeta(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val opcode = UInt(p.opcodeWidth.W)
  val lenBytes = UInt(p.lenWidth.W)
  val majorCategory = UInt(4.W)
  val dispatchTarget = DispatchTarget()
  val boundaryKind = BoundaryKind()
  val isLoad = Bool()
  val isStore = Bool()
  val isBlockBoundary = Bool()
  val isBlockStop = Bool()
}

class FrontendDecodeStageIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val d1 = Input(new FrontendDecodePacket(p))
  val slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Input(UInt(p.decodeWidth.W))
  val flushValid = Input(Bool())

  val out = Output(Vec(p.decodeWidth, new DecodedUop(p)))
  val meta = Output(Vec(p.decodeWidth, new FrontendOpcodeMeta(p)))
  val outValidMask = Output(UInt(p.decodeWidth.W))
  val invalidOpcodeMask = Output(UInt(p.decodeWidth.W))
  val blockBoundaryMask = Output(UInt(p.decodeWidth.W))
  val blockStopMask = Output(UInt(p.decodeWidth.W))
  val loadMask = Output(UInt(p.decodeWidth.W))
  val storeMask = Output(UInt(p.decodeWidth.W))
  val uopCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
}

class FrontendDecodeStage(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "FrontendDecodeStage currently consumes the 4-slot F4 window")
  require(p.opcodeWidth == 12, "FrontendDecodeStage follows the pyCircuit 12-bit opcode catalog")
  require(p.insnWidth == 64, "FrontendDecodeStage expects 64-bit raw instruction payloads")

  val io = IO(new FrontendDecodeStageIO(p))

  val active = io.d1.valid && !io.flushValid
  val slotActive = Wire(Vec(p.decodeWidth, Bool()))
  val decodedValid = Wire(Vec(p.decodeWidth, Bool()))
  val invalidOpcode = Wire(Vec(p.decodeWidth, Bool()))
  val blockBoundary = Wire(Vec(p.decodeWidth, Bool()))
  val blockStop = Wire(Vec(p.decodeWidth, Bool()))
  val loadVec = Wire(Vec(p.decodeWidth, Bool()))
  val storeVec = Wire(Vec(p.decodeWidth, Bool()))

  for (slot <- 0 until p.decodeWidth) {
    slotActive(slot) := active && io.slots(slot).valid && io.validMask(slot)
    val meta = FrontendOpcodeDecodeTable.decode(p, io.slots(slot).insnRaw, io.slots(slot).lenBytes)
    val out = Wire(new DecodedUop(p))
    out := 0.U.asTypeOf(out)

    out.valid := slotActive(slot) && meta.valid
    out.threadId := 0.U
    out.pc := io.slots(slot).pc
    out.opcode := meta.opcode
    out.uopType := meta.dispatchTarget.asUInt
    out.imm := 0.U
    out.immType := 0.U
    out.immValid := false.B
    out.sob := meta.isBlockBoundary
    out.eob := meta.isBlockStop
    out.boundaryKind := meta.boundaryKind
    out.boundaryTarget := 0.U
    out.predTaken := false.B
    out.insnLen := meta.lenBytes
    out.insnRaw := io.slots(slot).insnRaw
    out.checkpointId := io.d1.checkpointId
    out.blockUid := 0.U
    out.blockBidValid := false.B
    out.blockBid := 0.U
    out.uid.uid := io.slots(slot).uopUid
    out.uid.parentUid := io.d1.pktUid
    out.uid.kind := meta.dispatchTarget.asUInt
    out.uid.fetchPacketUid := io.d1.pktUid
    out.uid.fetchSlot := slot.U
    out.uid.replayDepth := 0.U
    out.uid.templateKind := 0.U

    for (src <- 0 until 3) {
      out.src(src).valid := false.B
      out.src(src).operandClass := OperandClass.Invalid
      out.src(src).archTag := LinxCommonConstants.regInvalid(p.archRegWidth)
      out.src(src).relTag := LinxCommonConstants.regInvalid(p.archRegWidth)
    }
    out.dst(0).valid := false.B
    out.dst(0).kind := DestinationKind.None
    out.dst(0).archTag := LinxCommonConstants.regInvalid(p.archRegWidth)
    out.dst(0).relTag := LinxCommonConstants.regInvalid(p.archRegWidth)

    io.meta(slot) := meta
    io.out(slot) := out
    decodedValid(slot) := out.valid
    invalidOpcode(slot) := slotActive(slot) && !meta.valid
    blockBoundary(slot) := out.valid && meta.isBlockBoundary
    blockStop(slot) := out.valid && meta.isBlockStop
    loadVec(slot) := out.valid && meta.isLoad
    storeVec(slot) := out.valid && meta.isStore
  }

  io.outValidMask := decodedValid.asUInt
  io.invalidOpcodeMask := invalidOpcode.asUInt
  io.blockBoundaryMask := blockBoundary.asUInt
  io.blockStopMask := blockStop.asUInt
  io.loadMask := loadVec.asUInt
  io.storeMask := storeVec.asUInt
  io.uopCount := PopCount(decodedValid)
}
