package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendTraceTopSpec extends AnyFunSuite {
  test("interface and trace parameters follow the reduced top configuration") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendTraceTopIO(p, trace)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.in.window.getWidth == 64)
    assert(io.f4ValidMask.getWidth == 4)
    assert(io.selectedRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
    assert(io.commitHeadRobValue.getWidth == 3)
  }

  test("LinxCoreFrontendTraceTop elaborates F4 decode through DecodeRenameROBPath") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendTraceTop"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module FrontendDecodeStage"))
    assert(sv.contains("module DecodeRenameQueue"))
    assert(sv.contains("module ScalarTURenameBridge"))
    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_commitContractError"))
    assert(sv.contains("io_commitHeadRobValue"))
  }
}
