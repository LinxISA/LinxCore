package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendRfAluTraceTopSpec extends AnyFunSuite {
  test("interface removes operand fixtures and exposes RF plus issue debug") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendRfAluTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendRfAluTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendRfAluTraceTopIO(p, trace, issueQueueDepth = 4, physRegs = 64)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.in.window.getWidth == 64)
    assert(io.rfInitArchTag.getWidth == 6)
    assert(io.rfInitData.getWidth == 64)
    assert(io.rfReadyMask.getWidth == 64)
    assert(io.rfWriteTag.getWidth == 6)
    assert(io.issueQueueCount.getWidth == 3)
    assert(io.issueQueueIssuedCount.getWidth == 3)
    assert(io.issueQueueNotIssuedCount.getWidth == 3)
    assert(io.issueQueueSourceReadyMask.getWidth == 3)
    assert(io.issueQueueSelectedIndex.getWidth == 2)
    assert(io.issueQueueSelectedReadReady.getWidth == 1)
    assert(io.issueQueuePickFire.getWidth == 1)
    assert(io.issueQueueCancelFire.getWidth == 1)
    assert(io.issueQueueI1Valid.getWidth == 1)
    assert(io.issueQueueI2Valid.getWidth == 1)
    assert(io.issueQueueStageBusy.getWidth == 1)
    assert(io.issueQueueBankOccupancy.length == 2)
    assert(io.issueQueueBankPickMask.getWidth == 2)
    assert(io.issueQueueBankReadAttemptMask.getWidth == 2)
    assert(io.issueQueueBankIssueValidMask.getWidth == 2)
    assert(io.issueQueueReadArbitrationLoss.getWidth == 1)
    assert(io.executeCompleteRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
  }

  test("LinxCoreFrontendRfAluTraceTop elaborates frontend, rename, RF, issue, ROB, and ALU execute") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendRfAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendRfAluTraceTop"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module ScalarGPRFile"))
    assert(sv.contains("module ScalarIssueFabric"))
    assert(sv.contains("module ScalarIssueCandidateArbiter"))
    assert(!sv.contains("module ReducedScalarRegisterFile"))
    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_rfInitValid"))
    assert(sv.contains("io_rfWriteValid"))
    assert(sv.contains("io_issueQueueEnqueueFire"))
    assert(sv.contains("io_issueQueuePickFire"))
    assert(sv.contains("io_issueQueueCancelFire"))
    assert(sv.contains("io_issueQueueReleaseFire"))
    assert(sv.contains("io_issueQueueHeadIssued"))
    assert(sv.contains("io_issueQueueSourceReadyMask"))
    assert(sv.contains("io_issueQueueSelectedValid"))
    assert(sv.contains("io_issueQueueSelectedIndex"))
    assert(sv.contains("io_issueQueueSelectedReadReady"))
    assert(sv.contains("io_issueQueueI1Valid"))
    assert(sv.contains("io_issueQueueI2Valid"))
    assert(sv.contains("io_issueQueueStageBusy"))
    assert(sv.contains("io_issueQueueBlockedBySource"))
    assert(sv.contains("io_issueQueueBlockedByRead"))
    assert(sv.contains("io_issueQueueBlockedByIssued"))
    assert(sv.contains("io_issueQueueBankOccupancy_0"))
    assert(sv.contains("io_issueQueueReadArbitrationLoss"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_commitContractError"))
  }
}
