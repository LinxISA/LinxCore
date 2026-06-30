package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendFetchTraceTopSpec extends AnyFunSuite {
  test("interface exposes live fetch source and reduced commit trace parameters") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendFetchTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendFetchTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendFetchTraceTopIO(p, trace)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.startPc.getWidth == 64)
    assert(io.fetchReqPc.getWidth == 64)
    assert(io.fetchRespWindow.getWidth == 64)
    assert(io.sourceCurrentPc.getWidth == 64)
    assert(io.sourceNextPktUid.getWidth == 64)
    assert(io.sourceAdvanceBytes.getWidth == 4)
    assert(io.f4ValidMask.getWidth == 4)
    assert(io.selectedRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
    assert(io.commitHeadRobValue.getWidth == 3)
  }

  test("LinxCoreFrontendFetchTraceTop elaborates source through F4 and DecodeRenameROBPath") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendFetchTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendFetchTraceTop"))
    assert(sv.contains("module FrontendFetchPacketSource"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module FrontendDecodeStage"))
    assert(sv.contains("module DecodeRenameQueue"))
    assert(sv.contains("module ScalarTURenameBridge"))
    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_fetchReqValid"))
    assert(sv.contains("io_fetchRespReady"))
    assert(sv.contains("io_sourceOutFire"))
    assert(sv.contains("io_commitContractError"))
  }
}
