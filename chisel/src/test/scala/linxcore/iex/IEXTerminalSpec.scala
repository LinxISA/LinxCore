package linxcore.iex

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class IEXTerminalSpec extends AnyFunSuite {
  test("public IEX retains one atomic typed terminal owner") {
    val chirrtl = ChiselStage.emitCHIRRTL(new IEX(SimulationParamProfiles.W4))
    assert(chirrtl.contains("module OooIexTerminalFabric"))
    assert(chirrtl.contains("module OooIexTerminalPublish"))
    assert(chirrtl.contains("robResolve"))
    assert(chirrtl.contains("recoveryEvent"))
    assert(chirrtl.contains("cmdIssue"))
  }
}
