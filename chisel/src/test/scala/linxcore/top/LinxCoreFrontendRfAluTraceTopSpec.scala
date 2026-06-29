package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.CoreParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreFrontendRfAluTraceTopSpec extends AnyFunSuite {
  test("interface removes operand fixtures and exposes RF preload and writeback debug") {
    val core = CoreParams(robEntries = 8, commitWidth = 2)
    val p = LinxCoreFrontendRfAluTraceTop.interfaceParamsFor(core)
    val trace = LinxCoreFrontendRfAluTraceTop.traceParamsFor(p)
    val io = new LinxCoreFrontendRfAluTraceTopIO(p, trace, physRegs = 64)

    assert(p.robEntries == 8)
    assert(p.commitWidth == 2)
    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
    assert(io.in.window.getWidth == 64)
    assert(io.rfInitArchTag.getWidth == 6)
    assert(io.rfInitData.getWidth == 64)
    assert(io.rfReadyMask.getWidth == 64)
    assert(io.rfWriteTag.getWidth == 6)
    assert(io.executeCompleteRobValue.getWidth == 3)
    assert(io.commit.rows.length == 2)
  }

  test("LinxCoreFrontendRfAluTraceTop elaborates frontend, rename, RF, ROB, and ALU execute") {
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreFrontendRfAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8)
    )

    assert(sv.contains("module LinxCoreFrontendRfAluTraceTop"))
    assert(sv.contains("module F4DecodeWindow"))
    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("module ReducedScalarRegisterFile"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("io_rfInitValid"))
    assert(sv.contains("io_rfWriteValid"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_commitContractError"))
  }
}
