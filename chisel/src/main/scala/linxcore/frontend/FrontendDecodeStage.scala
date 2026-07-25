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
  val rdKind = UInt(2.W)
  val rs1Kind = UInt(2.W)
  val rs2Kind = UInt(2.W)
  val immKind = UInt(6.W)
  val isLoad = Bool()
  val isStore = Bool()
  val isLoadStorePair = Bool()
  val isStorePcr = Bool()
  val cacheMaintainNoSplit = Bool()
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

object FrontendDecodeStage {
  val BoundaryTargetOpcodes: Set[Int] = Set(
    FrontendOpcodeDecodeTable.OP_C_BSTART_COND,
    FrontendOpcodeDecodeTable.OP_C_BSTART_DIRECT,
    FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_COND,
    FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_DIRECT,
    FrontendOpcodeDecodeTable.OP_BSTART_STD_CALL,
    FrontendOpcodeDecodeTable.OP_BSTART_STD_COND,
    FrontendOpcodeDecodeTable.OP_BSTART_STD_DIRECT
  )
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
    val decode = Module(new FrontendInstructionDecodeLane(p))
    decode.io.active := slotActive(slot)
    decode.io.peId := io.d1.peId
    decode.io.threadId := io.d1.threadId
    decode.io.pc := io.slots(slot).pc
    decode.io.insn := io.slots(slot).insnRaw
    decode.io.lenBytes := io.slots(slot).lenBytes
    decode.io.isLastInBlock := io.slots(slot).isLastInBlock
    decode.io.checkpointId := io.d1.checkpointId
    decode.io.instructionUid := io.slots(slot).uopUid
    decode.io.parentUid := io.d1.pktUid
    decode.io.fetchPacketUid := io.d1.pktUid
    decode.io.fetchSlot := slot.U
    decode.io.prediction := 0.U.asTypeOf(decode.io.prediction)

    io.meta(slot) := decode.io.meta
    io.out(slot) := decode.io.out
    decodedValid(slot) := decode.io.out.valid
    invalidOpcode(slot) := decode.io.invalidOpcode
    blockBoundary(slot) := decode.io.blockBoundary
    blockStop(slot) := decode.io.blockStop
    loadVec(slot) := decode.io.isLoad
    storeVec(slot) := decode.io.isStore
  }

  io.outValidMask := decodedValid.asUInt
  io.invalidOpcodeMask := invalidOpcode.asUInt
  io.blockBoundaryMask := blockBoundary.asUInt
  io.blockStopMask := blockStop.asUInt
  io.loadMask := loadVec.asUInt
  io.storeMask := storeVec.asUInt
  io.uopCount := PopCount(decodedValid)
}
