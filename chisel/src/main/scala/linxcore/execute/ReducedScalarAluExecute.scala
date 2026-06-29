package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.{InterfaceParams, RenamedUop}
import linxcore.frontend.FrontendOpcodeDecodeTable

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
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)

  private def resultFor(op: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val out = Wire(UInt(p.immWidth.W))
    out := 0.U
    when(op === opcode(FrontendOpcodeDecodeTable.OP_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)) {
      out := srcData(0)
    }
    out
  }

  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def robIdValue(id: linxcore.rob.ROBID): UInt =
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
    row.src0.valid := valid && uop.src(0).valid
    row.src0.reg := fitReg(uop.src(0).archTag)
    row.src0.data := srcData(0)
    row.src1.valid := valid && uop.src(1).valid
    row.src1.reg := fitReg(uop.src(1).archTag)
    row.src1.data := srcData(1)
    row.dst.valid := valid && uop.dst(0).valid
    row.dst.reg := fitReg(uop.dst(0).archTag)
    row.dst.data := result
    row.wb.valid := valid && uop.dst(0).valid
    row.wb.reg := fitReg(uop.dst(0).archTag)
    row.wb.data := result
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
  val eResult = resultFor(eUop.opcode, eSrcData, eUop.imm)
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
  io.completeDstPhysValid := io.completeValid && w2Uop.dst(0).valid
  io.completeDstPhysTag := w2Uop.dst(0).physTag
  io.completeDstData := w2Result
  io.unsupported := w2Valid && !w2Supported
  io.unsupportedOpcode := w2Uop.opcode
}

object ReducedScalarAluExecute {
  private val Mask64 = (BigInt(1) << 64) - 1

  def referenceResult(opcode: Int, src0: BigInt, src1: BigInt, imm: BigInt): Option[BigInt] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_MOVI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_MOVR => Some(src0 & Mask64)
      case _ => None
    }
}
