package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendAluTraceTopSpec extends AnyFunSuite {
  test("interface and trace parameters follow the reduced ALU trace top configuration") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendAluTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendAluTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendAluTraceTopIO(p, trace)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.in.window.getWidth == 64)
    assert(io.operandData.length == 3)
    assert(io.operandData.head.getWidth == 64)
    assert(io.executeCompleteRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
  }

  test("LinxCoreFrontendAluTraceTop elaborates frontend, rename, ROB, and ALU execute") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendAluTraceTop"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_commitContractError"))
  }
}
