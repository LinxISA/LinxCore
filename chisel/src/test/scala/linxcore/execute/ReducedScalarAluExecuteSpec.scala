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
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVI, 0, 0, 0x15).contains(0x15))
    assert(ReducedScalarAluExecute.referenceResult(FrontendOpcodeDecodeTable.OP_C_MOVR, 0x1234, 0, 0).contains(0x1234))
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
    assert(sv.contains("io_releaseValid"))
    assert(sv.contains("io_releaseBid_value"))
    assert(sv.contains("io_releaseRid_value"))
    assert(sv.contains("io_releaseStid"))
    assert(sv.contains("io_unsupportedOpcode"))
  }
}
