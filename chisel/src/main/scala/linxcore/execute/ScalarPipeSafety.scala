package linxcore.execute

import chisel3._

import linxcore.common.{DispatchTarget, RenamedUop, ScalarSpAccess}
import linxcore.frontend.FrontendOpcodeDecodeTable

object ScalarPipeSafety {
  private def opcode(value: Int, width: Int): UInt =
    value.U(width.W)

  private def isFixedScalarAluOpcode(op: UInt): Bool = {
    val width = op.getWidth
    op === opcode(FrontendOpcodeDecodeTable.OP_ADD, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUB, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_AND, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_OR, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XOR, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLL, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRL, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRA, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LUI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIS, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIU, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORIW, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADDI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_AND, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_OR, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SUB, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SLLI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SRLI, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_B, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_H, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_W, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_B, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_H, width) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_W, width)
  }

  def fixedScalarAlu(uop: RenamedUop): Bool = {
    val spAccess = ScalarSpAccess.classify(uop)
    isFixedScalarAluOpcode(uop.opcode) &&
      (uop.dispatchTarget === DispatchTarget.Alu) &&
      !uop.isLoad &&
      !uop.isStore &&
      !uop.storeSplitIntent &&
      !uop.isLoadStorePair &&
      !uop.isStorePcr &&
      !uop.cacheMaintainNoSplit &&
      !uop.pairFirstDst.valid &&
      !spAccess.valid &&
      !uop.fretStkContextValid &&
      !uop.fretStkConditionValid &&
      !uop.fretStkFallbackTargetValid
  }

}
