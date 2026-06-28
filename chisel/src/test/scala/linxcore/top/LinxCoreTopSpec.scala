package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreTopSpec extends AnyFunSuite {
  test("trace parameters follow the top-level ROB and commit configuration") {
    val params = CoreParams(robEntries = 8, commitWidth = 2)
    val trace = LinxCoreTop.traceParamsFor(params)

    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
  }

  test("Chisel LinxCoreTop elaborates with the monitored reduced commit shell") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreTop(CoreParams(robEntries = 8, commitWidth = 2))
    )

    assert(sv.contains("module LinxCoreTop"))
    assert(sv.contains("module ReducedCommitROB"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("commitContractError"))
    assert(sv.contains("io_idle"))
  }
}
