package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass, RenamedUop}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID

class ReducedScalarAluExecuteIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val srcData = Input(Vec(3, UInt(p.immWidth.W)))

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val completeDstPhysValid = Output(Bool())
  val completeDstPhysTag = Output(UInt(p.physRegWidth.W))
  val completeDstData = Output(UInt(p.immWidth.W))
  val branchConditionValid = Output(Bool())
  val branchConditionTaken = Output(Bool())

  val releaseValid = Output(Bool())
  val releaseBid = Output(new ROBID(p.robEntries))
  val releaseRid = Output(new ROBID(p.robEntries))
  val releaseStid = Output(UInt(p.threadIdWidth.W))

  val accepted = Output(Bool())
  val busy = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedOpcode = Output(UInt(p.opcodeWidth.W))
}

class ReducedScalarAluExecute(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(traceParams.robValueWidth >= p.robIndexWidth, "trace ROB value must hold execute completion index")
  require(traceParams.regWidth >= p.archRegWidth, "trace register field must hold architectural register tags")
  require(traceParams.dataWidth == p.immWidth, "reduced ALU trace data follows InterfaceParams immediate/data width")

  val io = IO(new ReducedScalarAluExecuteIO(p, traceParams))

  private def opcode(value: Int): UInt =
    value.U(p.opcodeWidth.W)

  private def isSupported(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_ADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRA) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD)

  private def resultFor(op: UInt, pc: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val out = Wire(UInt(p.immWidth.W))
    out := 0.U
    when(op === opcode(FrontendOpcodeDecodeTable.OP_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC)) {
      out := (pc & "hffff_ffff_ffff_f000".U(p.immWidth.W)) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)) {
      out := srcData(0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET)) {
      out := pc + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      out := srcData(1) - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLL)) {
      out := (srcData(0) << srcData(1)(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLI)) {
      out := (srcData(0) << imm(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRL)) {
      out := srcData(0) >> srcData(1)(5, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRA)) {
      out := (srcData(0).asSInt >> srcData(1)(5, 0)).asUInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_OR)) {
      out := srcData(0) | srcData(1)
    }
    out
  }

  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def robIdValue(id: ROBID): UInt =
    id.value.pad(32)(31, 0)

  private def completionRow(uop: RenamedUop, srcData: Vec[UInt], result: UInt, valid: Bool): CommitTraceRow = {
    val row = Wire(new CommitTraceRow(traceParams))
    row := 0.U.asTypeOf(row)
    row.valid := valid
    row.identity.bid := robIdValue(uop.bid)
    row.identity.gid := robIdValue(uop.gid)
    row.identity.rid := robIdValue(uop.rid)
    row.rob.valid := uop.rid.valid
    row.rob.wrap := uop.rid.wrap
    row.rob.value := uop.rid.value
    row.blockBidValid := uop.blockBidValid
    row.blockBid := uop.blockBid
    row.pc := uop.pc
    row.insn := uop.insnRaw
    row.len := uop.insnLen
    row.nextPc := uop.pc + uop.insnLen
    row.src0.valid := valid && uop.src(0).valid && (uop.src(0).operandClass === OperandClass.P)
    row.src0.reg := fitReg(uop.src(0).archTag)
    row.src0.data := srcData(0)
    row.src1.valid := valid && uop.src(1).valid && (uop.src(1).operandClass === OperandClass.P)
    row.src1.reg := fitReg(uop.src(1).archTag)
    row.src1.data := srcData(1)
    row.dst.valid := valid && uop.dst(0).valid
    row.dst.reg := fitReg(uop.dst(0).archTag)
    row.dst.data := result
    row.wb.valid := valid && uop.dst(0).valid
    row.wb.reg := fitReg(uop.dst(0).archTag)
    row.wb.data := result
    when(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      row.src0.valid := false.B
      row.src1.valid := false.B
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := result + uop.imm - 8.U
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := srcData(0) + uop.imm
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := srcData(0) + ((uop.imm << 3)(p.immWidth - 1, 0))
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := srcData(1) + ((uop.imm << 3)(p.immWidth - 1, 0))
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }
    row
  }

  val eValid = RegInit(false.B)
  val eUop = Reg(new RenamedUop(p))
  val eSrcData = Reg(Vec(3, UInt(p.immWidth.W)))

  val w1Valid = RegInit(false.B)
  val w1Uop = Reg(new RenamedUop(p))
  val w1SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w1Result = Reg(UInt(p.immWidth.W))
  val w1Supported = Reg(Bool())

  val w2Valid = RegInit(false.B)
  val w2Uop = Reg(new RenamedUop(p))
  val w2SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w2Result = Reg(UInt(p.immWidth.W))
  val w2Supported = Reg(Bool())

  val accept = io.inValid && io.inReady
  val eResult = resultFor(eUop.opcode, eUop.pc, eSrcData, eUop.imm)
  val eSupported = eValid && isSupported(eUop.opcode)

  w2Valid := w1Valid
  w2Uop := w1Uop
  w2SrcData := w1SrcData
  w2Result := w1Result
  w2Supported := w1Supported

  w1Valid := eValid
  w1Uop := eUop
  w1SrcData := eSrcData
  w1Result := eResult
  w1Supported := eSupported

  eValid := accept
  when(accept) {
    eUop := io.in
    eSrcData := io.srcData
  }

  io.inReady := !eValid
  io.accepted := accept
  io.busy := eValid || w1Valid || w2Valid
  io.completeValid := w2Valid && w2Supported
  io.completeRobValue := w2Uop.rid.value
  io.completeRow := completionRow(w2Uop, w2SrcData, w2Result, io.completeValid)
  io.completeDstPhysValid := io.completeValid && w2Uop.dst(0).valid && (w2Uop.dst(0).kind === DestinationKind.Gpr)
  io.completeDstPhysTag := w2Uop.dst(0).physTag
  io.completeDstData := w2Result
  val branchSrc0 = Mux(w2Uop.src(0).valid, w2SrcData(0), 0.U)
  val branchSrc1 = Mux(w2Uop.src(1).valid, w2SrcData(1), 0.U)
  val branchIsSetcEq = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)
  val branchIsSetcNe = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)
  io.branchConditionValid := io.completeValid && (branchIsSetcEq || branchIsSetcNe)
  io.branchConditionTaken := Mux(branchIsSetcEq, branchSrc0 === branchSrc1, branchSrc0 =/= branchSrc1)
  io.releaseValid := w2Valid
  io.releaseBid := w2Uop.bid
  io.releaseRid := w2Uop.rid
  io.releaseStid := w2Uop.threadId
  io.unsupported := w2Valid && !w2Supported
  io.unsupportedOpcode := w2Uop.opcode
}

object ReducedScalarAluExecute {
  private val Mask64 = (BigInt(1) << 64) - 1
  private val SignBit64 = BigInt(1) << 63

  private def signed64(value: BigInt): BigInt = {
    val masked = value & Mask64
    if ((masked & SignBit64) != 0) masked - (BigInt(1) << 64) else masked
  }

  def referenceResult(opcode: Int, src0: BigInt, src1: BigInt, imm: BigInt): Option[BigInt] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDTPC => None
      case FrontendOpcodeDecodeTable.OP_C_MOVI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_MOVR => Some(src0 & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SETRET => None
      case FrontendOpcodeDecodeTable.OP_C_LDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE => Some(0)
      case FrontendOpcodeDecodeTable.OP_FENTRY => Some((src1 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LUI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_LDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SLL => Some((src0 << ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SLLI => Some((src0 << ((imm & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRL => Some((src0 & Mask64) >> ((src1 & 0x3f).toInt))
      case FrontendOpcodeDecodeTable.OP_SRA => Some((signed64(src0) >> ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_OR => Some((src0 | src1) & Mask64)
      case _ => None
    }

  def referenceResult(opcode: Int, pc: BigInt, src0: BigInt, src1: BigInt, imm: BigInt): Option[BigInt] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADDTPC => Some(((pc & ~BigInt(0xfff)) + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SETRET => Some((pc + imm) & Mask64)
      case _ => referenceResult(opcode, src0, src1, imm)
    }

  def referenceBranchCondition(
      opcode: Int,
      src0: BigInt,
      src1: BigInt,
      src0Valid: Boolean = true,
      src1Valid: Boolean = true): Option[Boolean] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) src1 & Mask64 else BigInt(0)
        Some(lhs == rhs)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) src1 & Mask64 else BigInt(0)
        Some(lhs != rhs)
      case _ => None
    }
}
