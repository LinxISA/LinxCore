package linxcore.frontend

import chisel3._
import linxcore.common._

class FrontendInstructionDecodeLaneIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val active = Input(Bool())
  val peId = Input(UInt(p.peIdWidth.W))
  val threadId = Input(UInt(p.threadIdWidth.W))
  val pc = Input(UInt(p.pcWidth.W))
  val insn = Input(UInt(p.insnWidth.W))
  val lenBytes = Input(UInt(p.lenWidth.W))
  val isLastInBlock = Input(Bool())
  val checkpointId = Input(UInt(p.checkpointWidth.W))
  val instructionUid = Input(UInt(p.uopUidWidth.W))
  val parentUid = Input(UInt(p.uopUidWidth.W))
  val fetchPacketUid = Input(UInt(p.uopUidWidth.W))
  val fetchSlot = Input(UInt(p.fetchSlotWidth.W))
  val prediction = Input(new BranchPredictionSidecar(p))

  val out = Output(new DecodedUop(p))
  val meta = Output(new FrontendOpcodeMeta(p))
  val invalidOpcode = Output(Bool())
  val blockBoundary = Output(Bool())
  val blockStop = Output(Bool())
  val isLoad = Output(Bool())
  val isStore = Output(Bool())
}

/** Shared full-decode owner for one fixed-64-bit D1 instruction container. */
class FrontendInstructionDecodeLane(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.opcodeWidth == 12, "frontend decode follows the pyCircuit 12-bit opcode catalog")
  require(p.insnWidth == 64, "frontend decode expects 64-bit instruction containers")

  val io = IO(new FrontendInstructionDecodeLaneIO(p))

  val rawMeta = FrontendOpcodeDecodeTable.decode(p, io.insn, io.lenBytes)
  val cSetretAlias =
    rawMeta.valid &&
      rawMeta.opcode === FrontendOpcodeDecodeTable.OP_C_MOVI.U(p.opcodeWidth.W) &&
      io.lenBytes === 2.U &&
      (io.insn(15, 0) & "hf83f".U) === "h5016".U
  val meta = Wire(new FrontendOpcodeMeta(p))
  meta := rawMeta
  when(cSetretAlias) {
    meta.opcode := FrontendOpcodeDecodeTable.OP_C_SETRET.U(p.opcodeWidth.W)
  }

  val operandDecode = Module(new FrontendOperandDecode(p))
  operandDecode.io.active := io.active && meta.valid
  operandDecode.io.meta := meta
  operandDecode.io.insn := io.insn

  val hasBoundaryTarget =
    FrontendDecodeStage.BoundaryTargetOpcodes.toSeq
      .map(op => meta.opcode === op.U(p.opcodeWidth.W))
      .reduce(_ || _)

  val out = Wire(new DecodedUop(p))
  out := 0.U.asTypeOf(out)
  out.valid := io.active && meta.valid
  out.peId := io.peId
  out.threadId := io.threadId
  out.pc := io.pc
  out.opcode := meta.opcode
  out.uopType := meta.dispatchTarget.asUInt
  out.src := operandDecode.io.src
  out.dst := operandDecode.io.dst
  out.pairFirstDst := operandDecode.io.pairFirstDst
  out.imm := operandDecode.io.imm
  out.immType := 0.U
  out.immValid := operandDecode.io.immValid
  out.isLoad := meta.isLoad
  out.isStore := meta.isStore
  out.isLoadStorePair := meta.isLoadStorePair
  out.isStorePcr := meta.isStorePcr
  out.cacheMaintainNoSplit := meta.cacheMaintainNoSplit
  out.sob := meta.isBlockBoundary
  out.eob := meta.isBlockStop
  out.isLastInBlock := meta.isBlockStop || io.isLastInBlock
  out.boundaryKind := meta.boundaryKind
  out.boundaryTarget :=
    Mux(hasBoundaryTarget && operandDecode.io.immValid, out.pc + operandDecode.io.imm, 0.U)
  out.predTaken := io.prediction.valid && io.prediction.taken
  out.prediction := io.prediction
  out.insnLen := meta.lenBytes
  out.insnRaw := io.insn
  out.checkpointId := io.checkpointId
  out.blockUid := 0.U
  out.blockBidValid := false.B
  out.blockBid := 0.U
  out.uid.uid := io.instructionUid
  out.uid.parentUid := io.parentUid
  out.uid.kind := meta.dispatchTarget.asUInt
  out.uid.fetchPacketUid := io.fetchPacketUid
  out.uid.fetchSlot := io.fetchSlot
  out.uid.replayDepth := 0.U
  out.uid.templateKind := 0.U

  io.out := out
  io.meta := meta
  io.invalidOpcode := io.active && !meta.valid
  io.blockBoundary := out.valid && meta.isBlockBoundary
  io.blockStop := out.valid && meta.isBlockStop
  io.isLoad := out.valid && meta.isLoad
  io.isStore := out.valid && meta.isStore
}
