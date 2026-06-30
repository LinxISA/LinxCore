package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendFetchRfAluTraceTopSpec extends AnyFunSuite {
  test("interface combines live fetch source with RF-backed issue and ALU diagnostics") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendFetchRfAluTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendFetchRfAluTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendFetchRfAluTraceTopIO(p, trace, issueQueueDepth = 4, physRegs = 64)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.startPc.getWidth == 64)
    assert(io.fetchReqPc.getWidth == 64)
    assert(io.fetchRespWindow.getWidth == 64)
    assert(io.sourceAdvanceBytes.getWidth == 4)
    assert(io.denseSlotQueueInFire.getWidth == 1)
    assert(io.denseSlotQueueOutFire.getWidth == 1)
    assert(io.denseSlotQueueInSlotCount.getWidth == 3)
    assert(io.denseSlotQueueCount.getWidth == 4)
    assert(io.denseSlotQueueHeadSlot.getWidth == 2)
    assert(io.blockMarkerSkipFire.getWidth == 1)
    assert(io.blockMarkerMixedPacket.getWidth == 1)
    assert(io.blockMarkerPc.getWidth == 64)
    assert(io.blockMarkerInsn.getWidth == 64)
    assert(io.blockMarkerLen.getWidth == 4)
    assert(io.rfInitArchTag.getWidth == 6)
    assert(io.rfInitData.getWidth == 64)
    assert(io.rfReadyMask.getWidth == 64)
    assert(io.issueQueueCount.getWidth == 3)
    assert(io.issueQueueSelectedIndex.getWidth == 2)
    assert(io.executeCompleteRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
  }

  test("LinxCoreFrontendFetchRfAluTraceTop elaborates source, frontend, rename, RF, issue, ROB, and ALU execute") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendFetchRfAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendFetchRfAluTraceTop"))
    assert(sv.contains("module FrontendFetchPacketSource"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module F4DenseSlotQueue"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module ReducedScalarRegisterFile"))
    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_fetchReqValid"))
    assert(sv.contains("io_sourceOutFire"))
    assert(sv.contains("io_denseSlotQueueOutFire"))
    assert(sv.contains("io_blockMarkerSkipFire"))
    assert(sv.contains("io_blockMarkerMixedPacket"))
    assert(sv.contains("io_rfWriteValid"))
    assert(sv.contains("io_issueQueuePickFire"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_commitContractError"))
  }
}
