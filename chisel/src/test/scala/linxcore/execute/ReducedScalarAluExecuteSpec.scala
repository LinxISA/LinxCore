package linxcore.execute

import chisel3._
import circt.stage.ChiselStage
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.{DestinationKind, DispatchTarget, InterfaceParams, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarAluExecuteSpec extends AnyFunSuite with ChiselSim {
  private val unsupportedHlDualDstOps = Seq(
    FrontendOpcodeDecodeTable.OP_HL_MUL,
    FrontendOpcodeDecodeTable.OP_HL_MULU,
    FrontendOpcodeDecodeTable.OP_HL_DIV,
    FrontendOpcodeDecodeTable.OP_HL_DIVU,
    FrontendOpcodeDecodeTable.OP_HL_DIVW,
    FrontendOpcodeDecodeTable.OP_HL_DIVUW,
    FrontendOpcodeDecodeTable.OP_HL_REM,
    FrontendOpcodeDecodeTable.OP_HL_REMU,
    FrontendOpcodeDecodeTable.OP_HL_REMW,
    FrontendOpcodeDecodeTable.OP_HL_REMUW)

  private def initExecute(dut: ReducedScalarAluExecute): Unit = {
    dut.io.inValid.poke(false.B)
    dut.io.in.poke(0.U.asTypeOf(dut.io.in))
    dut.io.srcData.foreach(_.poke(0.U))
    dut.io.loadLookupData.poke(0.U)
    dut.io.loadPairFirstLookupData.poke(0.U)
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
      imm: BigInt = 0,
      dispatchTarget: DispatchTarget.Type = DispatchTarget.Alu): Unit = {
    dut.io.in.poke(0.U.asTypeOf(dut.io.in))
    dut.io.in.valid.poke(true.B)
    dut.io.in.peId.poke((rid + 1).U)
    dut.io.in.threadId.poke((rid + 2).U)
    dut.io.in.pc.poke(pc.U)
    dut.io.in.opcode.poke(opcode.U)
    dut.io.in.dispatchTarget.poke(dispatchTarget)
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

  test("E1 load lookup owns the read port ahead of an older W2 byte replay") {
    assert(ReducedScalarAluExecute.referenceLoadLookupOwner(e1LoadValid = true, w2ByteReplayValid = true) == "e1")
    assert(ReducedScalarAluExecute.referenceLoadLookupOwner(e1LoadValid = false, w2ByteReplayValid = true) == "w2-replay")
    assert(ReducedScalarAluExecute.referenceLoadLookupOwner(e1LoadValid = false, w2ByteReplayValid = false) == "none")
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
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SUBIW,
      src0 = BigInt("80000001", 16),
      src1 = 0,
      imm = 2).contains(BigInt("000000007fffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SUBIW,
      src0 = 0,
      src1 = 0,
      imm = 1).contains(BigInt("ffffffffffffffff", 16)))
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
      FrontendOpcodeDecodeTable.OP_SETRET,
      pc = BigInt("138cc", 16),
      src0 = 0,
      src1 = 0,
      imm = 6).contains(BigInt("138d2", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SETRET,
      pc = BigInt("10012", 16),
      src0 = 0,
      src1 = 0,
      imm = BigInt("fffffffffffffffa", 16)).contains(BigInt("1000c", 16)))
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
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_LTU,
      src0 = 22,
      src1 = BigInt("7fffffff", 16),
      imm = 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_LTU,
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = 1,
      imm = 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_LT,
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = 1,
      imm = 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_GEU,
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = 1,
      imm = 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_ANDI,
      src0 = BigInt("100", 16),
      src1 = 0,
      imm = BigInt("180", 16)).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_CMP_ORI,
      src0 = 0,
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
    assert(ReducedScalarAluExecute.referenceFentryFirstSaveAddress(BigInt("387f0", 16)) == BigInt("387e8", 16))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_LUI,
      0,
      0,
      BigInt("12345000", 16)).contains(BigInt("12345000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_LUI,
      0,
      0,
      BigInt("ffffffff80000000", 16)).contains(BigInt("ffffffff80000000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_LUI, 0, 0, 1).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_LIS,
      src0 = 0,
      src1 = 0,
      imm = BigInt("ffffffff80000001", 16)).contains(BigInt("ffffffff80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_LIU,
      src0 = 0,
      src1 = 0,
      imm = BigInt("0000000080000001", 16)).contains(BigInt("0000000080000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_ADDI,
      src0 = BigInt("fffffffffff00000", 16),
      src1 = 0,
      imm = BigInt("ffffff", 16)).contains(BigInt("0000000000efffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SUBI,
      src0 = 0,
      src1 = 0,
      imm = BigInt("ffffff", 16)).contains(BigInt("ffffffffff000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_ADDIW,
      src0 = BigInt("7fffffff", 16),
      src1 = 0,
      imm = 1).contains(BigInt("ffffffff80000000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_ANDIW,
      src0 = BigInt("00000000800000ff", 16),
      src1 = 0,
      imm = BigInt("ffffffffff800000", 16)).contains(BigInt("ffffffff80000000", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_ORI,
      src0 = BigInt("0000000012340000", 16),
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16)).contains(BigInt("ffffffffffffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_ORIW,
      src0 = BigInt("0000000070000000", 16),
      src1 = 0,
      imm = BigInt("fffffffff0000001", 16)).contains(BigInt("fffffffff0000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_XORI,
      src0 = BigInt("aaaaaaaaaaaaaaaa", 16),
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16)).contains(BigInt("5555555555555555", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_XORIW,
      src0 = BigInt("000000007fffffff", 16),
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16)).contains(BigInt("ffffffff80000000", 16)))
    // ISA pseudocode and QEMU's linx_hl_swi_writeback agree: PO stores at
    // the old base, then writes base + (simm << 2) to the destination.
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SWI_PO,
      src0 = 8,
      src1 = 0x166a0,
      imm = 1).contains(0x166a4))
    assert(ReducedScalarAluExecute.referenceHlSwiPoAddress(0x166a0) == 0x166a0)
    assert(ReducedScalarAluExecute.referenceHlSwiPoData(BigInt("deadbeefcafef00d", 16)) == BigInt("cafef00d", 16))
    // Sail, QEMU, LLVM and LinxCoreModel agree: HL.SDI.PO stores at the old
    // base, then writes base + (simm17 << 3) to RegDst.
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_SDI_PO,
      src0 = BigInt("0123456789abcdef", 16),
      src1 = BigInt("00000000000166a0", 16),
      imm = BigInt("ffffffffffffffff", 16)).contains(BigInt("0000000000016698", 16)))
    assert(ReducedScalarAluExecute.referenceHlSdiPoAddress(BigInt("166a0", 16)) == BigInt("166a0", 16))
    assert(ReducedScalarAluExecute.referenceHlSdiPoData(BigInt("0123456789abcdef", 16)) == BigInt("0123456789abcdef", 16))
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
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ORW,
      BigInt("80000000", 16),
      1,
      0).contains(BigInt("ffffffff80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_ORIW,
      BigInt("80000000", 16),
      0,
      1).contains(BigInt("ffffffff80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVI, 0, 0, 0x15).contains(0x15))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVR, 0x1234, 0, 0).contains(0x1234))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SWIP, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SWIP_U, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SDIP, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SDIP_U, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LDIP,
      src0 = BigInt("07fffe48", 16),
      src1 = 0,
      imm = 3,
      loadData = 45).contains(45))
    assert(ReducedScalarAluExecute.referenceHlImmediateLoadPairSecondAddress(
      FrontendOpcodeDecodeTable.OP_HL_LDIP,
      base = BigInt("07fffe48", 16),
      imm = 3) == BigInt("07fffe68", 16))
    assert(ReducedScalarAluExecute.referenceHlImmediateLoadPairSecondAddress(
      FrontendOpcodeDecodeTable.OP_HL_LDIP_U,
      base = BigInt("1000", 16),
      imm = BigInt("ffffffffffffffff", 16)) == BigInt("1007", 16))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LBI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16),
      loadData = BigInt("80", 16)).contains(BigInt("ffffffffffffff80", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LBUI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = 7,
      loadData = BigInt("ff", 16)).contains(BigInt("ff", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LHI_U,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = BigInt("fffffffffffffffe", 16),
      loadData = BigInt("8000", 16)).contains(BigInt("ffffffffffff8000", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LWUI_U,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = BigInt("fffffffffffffffc", 16),
      loadData = BigInt("ffffffff80000001", 16)).contains(BigInt("80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LDI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = 3,
      loadData = BigInt("0123456789abcdef", 16)).contains(BigInt("0123456789abcdef", 16)))
    assert(ReducedScalarAluExecute.referenceHlLongOffsetLoadAddress(
      FrontendOpcodeDecodeTable.OP_HL_LWI,
      srcL = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffd", 16)) == BigInt("40010074", 16))
    assert(ReducedScalarAluExecute.referenceHlLongOffsetLoadAddress(
      FrontendOpcodeDecodeTable.OP_HL_LWI_U,
      srcL = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffd", 16)) == BigInt("4001007d", 16))
    assert(ReducedScalarAluExecute.referenceHlLongOffsetStoreAddress(
      FrontendOpcodeDecodeTable.OP_HL_SWI,
      srcR = BigInt("40010080", 16),
      imm = 5) == BigInt("40010094", 16))
    assert(ReducedScalarAluExecute.referenceHlLongOffsetStoreAddress(
      FrontendOpcodeDecodeTable.OP_HL_SWI_U,
      srcR = BigInt("40010080", 16),
      imm = 5) == BigInt("40010085", 16))
    assert(ReducedScalarAluExecute.referenceHlLongOffsetStoreAddress(
      FrontendOpcodeDecodeTable.OP_HL_SDI,
      srcR = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffe", 16)) == BigInt("40010070", 16))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SBI, 0x12, 0x40010080L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_HL_SDI_U, 0x12, 0x40010080L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ADD, 0x1234, 0x100, 0).contains(0x1334))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SLLI,
      src0 = BigInt("4000000000000001", 16),
      src1 = 0,
      imm = 1).contains(BigInt("8000000000000002", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_SRLI,
      src0 = BigInt("8000000000000002", 16),
      src1 = 0,
      imm = 1).contains(BigInt("4000000000000001", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ADDI, 0x1234, 0, 0).contains(0x1234))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ADDI, 0x1234, 0, 0x10).contains(0x1244))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_ADDI,
      0x1234,
      0,
      BigInt("ffffffffffffffff", 16)).contains(0x1233))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_ADDI,
      BigInt("ffffffffffffffff", 16),
      0,
      1).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_ADDI,
      src0 = 31,
      src1 = 0,
      imm = 1).contains(32))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADDI, 0x1234, 0, 0x10).contains(0x1244))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LUI, 0, 0, 0x1244).contains(0x1244))
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
    assert(ReducedScalarAluExecute.referenceLwiAddress(
      srcL = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffd", 16)) == BigInt("40010074", 16))
    assert(ReducedScalarAluExecute.referenceLwiUAddress(
      srcL = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffd", 16)) == BigInt("4001007d", 16))
    assert(ReducedScalarAluExecute.referenceLwiAddress(
      srcL = BigInt("40010080", 16),
      imm = 5) == BigInt("40010094", 16))
    assert(ReducedScalarAluExecute.referenceLwiUAddress(
      srcL = BigInt("40010080", 16),
      imm = 5) == BigInt("40010085", 16))
    assert(ReducedScalarAluExecute.referenceLhiAddress(
      srcL = BigInt("40010080", 16),
      imm = 0) == BigInt("40010080", 16))
    assert(ReducedScalarAluExecute.referenceLhiAddress(
      srcL = BigInt("40010080", 16),
      imm = 7) == BigInt("4001008e", 16))
    assert(ReducedScalarAluExecute.referenceLhiAddress(
      srcL = BigInt("40010080", 16),
      imm = BigInt("fffffffffffffffc", 16)) == BigInt("40010078", 16))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LHI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = 0,
      loadData = BigInt("7fff", 16)).contains(BigInt("7fff", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LHI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = 0,
      loadData = BigInt("8000", 16)).contains(BigInt("ffffffffffff8000", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LHUI,
      src0 = BigInt("40010080", 16),
      src1 = 0,
      imm = 0,
      loadData = BigInt("ffff", 16)).contains(BigInt("ffff", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LWI,
      src0 = 0x40010080L,
      src1 = 0,
      imm = BigInt("fffffffffffffffd", 16),
      loadData = BigInt("80010020", 16)).contains(BigInt("ffffffff80010020", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LWI_U,
      src0 = 0x40010080L,
      src1 = 0,
      imm = BigInt("fffffffffffffffd", 16),
      loadData = BigInt("00010020", 16)).contains(BigInt("00010020", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LBUI,
      src0 = 1,
      src1 = 0,
      imm = BigInt("ffffffffffffffff", 16),
      loadData = 0x1234L).contains(0x34))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LBI,
      src0 = 1,
      src1 = 0,
      imm = 0,
      loadData = 0x80).contains(BigInt("ffffffffffffff80", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LB,
      src0 = 1,
      src1 = 0,
      imm = 0,
      loadData = 0x7f).contains(0x7f))
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
    assert(ReducedScalarAluExecute.referenceBranchCondition(
      FrontendOpcodeDecodeTable.OP_C_SETC_TGT,
      src0 = 0,
      src1 = 0,
      src0Valid = false).contains(false))
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
    assert(ReducedScalarAluExecute.referenceFretStkSnapshotLoadsReturn(
      restoresRa = true,
      contextValid = true,
      snapshotConditionValid = true,
      snapshotConditionTaken = false,
      snapshotTargetPending = true,
      liveConditionValid = false,
      liveConditionTaken = false,
      liveTargetPending = true))
    assert(!ReducedScalarAluExecute.referenceFretStkSnapshotLoadsReturn(
      restoresRa = true,
      contextValid = false,
      snapshotConditionValid = true,
      snapshotConditionTaken = false,
      snapshotTargetPending = true,
      liveConditionValid = false,
      liveConditionTaken = false,
      liveTargetPending = true))
    assert(!ReducedScalarAluExecute.referenceFretStkDecodeContextValid(
      isFretStk = true,
      activeBlockConditional = true,
      conditionValid = false))
    assert(ReducedScalarAluExecute.referenceFretStkSnapshotLoadsReturn(
      restoresRa = true,
      contextValid = ReducedScalarAluExecute.referenceFretStkDecodeContextValid(
        isFretStk = true,
        activeBlockConditional = true,
        conditionValid = false),
      snapshotConditionValid = false,
      snapshotConditionTaken = false,
      snapshotTargetPending = true,
      liveConditionValid = true,
      liveConditionTaken = false,
      liveTargetPending = true))
    assert(ReducedScalarAluExecute.referenceFretStkDecodeContextValid(
      isFretStk = true,
      activeBlockConditional = false,
      conditionValid = false))
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
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_TGT, 0, 0).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SBI, 0, 0x4fff0038L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SD, 0, 0x4fff0040L, 21).contains(0))
    assert(ReducedScalarAluExecute.referenceSdIndexedAddress(0x4fff0040L, 21).equals(BigInt("4fff00e8", 16)))
    assert(ReducedScalarAluExecute.referenceSdIndexedData(0x400055f2L).equals(BigInt("400055f2", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SDI, 0, 0x4fff0128L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SHI, 0xdeadbeefL, 0x4fff0100L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceShiAddress(srcR = BigInt("4fff0100", 16), imm = 0).equals(BigInt("4fff0100", 16)))
    assert(ReducedScalarAluExecute.referenceShiAddress(srcR = BigInt("4fff0100", 16), imm = 7).equals(BigInt("4fff010e", 16)))
    assert(ReducedScalarAluExecute.referenceShiAddress(
      srcR = BigInt("4fff0100", 16),
      imm = BigInt("fffffffffffffffc", 16)).equals(BigInt("4fff00f8", 16)))
    assert(ReducedScalarAluExecute.referenceShiData(BigInt("deadbeefcafebabe", 16)).equals(BigInt("deadbeefcafebabe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_MULW, 6, 7, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MULW,
      BigInt("ffffffff", 16),
      2,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_DIVU, 100, 7, 0).contains(14))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_DIV,
      BigInt("ffffffffffffff9c", 16),
      7,
      0).contains(BigInt("fffffffffffffff2", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_DIVUW,
      BigInt("fffffffe", 16),
      1,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_DIV, 123, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_REMU, 100, 7, 0).contains(2))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_REM,
      BigInt("ffffffffffffff9c", 16),
      7,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_REMUW,
      BigInt("fffffffe", 16),
      BigInt("ffffffff", 16),
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_REM, 123, 0, 0).contains(123))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_SUB, 10, 3, 0).contains(7))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SUBW,
      BigInt("80000000", 16),
      1,
      0).contains(BigInt("000000007fffffff", 16)))
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
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_ANDW,
      insnRaw = BigInt("06000000", 16),
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = BigInt("80000001", 16),
      imm = 0).contains(BigInt("ffffffff80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_ANDW,
      insnRaw = BigInt("08000000", 16),
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = BigInt("80000001", 16),
      imm = 0).contains(2))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_ANDW,
      insnRaw = BigInt("0c000000", 16),
      src0 = BigInt("ffffffffffffffff", 16),
      src1 = BigInt("7ffffffe", 16),
      imm = 0).contains(2))
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
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_XOR,
      BigInt("ff00", 16),
      BigInt("0ff0", 16),
      0).contains(BigInt("f0f0", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_XORW,
      BigInt("80000000", 16),
      1,
      0).contains(BigInt("ffffffff80000001", 16)))
    val wrapFieldInsn = (BigInt(60) << 26) | (BigInt(7) << 20)
    val wrapFieldValue = (BigInt(1) << 60) | BigInt(1)
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BXU,
      wrapFieldInsn,
      wrapFieldValue,
      0,
      0).contains(0x11))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BXS,
      wrapFieldInsn,
      BigInt(1) << 3,
      0,
      0).contains(BigInt("ffffffffffffff80", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BCNT,
      wrapFieldInsn,
      wrapFieldValue,
      0,
      0).contains(2))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_CLZ,
      wrapFieldInsn,
      BigInt(1) << 60,
      0,
      0).contains(7))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_CTZ,
      wrapFieldInsn,
      BigInt(1) << 1,
      0,
      0).contains(5))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BIC,
      wrapFieldInsn,
      BigInt("ffffffffffffffff", 16),
      0,
      0).contains(BigInt("0ffffffffffffff0", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BIS,
      wrapFieldInsn,
      0,
      0,
      0).contains(BigInt("f00000000000000f", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MAX,
      BigInt("ffffffffffffffff", 16),
      1,
      0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MIN,
      BigInt("ffffffffffffffff", 16),
      1,
      0).contains(BigInt("ffffffffffffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MAXU,
      BigInt("ffffffffffffffff", 16),
      1,
      0).contains(BigInt("ffffffffffffffff", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MINU,
      BigInt("ffffffffffffffff", 16),
      1,
      0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MULU,
      BigInt("ffffffffffffffff", 16),
      2,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_MULUW,
      BigInt("ffffffff", 16),
      2,
      0).contains(BigInt("fffffffffffffffe", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_XORIW,
      BigInt("80000000", 16),
      0,
      1).contains(BigInt("ffffffff80000001", 16)))
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
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LD, 1, 2, 3, BigInt("deadbeefcafef00d", 16))
      .contains(BigInt("deadbeefcafef00d", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LWUI, 1, 0, 3, BigInt("ffffffff80000001", 16))
      .contains(BigInt("80000001", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LR_W, 0, 0, 0, BigInt("80000000", 16))
      .contains(BigInt("ffffffff80000000", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_HL_LB_PCR, 0, 0, 0, BigInt("80", 16))
      .contains(BigInt("ffffffffffffff80", 16)))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_ANDI, BigInt("100", 16), 0, BigInt("180", 16)).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_EQI, BigInt("ffffffffffffffff", 16), 0, BigInt("ffffffffffffffff", 16)).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_GEI, BigInt("ffffffffffffffff", 16), 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_GEUI, BigInt("ffffffffffffffff", 16), 0, 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_LTI, BigInt("ffffffffffffffff", 16), 0, 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_LTUI, BigInt("ffffffffffffffff", 16), 0, 1).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_NEI, BigInt("100", 16), 0, BigInt("101", 16)).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_ORI, 0, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_HL_CMP_ORI, 0, 0, BigInt("ffffffffffffffff", 16)).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_CMP_EQI, 0, 0, 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_C_OR, BigInt("100", 16), BigInt("11", 16), 0).contains(BigInt("111", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BXU, BigInt("01fc1167", 16), BigInt("ffffffff89abcdef", 16), 0, 0)
      .contains(BigInt("89abcdef", 16)))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_BCNT, BigInt("03f26fe7", 16), BigInt("f0f0", 16), 0, 0).contains(8))
    assert(ReducedScalarAluExecute.referenceResultWithInsn(
      FrontendOpcodeDecodeTable.OP_CLZ, BigInt("01f1df67", 16), BigInt("00008000", 16), 0, 0).contains(16))
    assert(ReducedScalarAluExecute.referenceMadd(5, 6, 7) == 47)
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LHI_U, 1, 2, 3).isEmpty)
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LHUI_U, 1, 2, 3).isEmpty)
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SHI_U, 1, 2, 3).isEmpty)
    unsupportedHlDualDstOps.foreach { opcode =>
      assert(ReducedScalarAluExecute.referenceResult(opcode, 7, 3, 0).isEmpty)
    }
  }

  test("ReducedScalarAluExecute elaborates completion-row writeback payloads") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarAluExecute(p, trace))

    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("io_completeValid"))
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
    assert(sv.contains("fretStkContextValid"))
    assert(sv.contains("fretStkFallbackTargetValid"))
    assert(sv.contains("io_flushValid"))
    assert(sv.contains("io_fretStkFallbackTargetValid"))
    assert(sv.contains("io_fretStkFallbackTarget"))
    assert(sv.contains("io_releaseValid"))
    assert(sv.contains("io_releaseBid_value"))
    assert(sv.contains("io_releaseRid_value"))
    assert(sv.contains("io_releaseStid"))
    assert(sv.contains("io_earlyReleaseValid"))
    assert(sv.contains("io_earlyReleaseBid_value"))
    assert(sv.contains("io_earlyReleaseGid_value"))
    assert(sv.contains("io_earlyReleaseRid_value"))
    assert(sv.contains("io_earlyReleaseStid"))
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
    assert(sv.contains("12'h16B"))
    assert(sv.contains("12'h16E"))
    assert(sv.contains("4'h2"))
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
      initExecute(dut)
      for (((opcode, src0, imm, expected), index) <- cases.zipWithIndex) {
        val rid = index + 1
        pokeAlu(dut, opcode, rid, BigInt("40008000", 16) + (index * 4), src0, 0, imm = imm)
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

  test("safe fixed scalar ALU inputs accept every cycle and complete in identity order") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadPairFirstLookupData.poke(0.U)
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

      def pokeAlu(opcode: Int, rid: Int, pc: BigInt, src0: BigInt, src1: BigInt, imm: BigInt = 0): Unit = {
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

      def expectComplete(rid: Int, pc: BigInt, data: BigInt): Unit = {
        dut.io.completeValid.expect(true.B)
        dut.io.releaseValid.expect(false.B)
        dut.io.completeRobValue.expect(rid.U)
        dut.io.completePeId.expect((rid + 1).U)
        dut.io.completeStid.expect((rid + 2).U)
        dut.io.completeTid.expect((rid + 2).U)
        dut.io.completeRow.pc.expect(pc.U)
        dut.io.completeRow.identity.rid.expect(rid.U)
        dut.io.completeDstData.expect(data.U)
      }

      pokeAlu(FrontendOpcodeDecodeTable.OP_ADD, 1, BigInt("40001000", 16), 5, 6)
      dut.io.inReady.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.earlyReleaseValid.expect(false.B)
      dut.clock.step()

      pokeAlu(FrontendOpcodeDecodeTable.OP_ADDI, 2, BigInt("40001004", 16), 20, 0, imm = 2)
      dut.io.inReady.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.earlyReleaseValid.expect(true.B)
      dut.io.earlyReleaseRid.value.expect(1.U)
      dut.io.earlyReleaseStid.expect(3.U)
      dut.clock.step()

      pokeAlu(FrontendOpcodeDecodeTable.OP_ADD, 3, BigInt("40001008", 16), 30, 3)
      dut.io.inReady.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.earlyReleaseValid.expect(true.B)
      dut.io.earlyReleaseRid.value.expect(2.U)
      dut.io.earlyReleaseStid.expect(4.U)
      dut.clock.step()
      dut.io.inValid.poke(false.B)

      dut.io.earlyReleaseValid.expect(true.B)
      dut.io.earlyReleaseRid.value.expect(3.U)
      dut.io.earlyReleaseStid.expect(5.U)
      expectComplete(1, BigInt("40001000", 16), 11)
      dut.clock.step()
      dut.io.earlyReleaseValid.expect(false.B)
      expectComplete(2, BigInt("40001004", 16), 22)
      dut.clock.step()
      expectComplete(3, BigInt("40001008", 16), 33)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
    }
  }

  test("W2 completion backpressure holds payloads stable and fires once when ready returns") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      initExecute(dut)

      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 1, BigInt("40002000", 16), 5, 6)
      dut.clock.step()
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADDI, 2, BigInt("40002004", 16), 10, 0, imm = 7)
      dut.clock.step()
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 3, BigInt("40002008", 16), 30, 4)
      dut.clock.step()

      dut.io.completeReady.poke(false.B)
      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 4, BigInt("4000200c", 16), 40, 5)
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(false.B)
      dut.io.completeAccepted.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.earlyReleaseValid.expect(true.B)
      dut.io.earlyReleaseRid.value.expect(3.U)
      dut.io.completeRobValue.expect(1.U)
      dut.io.completeRow.pc.expect(BigInt("40002000", 16).U)
      dut.io.completeDstData.expect(11.U)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()
      for (_ <- 0 until 2) {
        dut.io.completeValid.expect(true.B)
        dut.io.completeFire.expect(false.B)
        dut.io.completeAccepted.expect(false.B)
        dut.io.releaseValid.expect(false.B)
        dut.io.earlyReleaseValid.expect(false.B)
        dut.io.completeRobValue.expect(1.U)
        dut.io.completeRow.pc.expect(BigInt("40002000", 16).U)
        dut.io.completeDstData.expect(11.U)
        dut.io.inReady.expect(false.B)
        dut.io.accepted.expect(false.B)
        dut.clock.step()
      }

      dut.io.completeReady.poke(true.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(true.B)
      dut.io.completeAccepted.expect(true.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.earlyReleaseValid.expect(false.B)
      dut.io.completeRobValue.expect(1.U)
      dut.clock.step()

      dut.io.earlyReleaseValid.expect(true.B)
      dut.io.earlyReleaseRid.value.expect(4.U)
      dut.io.inValid.poke(false.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeFire.expect(true.B)
      dut.io.completeRobValue.expect(2.U)
      dut.io.completeDstData.expect(17.U)
    }
  }

  test("dense four-entry ALU stream survives a mid-stream W2 stall without dropping or reordering") {
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
      for (_ <- 0 until 2) {
        dut.io.completeValid.expect(true.B)
        dut.io.completeFire.expect(false.B)
        dut.io.completeRobValue.expect(1.U)
        dut.io.inReady.expect(false.B)
        dut.io.accepted.expect(false.B)
        dut.clock.step()
      }

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

  test("malformed dispatch target does not use the pipe-safe ALU bypass") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      initExecute(dut)

      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_ADD, 1, BigInt("40004000", 16), 1, 2)
      dut.io.inReady.expect(true.B)
      dut.clock.step()

      pokeAlu(
        dut,
        FrontendOpcodeDecodeTable.OP_ADD,
        2,
        BigInt("40004004", 16),
        3,
        4,
        dispatchTarget = DispatchTarget.Bru)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()

      dut.io.inReady.expect(true.B)
      dut.io.completeValid.expect(false.B)
      dut.io.completeFire.expect(false.B)
    }
  }

  test("unsupported W2 entries diagnose and release once without ROB-complete readiness") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      initExecute(dut)
      dut.io.completeReady.poke(false.B)

      pokeAlu(dut, FrontendOpcodeDecodeTable.OP_INVALID, 5, BigInt("40005000", 16), 0, 0)
      dut.io.inReady.expect(true.B)
      dut.clock.step()
      dut.io.inValid.poke(false.B)
      dut.clock.step()
      dut.clock.step()

      dut.io.completeValid.expect(false.B)
      dut.io.completeFire.expect(false.B)
      dut.io.unsupported.expect(true.B)
      dut.io.unsupportedOpcode.expect(FrontendOpcodeDecodeTable.OP_INVALID.U)
      dut.io.releaseValid.expect(true.B)
      dut.io.earlyReleaseValid.expect(false.B)
      dut.io.releaseRid.value.expect(5.U)
      dut.clock.step()

      dut.io.unsupported.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.busy.expect(false.B)

      for ((opcode, index) <- unsupportedHlDualDstOps.zipWithIndex) {
        val rid = index % p.robEntries
        pokeAlu(dut, opcode, rid, BigInt("40005010", 16) + index * 4, 7, 3)
        dut.io.inReady.expect(true.B)
        dut.clock.step()
        dut.io.inValid.poke(false.B)
        dut.clock.step()
        dut.clock.step()

        dut.io.completeValid.expect(false.B)
        dut.io.completeFire.expect(false.B)
        dut.io.unsupported.expect(true.B)
        dut.io.unsupportedOpcode.expect(opcode.U)
        dut.io.releaseValid.expect(true.B)
        dut.io.earlyReleaseValid.expect(false.B)
        dut.io.releaseRid.value.expect(rid.U)
        dut.clock.step()

        dut.io.unsupported.expect(false.B)
        dut.io.releaseValid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  test("unsafe inputs keep old execute backpressure around pipe-safe ALU ops") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadPairFirstLookupData.poke(0.U)
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

      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.in.valid.poke(true.B)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_ADD.U)
      dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
      dut.io.in.rid.valid.poke(true.B)
      dut.io.in.rid.value.poke(1.U)
      dut.io.in.dst(0).valid.poke(true.B)
      dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.in.dst(0).archTag.poke(10.U)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(2.U)
      dut.io.in.src(1).valid.poke(true.B)
      dut.io.in.src(1).operandClass.poke(OperandClass.P)
      dut.io.in.src(1).archTag.poke(3.U)
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(true.B)
      dut.clock.step()

      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.in.valid.poke(true.B)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_LR_W.U)
      dut.io.in.rid.valid.poke(true.B)
      dut.io.in.rid.value.poke(2.U)
      dut.io.in.isLoad.poke(true.B)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(2.U)
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()

      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.in.valid.poke(true.B)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_LR_W.U)
      dut.io.in.rid.valid.poke(true.B)
      dut.io.in.rid.value.poke(3.U)
      dut.io.in.isLoad.poke(true.B)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(2.U)
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(true.B)
      dut.clock.step()

      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_ADD.U)
      dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
      dut.io.in.isLoad.poke(false.B)
      dut.io.in.rid.value.poke(4.U)
      dut.io.in.dst(0).valid.poke(true.B)
      dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.in.dst(0).archTag.poke(11.U)
      dut.io.inReady.expect(false.B)
      dut.io.accepted.expect(false.B)
    }
  }

  test("flush clears every stage of a dense safe ALU pipeline") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadPairFirstLookupData.poke(0.U)
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

      for (rid <- 1 to 3) {
        dut.io.in.poke(0.U.asTypeOf(dut.io.in))
        dut.io.in.valid.poke(true.B)
        dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_ADD.U)
        dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
        dut.io.in.rid.valid.poke(true.B)
        dut.io.in.rid.value.poke(rid.U)
        dut.io.in.dst(0).valid.poke(true.B)
        dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
        dut.io.in.dst(0).archTag.poke((rid + 10).U)
        dut.io.in.src(0).valid.poke(true.B)
        dut.io.in.src(0).operandClass.poke(OperandClass.P)
        dut.io.in.src(0).archTag.poke(2.U)
        dut.io.in.src(1).valid.poke(true.B)
        dut.io.in.src(1).operandClass.poke(OperandClass.P)
        dut.io.in.src(1).archTag.poke(3.U)
        dut.io.srcData(0).poke(rid.U)
        dut.io.srcData(1).poke(rid.U)
        dut.io.inValid.poke(true.B)
        dut.io.inReady.expect(true.B)
        if (rid == 1) {
          dut.io.earlyReleaseValid.expect(false.B)
        } else {
          dut.io.earlyReleaseValid.expect(true.B)
          dut.io.earlyReleaseRid.value.expect((rid - 1).U)
        }
        dut.clock.step()
      }

      dut.io.inValid.poke(false.B)
      dut.io.completeValid.expect(true.B)
      dut.io.flushValid.poke(true.B)
      dut.io.earlyReleaseValid.expect(false.B)
      dut.clock.step()
      dut.io.flushValid.poke(false.B)
      dut.io.completeValid.expect(false.B)
      dut.io.earlyReleaseValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  test("LR.W uses scalar load lookup address and sign-extends the returned word") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(BigInt("80000000", 16).U)
      dut.io.loadPairFirstLookupData.poke(0.U)
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

      dut.io.in.valid.poke(true.B)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_LR_W.U)
      dut.io.in.insnRaw.poke(BigInt("2000000b", 16).U)
      dut.io.in.insnLen.poke(4.U)
      dut.io.in.pc.poke(BigInt("40001000", 16).U)
      dut.io.in.bid.valid.poke(true.B)
      dut.io.in.bid.value.poke(1.U)
      dut.io.in.gid.valid.poke(true.B)
      dut.io.in.gid.value.poke(2.U)
      dut.io.in.rid.valid.poke(true.B)
      dut.io.in.rid.value.poke(3.U)
      dut.io.in.lsid.poke(5.U)
      dut.io.in.dst(0).valid.poke(true.B)
      dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.in.dst(0).archTag.poke(7.U)
      dut.io.in.dst(0).physTag.poke(9.U)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(4.U)
      dut.io.srcData(0).poke(BigInt("0000000000002000", 16).U)

      dut.io.inReady.expect(true.B)
      dut.io.inValid.poke(true.B)
      dut.clock.step()

      dut.io.inValid.poke(false.B)
      dut.io.loadLookupValid.expect(true.B)
      dut.io.loadLookupAddr.expect(BigInt("0000000000002000", 16).U)
      dut.io.loadLookupSize.expect(4.U)
      dut.io.loadLookupReturnSignExtend.expect(true.B)
      dut.io.loadLookupBid.value.expect(1.U)
      dut.io.loadLookupGid.value.expect(2.U)
      dut.io.loadLookupRid.value.expect(3.U)
      dut.io.loadLookupLsId.expect(5.U)

      var cycles = 0
      while (!dut.io.completeValid.peek().litToBoolean && cycles < 8) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.completeValid.expect(true.B)
      dut.io.unsupported.expect(false.B)
      dut.io.completeDstPhysValid.expect(true.B)
      dut.io.completeDstPhysTag.expect(9.U)
      dut.io.completeDstData.expect(BigInt("ffffffff80000000", 16).U)
      dut.io.completeRow.mem.valid.expect(true.B)
      dut.io.completeRow.mem.isStore.expect(false.B)
      dut.io.completeRow.mem.addr.expect(BigInt("0000000000002000", 16).U)
      dut.io.completeRow.mem.rdata.expect(BigInt("ffffffff80000000", 16).U)
      dut.io.completeRow.mem.size.expect(4.U)
    }
  }

  test("LR.W waits for the staged load-return data even when LIQ is enabled") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadPairFirstLookupData.poke(0.U)
      dut.io.loadLookupWaitBlocked.poke(false.B)
      dut.io.loadLiqEnable.poke(true.B)
      dut.io.loadLiqAccepted.poke(true.B)
      dut.io.stackPointerData.poke(0.U)
      dut.io.flushValid.poke(false.B)
      dut.io.fretStkFallbackTargetValid.poke(false.B)
      dut.io.fretStkFallbackTarget.poke(0.U)
      dut.io.fretStkConditionValid.poke(false.B)
      dut.io.fretStkConditionTaken.poke(false.B)
      dut.io.completeReady.poke(true.B)

      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_LR_W.U)
      dut.io.in.insnRaw.poke(BigInt("2000000b", 16).U)
      dut.io.in.insnLen.poke(4.U)
      dut.io.in.pc.poke(BigInt("40001000", 16).U)
      dut.io.in.bid.valid.poke(true.B)
      dut.io.in.bid.value.poke(1.U)
      dut.io.in.gid.valid.poke(true.B)
      dut.io.in.gid.value.poke(2.U)
      dut.io.in.rid.valid.poke(true.B)
      dut.io.in.rid.value.poke(3.U)
      dut.io.in.lsid.poke(5.U)
      dut.io.in.dst(0).valid.poke(true.B)
      dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.in.dst(0).physTag.poke(9.U)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.srcData(0).poke(BigInt("0000000000002000", 16).U)

      dut.io.inValid.poke(true.B)
      dut.clock.step()
      dut.io.inValid.poke(false.B)

      dut.io.loadLookupValid.expect(true.B)
      dut.io.loadLiqEligible.expect(false.B)
      dut.io.loadLookupWaitBlocked.poke(true.B)
      dut.io.loadLookupData.poke(BigInt("000000007fffffff", 16).U)
      dut.clock.step()
      dut.io.completeValid.expect(false.B)
      dut.io.loadWaitHold.expect(true.B)

      dut.io.loadLookupWaitBlocked.poke(false.B)
      dut.io.loadLookupData.poke(BigInt("0000000080000000", 16).U)
      dut.clock.step()
      dut.clock.step()

      dut.io.completeValid.expect(true.B)
      dut.io.completeDstPhysValid.expect(true.B)
      dut.io.completeDstPhysTag.expect(9.U)
      dut.io.completeDstData.expect(BigInt("ffffffff80000000", 16).U)
      dut.io.completeRow.mem.valid.expect(true.B)
      dut.io.completeRow.mem.rdata.expect(BigInt("ffffffff80000000", 16).U)
    }
  }

  test("generated execute datapath covers new scalar bit, min/max, multiply, and HL-immediate operations") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    simulate(new ReducedScalarAluExecute(p, trace)) { dut =>
      dut.io.inValid.poke(false.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.srcData.foreach(_.poke(0.U))
      dut.io.loadLookupData.poke(0.U)
      dut.io.loadPairFirstLookupData.poke(0.U)
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

      def execute(
          opcode: Int,
          insn: BigInt,
          src0: BigInt,
          src1: BigInt,
          expected: BigInt,
          imm: BigInt = 0): Unit = {
        dut.io.inReady.expect(true.B)
        dut.io.in.poke(0.U.asTypeOf(dut.io.in))
        dut.io.in.valid.poke(true.B)
        dut.io.in.opcode.poke(opcode.U)
        dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
        dut.io.in.dst(0).valid.poke(true.B)
        dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
        dut.io.in.dst(0).physTag.poke(7.U)
        dut.io.in.insnRaw.poke(insn.U)
        dut.io.in.imm.poke(imm.U)
        dut.io.srcData(0).poke(src0.U)
        dut.io.srcData(1).poke(src1.U)
        dut.io.inValid.poke(true.B)
        dut.clock.step()
        dut.io.inValid.poke(false.B)
        var cycles = 0
        while (!dut.io.completeValid.peek().litToBoolean && cycles < 8) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.completeValid.expect(true.B)
        dut.io.unsupported.expect(false.B)
        dut.io.completeDstData.expect(expected.U)
        dut.clock.step()
      }

      val wrapFieldInsn = (BigInt(60) << 26) | (BigInt(7) << 20)
      execute(
        FrontendOpcodeDecodeTable.OP_BXU,
        wrapFieldInsn,
        (BigInt(1) << 60) | BigInt(1),
        0,
        0x11)
      execute(
        FrontendOpcodeDecodeTable.OP_BIC,
        wrapFieldInsn,
        BigInt("ffffffffffffffff", 16),
        0,
        BigInt("0ffffffffffffff0", 16))
      execute(
        FrontendOpcodeDecodeTable.OP_CTZ,
        wrapFieldInsn,
        BigInt(1) << 1,
        0,
        5)
      execute(
        FrontendOpcodeDecodeTable.OP_MAX,
        0,
        BigInt("ffffffffffffffff", 16),
        1,
        1)
      execute(
        FrontendOpcodeDecodeTable.OP_MULUW,
        0,
        BigInt("ffffffff", 16),
        2,
        BigInt("fffffffffffffffe", 16))
      execute(
        FrontendOpcodeDecodeTable.OP_HL_ADDIW,
        0,
        BigInt("7fffffff", 16),
        0,
        BigInt("ffffffff80000000", 16),
        imm = 1)
      execute(
        FrontendOpcodeDecodeTable.OP_HL_ORI,
        0,
        BigInt("0000000012340000", 16),
        0,
        BigInt("ffffffffffffffff", 16),
        imm = BigInt("ffffffffffffffff", 16))
      execute(
        FrontendOpcodeDecodeTable.OP_HL_XORIW,
        0,
        BigInt("000000007fffffff", 16),
        0,
        BigInt("ffffffff80000000", 16),
        imm = BigInt("ffffffffffffffff", 16))
      execute(
        FrontendOpcodeDecodeTable.OP_C_SLLI,
        0,
        BigInt("4000000000000001", 16),
        0,
        BigInt("8000000000000002", 16),
        imm = 1)
      execute(
        FrontendOpcodeDecodeTable.OP_C_SRLI,
        0,
        BigInt("8000000000000002", 16),
        0,
        BigInt("4000000000000001", 16),
        imm = 1)
      execute(
        FrontendOpcodeDecodeTable.OP_HL_LIS,
        0,
        0,
        0,
        BigInt("ffffffff80000001", 16),
        imm = BigInt("ffffffff80000001", 16))
      execute(
        FrontendOpcodeDecodeTable.OP_HL_LIU,
        0,
        0,
        0,
        BigInt("0000000080000001", 16),
        imm = BigInt("0000000080000001", 16))

      dut.io.inReady.expect(true.B)
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.in.valid.poke(true.B)
      dut.io.in.pc.poke(BigInt("40005d32", 16).U)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_HL_SDI_PO.U)
      dut.io.in.dispatchTarget.poke(DispatchTarget.Alu)
      dut.io.in.imm.poke(BigInt("ffffffffffffffff", 16).U)
      dut.io.in.insnLen.poke(6.U)
      dut.io.in.dst(0).valid.poke(true.B)
      dut.io.in.dst(0).kind.poke(DestinationKind.Gpr)
      dut.io.in.dst(0).archTag.poke(3.U)
      dut.io.in.dst(0).physTag.poke(17.U)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(5.U)
      dut.io.in.src(1).valid.poke(true.B)
      dut.io.in.src(1).operandClass.poke(OperandClass.P)
      dut.io.in.src(1).archTag.poke(6.U)
      dut.io.srcData(0).poke(BigInt("0123456789abcdef", 16).U)
      dut.io.srcData(1).poke(BigInt("00000000000166a0", 16).U)
      dut.io.inValid.poke(true.B)
      dut.clock.step()
      dut.io.inValid.poke(false.B)
      var hlSdiPoCycles = 0
      while (!dut.io.completeValid.peek().litToBoolean && hlSdiPoCycles < 8) {
        dut.clock.step()
        hlSdiPoCycles += 1
      }
      dut.io.completeValid.expect(true.B)
      dut.io.unsupported.expect(false.B)
      dut.io.completeDstPhysValid.expect(true.B)
      dut.io.completeDstPhysTag.expect(17.U)
      dut.io.completeDstData.expect(BigInt("0000000000016698", 16).U)
      dut.io.completeRow.wb.valid.expect(true.B)
      dut.io.completeRow.wb.reg.expect(3.U)
      dut.io.completeRow.wb.data.expect(BigInt("0000000000016698", 16).U)
      dut.io.completeRow.mem.valid.expect(true.B)
      dut.io.completeRow.mem.isStore.expect(true.B)
      dut.io.completeRow.mem.addr.expect(BigInt("00000000000166a0", 16).U)
      dut.io.completeRow.mem.wdata.expect(BigInt("0123456789abcdef", 16).U)
      dut.io.completeRow.mem.size.expect(8.U)
      dut.clock.step()
    }
  }

  test("word and immediate shifts match ISA, QEMU, and LinxCoreModel semantics") {
    val signExtendedMin = BigInt("ffffffff80000000", 16)
    val signExtendedAllOnes = BigInt("ffffffffffffffff", 16)

    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SLLIW,
      src0 = BigInt("1234567880000001", 16),
      src1 = 0,
      imm = 31).contains(signExtendedMin))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SLLW,
      src0 = 1,
      src1 = 63,
      imm = 0).contains(signExtendedMin))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRLIW,
      src0 = BigInt("ffffffff80000000", 16),
      src1 = 0,
      imm = 31).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRLW,
      src0 = BigInt("ffffffff80000000", 16),
      src1 = 63,
      imm = 0).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRAIW,
      src0 = BigInt("0000000080000000", 16),
      src1 = 0,
      imm = 31).contains(signExtendedAllOnes))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRAW,
      src0 = BigInt("0000000080000000", 16),
      src1 = 63,
      imm = 0).contains(signExtendedAllOnes))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRLI,
      src0 = BigInt("8000000000000000", 16),
      src1 = 0,
      imm = 63).contains(1))
    assert(ReducedScalarAluExecute.referenceResult(
      FrontendOpcodeDecodeTable.OP_SRAI,
      src0 = BigInt("8000000000000000", 16),
      src1 = 0,
      imm = 63).contains(signExtendedAllOnes))
  }

  test("FRET.STK only restores SP on the stack-return path") {
    val outerFrameSp = BigInt("37028", 16)
    assert(ReducedScalarAluExecute.referenceFretStkProducedSp(
      stackPointerData = outerFrameSp,
      frameSize = 16,
      loadsReturn = false) == outerFrameSp)
    assert(ReducedScalarAluExecute.referenceFretStkProducedSp(
      stackPointerData = outerFrameSp,
      frameSize = 16,
      loadsReturn = true) == BigInt("37038", 16))
  }
}
