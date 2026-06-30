package linxcore.execute

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarAluExecuteSpec extends AnyFunSuite {
  test("reference results match the model-derived reduced scalar ALU subset") {
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADD, 10, 32, 0).contains(42))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_ADDI, 7, 0, 0x7ff).contains(2054))
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
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_OR, BigInt("100000000", 16), 0x5a, 0).contains(BigInt("10000005a", 16)))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVI, 0, 0, 0x15).contains(0x15))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVR, 0x1234, 0, 0).contains(0x1234))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_ADD, 0x1234, 0x100, 0).contains(0x1334))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_LDI, 0x4ffefdb0L, 0, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LDI, 0x4fff0048L, 0, BigInt("fffffffffffffffe", 16)).contains(0))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_LDI,
      src0 = 0x4000ecc8L,
      src1 = 0,
      imm = 0,
      loadData = 0x6ffffffbL).contains(0x6ffffffbL))
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
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SETC_LTU, 36, 0x6ffffffbL, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTU, 36, 0x6ffffffbL).contains(true))
    assert(ReducedScalarAluExecute.referenceBranchCondition(FrontendOpcodeDecodeTable.OP_SETC_LTU, 0x6ffffffbL, 36).contains(false))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SD, 0x4ffefdc0L, 21, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_SDI, 0, 0x4fff0128L, 0).contains(0))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_LD, 1, 2, 3).isEmpty)
  }

  test("ReducedScalarAluExecute elaborates completion-row writeback payloads") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarAluExecute(p, trace))

    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_completeRobValue"))
    assert(sv.contains("io_completeRow_wb_valid"))
    assert(sv.contains("io_completeRow_dst_data"))
    assert(sv.contains("io_completeDstPhysValid"))
    assert(sv.contains("io_completeDstPhysTag"))
    assert(sv.contains("io_completeDstData"))
    assert(sv.contains("io_branchConditionValid"))
    assert(sv.contains("io_branchConditionTaken"))
    assert(sv.contains("io_releaseValid"))
    assert(sv.contains("io_releaseBid_value"))
    assert(sv.contains("io_releaseRid_value"))
    assert(sv.contains("io_releaseStid"))
    assert(sv.contains("io_unsupportedOpcode"))
  }
}
