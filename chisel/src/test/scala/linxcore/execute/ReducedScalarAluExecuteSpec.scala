package linxcore.execute

import chisel3._
import circt.stage.ChiselStage
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarAluExecuteSpec extends AnyFunSuite with ChiselSim {
  private def initExecute(dut: ReducedScalarAluExecute): Unit = {
    dut.io.inValid.poke(false.B)
    dut.io.in.poke(0.U.asTypeOf(dut.io.in))
    dut.io.srcData.foreach(_.poke(0.U))
    dut.io.loadLookupData.poke(0.U)
    dut.io.loadLookupWaitBlocked.poke(false.B)
    dut.io.loadLiqEnable.poke(false.B)
    dut.io.loadLiqAccepted.poke(false.B)
    dut.io.stackPointerData.poke(0.U)
    dut.io.flushValid.poke(false.B)
    dut.io.fretStkFallbackTargetValid.poke(false.B)
    dut.io.fretStkFallbackTarget.poke(0.U)
    dut.io.fretStkConditionValid.poke(false.B)
    dut.io.fretStkConditionTaken.poke(false.B)
    dut.io.completeReady.poke(true.B)
  }

  private def pokeAlu(
      dut: ReducedScalarAluExecute,
      opcode: Int,
      rid: Int,
      pc: BigInt,
      src0: BigInt,
      src1: BigInt,
      imm: BigInt = 0): Unit = {
    dut.io.in.poke(0.U.asTypeOf(dut.io.in))
    dut.io.in.valid.poke(true.B)
    dut.io.in.peId.poke((rid + 1).U)
    dut.io.in.threadId.poke((rid + 2).U)
    dut.io.in.pc.poke(pc.U)
    dut.io.in.opcode.poke(opcode.U)
    dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
    dut.io.in.insnLen.poke(4.U)
    dut.io.in.insnRaw.poke(0.U)
    dut.io.in.imm.poke(imm.U)
    dut.io.in.bid.valid.poke(true.B)
    dut.io.in.bid.value.poke(0.U)
    dut.io.in.gid.valid.poke(true.B)
    dut.io.in.gid.value.poke(0.U)
    dut.io.in.rid.valid.poke(true.B)
    dut.io.in.rid.value.poke(rid.U)
    dut.io.in.dst(0).valid.poke(true.B)
    dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
    dut.io.in.dst(0).archTag.poke((rid + 10).U)
    dut.io.in.dst(0).physTag.poke((rid + 20).U)
    dut.io.in.src(0).valid.poke(true.B)
    dut.io.in.src(0).operandClass.poke(OperandClass.P)
    dut.io.in.src(0).archTag.poke(2.U)
    dut.io.in.src(1).valid.poke(true.B)
    dut.io.in.src(1).operandClass.poke(OperandClass.P)
    dut.io.in.src(1).archTag.poke(3.U)
    dut.io.srcData(0).poke(src0.U)
    dut.io.srcData(1).poke(src1.U)
    dut.io.inValid.poke(true.B)
  }

  test("reference results match the model-derived reduced scalar ALU subset") {
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADD, 10, 32, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_ADD,
      insnRaw = BigInt("1f8e0785", 16),
      src0 = BigInt("40010070", 16),
      src1 = BigInt("ffffffffffffffff", 16),
      imm = 0).contains(BigInt("40010068", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADDW, 10, 32, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ADDW,
      BigInt("7fffffff", 16),
      1,
      0).contains(BigInt("ffffffff80000000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADDI, 7, 0, 0x7ff).contains(2054))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SUBI,
      src0 = 0,
      src1 = 0,
      imm = 24).contains(BigInt("ffffffffffffffe8", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ANDIW, 0, 0, 255).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ANDIW,
      BigInt("ffffffffffffffff", 16),
      0,
      BigInt("ffffffffffffffff", 16)).contains(BigInt("ffffffffffffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ADDTPC,
      pc = BigInt("400054f8", 16),
      src0 = 0,
      src1 = 0,
      imm = 0x9000).contains(BigInt("4000e000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SETRET,
      pc = BigInt("40005506", 16),
      src0 = 0,
      src1 = 0,
      imm = 4).contains(BigInt("4000550a", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_EQI,
      src0 = 0,
      src1 = 0,
      imm = 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_EQI,
      src0 = 1,
      src1 = 0,
      imm = 0).contains(0))
    assert(ReducedScalarAluExecute.referenceCsel(
      srcL = BigInt("1111222233334444", 16),
      srcR = BigInt("aaaabbbbccccdddd", 16),
      srcP = 1).equals(BigInt("1111222233334444", 16)))
    assert(ReducedScalarAluExecute.referenceCsel(
      srcL = BigInt("1111222233334444", 16),
      srcR = BigInt("aaaabbbbccccdddd", 16),
      srcP = 0).equals(BigInt("aaaabbbbccccdddd", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_FENTRY,
      src0 = BigInt("4000550a", 16),
      src1 = BigInt("4ffefff0", 16),
      imm = 576).contains(BigInt("4ffefdb0", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_LUI, 0, 0, 1).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SLL, 1, 32, 0).contains(BigInt("100000000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SLLI, 1, 0, 3).contains(8))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SRL, BigInt("100000000", 16), 32, 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SRA, BigInt("100000000", 16), 32, 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRA,
      BigInt("fffffffffffff000", 16),
      4,
      0).contains(BigInt("ffffffffffffff00", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SSRSET,
      BigInt("40010058", 16),
      0,
      0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_OR, BigInt("100000000", 16), 0x5a, 0).contains(BigInt("10000005a", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVI, 0, 0, 0x15).contains(0x15))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVR, 0x1234, 0, 0).contains(0x1234))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ADD, 0x1234, 0x100, 0).contains(0x1334))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_AND, 0xff0f, 0x33f0, 0).contains(0x3300))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SEXT_B,
      BigInt("80", 16),
      0,
      0).contains(BigInt("ffffffffffffff80", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SEXT_H,
      BigInt("8001", 16),
      0,
      0).contains(BigInt("ffffffffffff8001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SEXT_W,
      BigInt("80000001", 16),
      0,
      0).contains(BigInt("ffffffff80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ZEXT_B, BigInt("ff80", 16), 0, 0)
      .contains(0x80))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ZEXT_H, BigInt("ffff8001", 16), 0, 0)
      .contains(0x8001))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ZEXT_W, BigInt("ffffffff80000001", 16), 0, 0)
      .contains(BigInt("80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_LDI, 0x4ffefdb0L, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SDI, 0x4ffefdb0L, 0x40005679L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SWI, 0x4000ff90L, 2, 40).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LDI, 0x4fff0048L, 0, BigInt("fffffffffffffffe", 16)).contains(0))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LDI,
      src0 = 0x4000ecc8L,
      src1 = 0,
      imm = 0,
      loadData = 0x6ffffffbL).contains(0x6ffffffbL))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LBUI,
      src0 = 1,
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16),
      loadData = 0x1234L).contains(0x34))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LD_PCR,
      src0 = 0,
      src1 = 0,
      imm = 0xa728,
      loadData = 0x4000574cL).contains(0x4000574cL))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SB_PCR,
      src0 = 1,
      src1 = 0,
      imm = 0x9dfa).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SD_PCR,
      src0 = 0x4fff0008L,
      src1 = 0,
      imm = 0xa43a).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SH_PCR,
      src0 = 1,
      src1 = 0,
      imm = 0x9dfa).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SW_PCR,
      src0 = 1,
      src1 = 0,
      imm = 0x9dfa).contains(0))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LD_PCR,
      src0 = 0,
      src1 = 0,
      imm = 0x100,
      loadData = 0x1234L).contains(0x1234L))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SETC_EQ, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_C_SETC_EQ, 0, 0).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_C_SETC_EQ, 8, 256).contains(false))
    assert(ReducedScalarAluExecute.referenceBranchCondition(
      FrontendOpcodeDecodeTable.OP_C_SETC_EQ,
      src0 = 0x4fff0018L,
      src1 = 0,
      src0Valid = false,
      src1Valid = true).contains(true))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SETC_NE, 0x4fff0018L, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_C_SETC_NE, 8, 256).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_C_SETC_NE, 256, 256).contains(false))
    assert(ReducedScalarAluExecute.referenceBranchCondition(
      FrontendOpcodeDecodeTable.OP_C_SETC_NE,
      src0 = 0x4fff0018L,
      src1 = 0,
      src0Valid = false,
      src1Valid = true).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SETC_TGT, 0x4000574cL, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_C_SETC_TGT, 0x4000574cL, 0).contains(true))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_FRET_STK, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_FRET_STK,
      src0 = 0,
      src1 = 0,
      imm = 8,
      loadData = 0x40005cb0L).contains(0x40005cb0L))
    assert(ReducedScalarAluExecute.referenceFretStkNextPc(
      pc = BigInt("40005788", 16),
      lenBytes = 4,
      setcTarget = None,
      fallbackTarget = Some(BigInt("40005f2c", 16))) == BigInt("40005f2c", 16))
    assert(ReducedScalarAluExecute.referenceFretStkNextPc(
      pc = BigInt("4000570a", 16),
      lenBytes = 4,
      setcTarget = Some(BigInt("4000574c", 16)),
      fallbackTarget = Some(BigInt("40005f2c", 16))) == BigInt("4000574c", 16))
    assert(ReducedScalarAluExecute.referenceFretStkNextPc(
      pc = BigInt("40005788", 16),
      lenBytes = 4,
      setcTarget = None,
      fallbackTarget = None) == BigInt("4000578c", 16))
    assert(ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = false,
      conditionTaken = false))
    assert(ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = true,
      conditionTaken = false))
    assert(!ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = true,
      conditionTaken = true))
    assert(!ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = false,
      conditionValid = false,
      conditionTaken = false))
    assert(!ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = false,
      conditionTaken = false,
      targetPending = true))
    assert(!ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = true,
      conditionTaken = true,
      targetPending = true))
    assert(ReducedScalarAluExecute.referenceFretStkLoadsReturn(
      restoresRa = true,
      conditionValid = true,
      conditionTaken = false,
      targetPending = true))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SETC_LT, 0, 1, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(
      FrontendOpcodeDecodeTable.OP_SETC_LT,
      BigInt("ffffffffffffffff", 16),
      1).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(
      FrontendOpcodeDecodeTable.OP_SETC_LT,
      1,
      BigInt("ffffffffffffffff", 16)).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SETC_LTU, 36, 0x6ffffffbL, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTU, 36, 0x6ffffffbL).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTU, 0x6ffffffbL, 36).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SETC_LTUI, 0x130, 3, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTUI, 2, 3).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTUI, 0x130, 3).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SETC_TGT, 0x4000574cL, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_TGT, 0x4000574cL, 0).contains(true))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SBI, 0, 0x4fff0038L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SD, 0, 0x4fff0040L, 21).contains(0))
    assert(ReducedScalarAluExecute.referenceSdIndexedAddress(0x4fff0040L, 21).equals(BigInt("4fff00e8", 16)))
    assert(ReducedScalarAluExecute.referenceSdIndexedData(0x400055f2L).equals(BigInt("400055f2", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SDI, 0, 0x4fff0128L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_MULW, 6, 7, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MULW,
      BigInt("ffffffff", 16),
      2,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SUB, 10, 3, 0).contains(7))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SUB,
      0,
      BigInt("4ffefbf8", 16),
      0).contains(BigInt("ffffffffb0010408", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ANDI, BigInt("f0", 16), 0, 3)
      .contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_AND,
      BigInt("f0f0", 16),
      BigInt("0ff0", 16),
      0).contains(BigInt("00f0", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ANDI,
      BigInt("ffff", 16),
      0,
      BigInt("fffffffffffffff0", 16)).contains(BigInt("fff0", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ORI, 0, 0, 0x18)
      .contains(0x18))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_XORI,
      0,
      0,
      BigInt("ffffffffffffffff", 16)).contains(BigInt("ffffffffffffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SUB, 0x130, 0x18, 0)
      .contains(0x118))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_MUL, 6, 7, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MUL,
      BigInt("ffffffffffffffff", 16),
      2,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SWI, 0, 0x4ffefbf8L, 0)
      .contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LD, 1, 2, 3).isEmpty)
  }


  test("HL CMP immediate forms produce ISA boolean results in the ALU pipeline") {
    val cases = Seq(
      (FrontendOpcodeDecodeTable.OP_HL_CMP_EQI, BigInt("ffffffffffffffff", 16), BigInt("ffffffffffffffff", 16), BigInt(1)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_GEI, BigInt("ffffffffffffffff", 16), BigInt(0), BigInt(0)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_GEUI, BigInt("ffffffffffffffff", 16), BigInt(0), BigInt(1)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_LTI, BigInt("ffffffffffffffff", 16), BigInt(0), BigInt(1)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_LTUI, BigInt("ffffffffffffffff", 16), BigInt(1), BigInt(0)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_NEI, BigInt("100", 16), BigInt("101", 16), BigInt(1)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_ORI, BigInt(0), BigInt(0), BigInt(0)),
      (FrontendOpcodeDecodeTable.OP_HL_CMP_ORI, BigInt(0), BigInt("ffffffffffffffff", 16), BigInt(1)))
    val p = InterfaceParams(robEntries = 16, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadLookupWaitBlocked.poke(false.B)
      dut.io.loadLiqEnable.poke(false.B)
      dut.io.loadLiqAccepted.poke(false.B)
      dut.io.stackPointerData.poke(0.U)
      dut.io.flushValid.poke(false.B)
      dut.io.fretStkFallbackTargetValid.poke(false.B)
      dut.io.fretStkFallbackTarget.poke(0.U)
      dut.io.fretStkConditionValid.poke(false.B)
      dut.io.fretStkConditionTaken.poke(false.B)
      dut.io.completeReady.poke(true.B)

      for (((opcode, src0, imm, expected), index) <- cases.zipWithIndex) {
        val rid = index + 1
        dut.io.in.poke(0.U.asTypeOf(dut.io.in))
        dut.io.in.valid.poke(true.B)
        dut.io.in.peId.poke((rid + 1).U)
        dut.io.in.threadId.poke((rid + 2).U)
        dut.io.in.pc.poke((BigInt("40008000", 16) + (index * 4)).U)
        dut.io.in.opcode.poke(opcode.U)
        dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
        dut.io.in.insnLen.poke(4.U)
        dut.io.in.insnRaw.poke(0.U)
        dut.io.in.imm.poke(imm.U)
        dut.io.in.bid.valid.poke(true.B)
        dut.io.in.bid.value.poke(0.U)
        dut.io.in.gid.valid.poke(true.B)
        dut.io.in.gid.value.poke(0.U)
        dut.io.in.rid.valid.poke(true.B)
        dut.io.in.rid.value.poke(rid.U)
        dut.io.in.dst(0).valid.poke(true.B)
        dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
        dut.io.in.dst(0).archTag.poke((rid + 10).U)
        dut.io.in.dst(0).physTag.poke((rid + 20).U)
        dut.io.in.src(0).valid.poke(true.B)
        dut.io.in.src(0).operandClass.poke(OperandClass.P)
        dut.io.in.src(0).archTag.poke(2.U)
        dut.io.in.src(1).valid.poke(true.B)
        dut.io.in.src(1).operandClass.poke(OperandClass.P)
        dut.io.in.src(1).archTag.poke(3.U)
        dut.io.srcData(0).poke(src0.U)
        dut.io.srcData(1).poke(0.U)
        dut.io.inValid.poke(true.B)
        dut.io.inReady.expect(true.B)
        dut.io.accepted.expect(true.B)
        dut.clock.step()
        dut.io.inValid.poke(false.B)
        dut.clock.step(2)
        dut.io.completeValid.expect(true.B)
        dut.io.unsupported.expect(false.B)
        dut.io.completeRobValue.expect(rid.U)
        dut.io.completeDstData.expect(expected.U)
        dut.io.completeRow.wb.valid.expect(true.B)
        dut.io.completeRow.wb.data.expect(expected.U)
        dut.clock.step()
      }
    }
  }

  test("ReducedScalarAluExecute elaborates completion-row writeback payloads") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarAluExecute(p, trace))

    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_completeReady"))
    assert(sv.contains("io_completeFire"))
    assert(sv.contains("io_completeRobValue"))
    assert(sv.contains("io_completePeId"))
    assert(sv.contains("io_completeStid"))
    assert(sv.contains("io_completeTid"))
    assert(sv.contains("io_completeRow_wb_valid"))
    assert(sv.contains("io_completeRow_dst_data"))
    assert(sv.contains("io_completeLsId"))
    assert(sv.contains("io_loadLookupGid_value"))
    assert(sv.contains("io_loadLookupRid_value"))
    assert(sv.contains("io_releaseGid_value"))
    assert(sv.contains("io_completeDstPhysValid"))
    assert(sv.contains("io_completeDstPhysTag"))
    assert(sv.contains("io_completeDstData"))
    assert(sv.contains("io_stackPointerData"))
    assert(sv.contains("io_branchConditionValid"))
    assert(sv.contains("io_branchConditionTaken"))
    assert(sv.contains("io_fretStkConditionValid"))
    assert(sv.contains("io_fretStkConditionTaken"))
    assert(sv.contains("io_flushValid"))
    assert(sv.contains("io_fretStkFallbackTargetValid"))
    assert(sv.contains("io_fretStkFallbackTarget"))
    assert(sv.contains("io_releaseValid"))
    assert(sv.contains("io_releaseBid_value"))
    assert(sv.contains("io_releaseRid_value"))
    assert(sv.contains("io_releaseStid"))
    assert(sv.contains("io_liqReleaseValid"))
    assert(sv.contains("io_liqReleaseBid_value"))
    assert(sv.contains("io_liqReleaseRid_value"))
    assert(sv.contains("io_liqReleaseStid"))
    assert(sv.contains("io_loadLookupPc"))
    assert(sv.contains("io_loadLookupSize"))
    assert(sv.contains("io_loadLiqEligible"))
    assert(sv.contains("io_loadLookupBid_value"))
    assert(sv.contains("io_loadLookupLsId"))
    assert(sv.contains("io_loadLookupWaitBlocked"))
    assert(sv.contains("io_loadLiqEnable"))
    assert(sv.contains("io_loadLiqAccepted"))
    assert(sv.contains("io_loadWaitHold"))
    assert(sv.contains("io_unsupportedOpcode"))
  }

  test("W2 completion backpressure holds payloads stable and fires once when ready returns") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      initExecute(dut)

      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 1, BigInt("40002000", 16), 10, 1)
      dut.io.inReady.expect(true.B)
      dut.clock.step()
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADDI, 2, BigInt("40002004", 16), 10, 0, imm = 7)
      dut.io.inReady.expect(true.B)
      dut.clock.step()

      dut.io.completeReady.poke(false.B)
      dut.io.inValid.poke(false.B)
      dut.io.completeValid.expect(false.B)
      dut.io.inReady.expect(true.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()

      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.completeRow.pc.expect(BigInt("40002000", 16).U)
      dut.io.completeDstPhysValid.expect(false.B)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)

      dut.clock.step()
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.completeRow.pc.expect(BigInt("40002000", 16).U)
      dut.io.completeDstPhysValid.expect(false.B)
      dut.io.inReady.expect(false.B)

      dut.io.completeReady.poke(true.B)
      dut.io.completeFire.expect(true.B)
      dut.io.releaseValid.expect(true.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.completeDstPhysValid.expect(true.B)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(2.U)
      dut.io.completeDstData.expect(17.U)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }

  test("dense fixed ALU stream completes one per cycle after a W2 stall clears") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      initExecute(dut)

      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 1, BigInt("40003000", 16), 1, 10)
      dut.io.inReady.expect(true.B)
      dut.clock.step()
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 2, BigInt("40003004", 16), 2, 20)
      dut.io.inReady.expect(true.B)
      dut.clock.step()
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 3, BigInt("40003008", 16), 3, 30)
      dut.io.inReady.expect(true.B)
      dut.clock.step()

      dut.io.completeReady.poke(false.B)
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 4, BigInt("4000300c", 16), 4, 40)
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()

      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.inReady.expect(false.B)
      dut.clock.step()
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.inReady.expect(false.B)

      dut.io.completeReady.poke(true.B)
      dut.io.inReady.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(1.U)
      dut.io.completeDstData.expect(11.U)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(2.U)
      dut.io.completeDstData.expect(22.U)
      dut.clock.step()
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(3.U)
      dut.io.completeDstData.expect(33.U)
      dut.clock.step()
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(4.U)
      dut.io.completeDstData.expect(44.U)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
    }
  }
}
