package linxcore.common

import chisel3._

import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID

class ScalarSpAccess(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val read = Bool()
  val write = Bool()
}

class ScalarSpTransaction(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val access = new ScalarSpAccess(p)
  val stid = UInt(p.threadIdWidth.W)
  val bid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val epoch = UInt(p.blockEpochWidth.W)
}

object ScalarSpAccess {
  private def opcode(p: InterfaceParams, value: Int): UInt =
    value.U(p.opcodeWidth.W)

  private def frameMacroReadWrite(p: InterfaceParams, op: UInt): Bool =
    op === opcode(p, FrontendOpcodeDecodeTable.OP_FENTRY) ||
      op === opcode(p, FrontendOpcodeDecodeTable.OP_FEXIT) ||
      op === opcode(p, FrontendOpcodeDecodeTable.OP_FRET_RA) ||
      op === opcode(p, FrontendOpcodeDecodeTable.OP_FRET_STK)

  private def decodedSourceReadsSp(src: DecodedOperand): Bool =
    src.valid && (src.operandClass === OperandClass.P) && (src.archTag === 1.U)

  private def renamedSourceReadsSp(src: RenamedOperand): Bool =
    src.valid && (src.operandClass === OperandClass.P) && (src.archTag === 1.U)

  private def decodedDestinationWritesSp(dst: DecodedDestination): Bool =
    dst.valid && (dst.kind === DestinationKind.Gpr) && (dst.archTag === 1.U)

  private def renamedDestinationWritesSp(dst: RenamedDestination): Bool =
    dst.valid && (dst.kind === DestinationKind.Gpr) && (dst.archTag === 1.U)

  def classify(uop: DecodedUop): ScalarSpAccess = {
    val out = Wire(new ScalarSpAccess(uop.p))
    val macroSp = frameMacroReadWrite(uop.p, uop.opcode)
    val read = macroSp || VecInit(uop.src.map(decodedSourceReadsSp)).asUInt.orR
    val write =
      macroSp ||
        decodedDestinationWritesSp(uop.dst(0)) ||
        decodedDestinationWritesSp(uop.pairFirstDst)
    out.valid := read || write
    out.read := read
    out.write := write
    out
  }

  def classify(uop: RenamedUop): ScalarSpAccess = {
    val out = Wire(new ScalarSpAccess(uop.p))
    val macroSp = frameMacroReadWrite(uop.p, uop.opcode)
    val read = macroSp || VecInit(uop.src.map(renamedSourceReadsSp)).asUInt.orR
    val write =
      macroSp ||
        renamedDestinationWritesSp(uop.dst(0)) ||
        renamedDestinationWritesSp(uop.pairFirstDst)
    out.valid := read || write
    out.read := read
    out.write := write
    out
  }
}
