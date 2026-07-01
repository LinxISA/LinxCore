package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Fill}
import linxcore.common._

class FrontendOperandDecodeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val active = Input(Bool())
  val meta = Input(new FrontendOpcodeMeta(p))
  val insn = Input(UInt(p.insnWidth.W))

  val src = Output(Vec(3, new DecodedOperand(p)))
  val dst = Output(Vec(1, new DecodedDestination(p)))
  val imm = Output(UInt(p.immWidth.W))
  val immValid = Output(Bool())
}

class FrontendOperandDecode(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.archRegWidth == 6, "FrontendOperandDecode emits the reg6 architectural namespace")
  require(p.immWidth == 64, "FrontendOperandDecode mirrors the pyCircuit 64-bit immediate field")
  require(p.insnWidth == 64, "FrontendOperandDecode expects 64-bit raw instruction payloads")

  val io = IO(new FrontendOperandDecodeIO(p))

  private def regInvalid: UInt = LinxCommonConstants.regInvalid(p.archRegWidth)

  private def archTag(value: UInt): UInt =
    value.pad(p.archRegWidth)(p.archRegWidth - 1, 0)

  private def fitImm(value: UInt): UInt = {
    val width = value.getWidth
    if (width == p.immWidth) {
      value
    } else if (width > p.immWidth) {
      value(p.immWidth - 1, 0)
    } else {
      value.pad(p.immWidth)
    }
  }

  private def sext(value: UInt, inWidth: Int): UInt = {
    require(inWidth > 0 && inWidth < p.immWidth)
    Cat(Fill(p.immWidth - inWidth, value(inWidth - 1)), value)
  }

  private def opcodeIs(values: Int*): Bool =
    values.map(value => io.meta.opcode === value.U(p.opcodeWidth.W)).reduce(_ || _)

  private def setSrc(idx: Int, tag: UInt): Unit = {
    io.src(idx) := FrontendRegAliasClassify.source(p, true.B, archTag(tag))
  }

  private def clearSrc(idx: Int): Unit = {
    io.src(idx).valid := false.B
    io.src(idx).operandClass := OperandClass.Invalid
    io.src(idx).archTag := regInvalid
    io.src(idx).relTag := regInvalid
  }

  private def setDst(tag: UInt): Unit = {
    io.dst(0) := FrontendRegAliasClassify.destination(p, true.B, archTag(tag))
  }

  private def setImm(value: UInt): Unit = {
    io.imm := fitImm(value)
    io.immValid := true.B
  }

  for (idx <- 0 until 3) {
    io.src(idx).valid := false.B
    io.src(idx).operandClass := OperandClass.Invalid
    io.src(idx).archTag := regInvalid
    io.src(idx).relTag := regInvalid
  }
  io.dst(0).valid := false.B
  io.dst(0).kind := DestinationKind.None
  io.dst(0).archTag := regInvalid
  io.dst(0).relTag := regInvalid
  io.imm := 0.U
  io.immValid := false.B

  val insn16 = io.insn(15, 0)
  val insn32 = io.insn(31, 0)
  val main32 = io.insn(47, 16)
  val pfx16 = io.insn(15, 0)

  val rd16 = archTag(insn16(15, 11))
  val rs16 = archTag(insn16(10, 6))
  val rd32 = archTag(insn32(11, 7))
  val rs1_32 = archTag(insn32(19, 15))
  val rs2_32 = archTag(insn32(24, 20))
  val srcp32 = archTag(insn32(31, 27))
  val rdHl = archTag(main32(11, 7))
  val rs1Hl = archTag(main32(19, 15))
  val rs2Hl = archTag(main32(24, 20))

  val genericRd = Mux(io.meta.lenBytes === 2.U, rd16, Mux(io.meta.lenBytes === 6.U, rdHl, rd32))
  val genericRs1 = Mux(io.meta.lenBytes === 2.U, rs16, Mux(io.meta.lenBytes === 6.U, rs1Hl, rs1_32))
  val genericRs2 = Mux(io.meta.lenBytes === 2.U, rd16, Mux(io.meta.lenBytes === 6.U, rs2Hl, rs2_32))

  val imm12U = insn32(31, 20).pad(p.immWidth)
  val imm12S = sext(insn32(31, 20), 12)
  val imm20S = sext(insn32(31, 12), 20)
  val imm20U = insn32(31, 12).pad(p.immWidth)
  val imm20Shifted = fitImm(imm20S << 12)
  val setretImm = fitImm(imm20U << 1)
  val simm12Split = sext(Cat(insn32(11, 7), insn32(31, 25)), 12)
  val simm17Raw = sext(insn32(31, 15), 17)
  val simm17Off = fitImm(simm17Raw << 1)
  val simm25 = sext(insn32(31, 7), 25)
  val simm5_11 = sext(insn16(15, 11), 5)
  val simm5_6 = sext(insn16(10, 6), 5)
  val cBranchOff = fitImm(sext(insn16(15, 4), 12) << 1)
  val uimm5 = insn16(10, 6).pad(p.immWidth)
  val shamt20_25 = insn32(25, 20).pad(p.immWidth)
  val macroImm = fitImm(insn32(11, 7).pad(p.immWidth) << 10) |
    fitImm(insn32(31, 25).pad(p.immWidth) << 3)
  val hlLuiImm = sext(Cat(pfx16(15, 4), main32(31, 12)), 32)
  val hlBstartOff = sext(Cat(pfx16(15, 4), io.insn(47, 31), 0.U(1.W)), 30)
  val hlPcrOff = sext(Cat(pfx16(15, 4), io.insn(47, 31)), 29)
  val hlStorePcrOff = sext(Cat(pfx16(15, 4), io.insn(27, 23), io.insn(47, 36)), 29)

  private def isLoadPcrOpcode: Bool =
    opcodeIs(
      FrontendOpcodeDecodeTable.OP_LB_PCR,
      FrontendOpcodeDecodeTable.OP_LBU_PCR,
      FrontendOpcodeDecodeTable.OP_LD_PCR,
      FrontendOpcodeDecodeTable.OP_LH_PCR,
      FrontendOpcodeDecodeTable.OP_LHU_PCR,
      FrontendOpcodeDecodeTable.OP_LW_PCR,
      FrontendOpcodeDecodeTable.OP_LWU_PCR)

  private def isHlLoadPcrOpcode: Bool =
    opcodeIs(
      FrontendOpcodeDecodeTable.OP_HL_LB_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LBU_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LD_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LH_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LHU_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LW_PCR,
      FrontendOpcodeDecodeTable.OP_HL_LWU_PCR)

  private def isSetcImmediateOpcode: Bool =
    opcodeIs(
      FrontendOpcodeDecodeTable.OP_SETC_ANDI,
      FrontendOpcodeDecodeTable.OP_SETC_EQI,
      FrontendOpcodeDecodeTable.OP_SETC_GEI,
      FrontendOpcodeDecodeTable.OP_SETC_GEUI,
      FrontendOpcodeDecodeTable.OP_SETC_LTI,
      FrontendOpcodeDecodeTable.OP_SETC_LTUI,
      FrontendOpcodeDecodeTable.OP_SETC_NEI,
      FrontendOpcodeDecodeTable.OP_SETC_ORI)

  when(io.active) {
    when(io.meta.rdKind === FrontendOpcodeDecodeTable.OperandREG.U) {
      setDst(genericRd)
    }
    when(io.meta.rs1Kind === FrontendOpcodeDecodeTable.OperandREG.U) {
      setSrc(0, genericRs1)
    }
    when(io.meta.rs2Kind === FrontendOpcodeDecodeTable.OperandREG.U) {
      setSrc(1, genericRs2)
    }

    when(opcodeIs(
      FrontendOpcodeDecodeTable.OP_C_ADD,
      FrontendOpcodeDecodeTable.OP_C_ADDI,
      FrontendOpcodeDecodeTable.OP_C_AND,
      FrontendOpcodeDecodeTable.OP_C_OR,
      FrontendOpcodeDecodeTable.OP_C_SUB,
      FrontendOpcodeDecodeTable.OP_C_LDI,
      FrontendOpcodeDecodeTable.OP_C_LWI,
      FrontendOpcodeDecodeTable.OP_C_SEXT_B,
      FrontendOpcodeDecodeTable.OP_C_SEXT_H,
      FrontendOpcodeDecodeTable.OP_C_SEXT_W,
      FrontendOpcodeDecodeTable.OP_C_ZEXT_B,
      FrontendOpcodeDecodeTable.OP_C_ZEXT_H,
      FrontendOpcodeDecodeTable.OP_C_ZEXT_W,
      FrontendOpcodeDecodeTable.OP_C_CMP_EQI,
      FrontendOpcodeDecodeTable.OP_C_CMP_NEI)) {
      setDst(31.U)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_C_CMP_EQI, FrontendOpcodeDecodeTable.OP_C_CMP_NEI)) {
      setSrc(0, 24.U)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_C_SDI, FrontendOpcodeDecodeTable.OP_C_SWI)) {
      setSrc(1, 24.U)
    }

    when(opcodeIs(
      FrontendOpcodeDecodeTable.OP_FENTRY,
      FrontendOpcodeDecodeTable.OP_FEXIT,
      FrontendOpcodeDecodeTable.OP_FRET_RA,
      FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      setSrc(0, rs1_32)
      setSrc(1, rs2_32)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      setSrc(0, rs1_32)
      clearSrc(1)
      setDst(1.U)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      clearSrc(0)
      clearSrc(1)
    }

    when(isSetcImmediateOpcode) {
      io.dst(0).valid := false.B
      io.dst(0).kind := DestinationKind.None
      io.dst(0).archTag := regInvalid
      io.dst(0).relTag := regInvalid
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_BTEXT)) {
      setSrc(0, rs1_32)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_SETRET)) {
      setDst(10.U)
    }

    when(opcodeIs(FrontendOpcodeDecodeTable.OP_BLOAD, FrontendOpcodeDecodeTable.OP_BSTORE)) {
      setDst(rd32)
      setSrc(0, rs1_32)
      setSrc(1, rs2_32)
    }

    when(opcodeIs(
      FrontendOpcodeDecodeTable.OP_MADD,
      FrontendOpcodeDecodeTable.OP_MADDW,
      FrontendOpcodeDecodeTable.OP_CSEL,
      FrontendOpcodeDecodeTable.OP_BIOR,
      FrontendOpcodeDecodeTable.OP_BLOAD,
      FrontendOpcodeDecodeTable.OP_BSTORE,
      FrontendOpcodeDecodeTable.OP_SB,
      FrontendOpcodeDecodeTable.OP_SH,
      FrontendOpcodeDecodeTable.OP_SW,
      FrontendOpcodeDecodeTable.OP_SD)) {
      setSrc(2, srcp32)
    }

    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmUIMM12.U) {
      setImm(imm12U)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM12_20_S12.U) {
      setImm(imm12S)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM12_7_S5_25_7.U) {
      setImm(simm12Split)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM17.U) {
      when(isLoadPcrOpcode) {
        setImm(simm17Raw)
      }.otherwise {
        setImm(simm17Off)
      }
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM25.U) {
      setImm(simm25)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM5_11_S5.U) {
      setImm(simm5_11)
    }
    when(opcodeIs(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      setImm(fitImm(simm5_11 << 3))
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM5_6_S5.U) {
      setImm(simm5_6)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmUIMM5.U) {
      setImm(uimm5)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmFENTRY_UIMM_HI.U) {
      setImm(macroImm)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmIMM32.U) {
      setImm(hlLuiImm)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM_4_S12_31_17.U) {
      when(isHlLoadPcrOpcode) {
        setImm(hlPcrOff)
      }.otherwise {
        setImm(hlBstartOff)
      }
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM_4_S12_23_5_36_12.U) {
      setImm(hlStorePcrOff)
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmIMM20.U) {
      when(opcodeIs(FrontendOpcodeDecodeTable.OP_SETRET)) {
        setImm(setretImm)
      }.otherwise {
        setImm(imm20Shifted)
      }
    }
    when(io.meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM12.U) {
      when(io.meta.lenBytes === 2.U) {
        setImm(cBranchOff)
      }.otherwise {
        setImm(imm12S)
      }
    }
    when(opcodeIs(
      FrontendOpcodeDecodeTable.OP_SLLI,
      FrontendOpcodeDecodeTable.OP_SRLI,
      FrontendOpcodeDecodeTable.OP_SRAI)) {
      setImm(shamt20_25)
    }
    when(opcodeIs(FrontendOpcodeDecodeTable.OP_C_SETRET)) {
      setDst(10.U)
      setImm(fitImm(uimm5 << 1))
    }
  }
}
