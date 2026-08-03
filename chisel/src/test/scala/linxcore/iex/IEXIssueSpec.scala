package linxcore.iex

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class IEXIssueSpec extends AnyFunSuite {
  private def publicModule(chirrtl: String, name: String): String = {
    val marker = s"module $name :"
    val start = chirrtl.indexOf(marker)
    assert(start >= 0, s"missing public module $name")
    val next = chirrtl.indexOf("\n  module ", start + marker.length)
    if (next < 0) chirrtl.substring(start) else chirrtl.substring(start, next)
  }

  test("public IEX owns the canonical classed dispatch boundary at every width") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p = SimulationParamProfiles.forWidth(width)
      val chirrtl = ChiselStage.emitCHIRRTL(new IEX(p))
      assert(chirrtl.contains("circuit IEX"))
      assert(chirrtl.contains("module OooIexIssue"))
      assert(chirrtl.contains("module OooIexOperandFiles"))
      assert(!chirrtl.contains("module ReducedScalarIssueQueue"))
    }
  }

  test("public IEX shell contains no retained state or arbitration") {
    val chirrtl = ChiselStage.emitCHIRRTL(new IEX(SimulationParamProfiles.W4))
    val shell = publicModule(chirrtl, "IEX")

    assert(!shell.contains(" reg "), shell)
    assert(!shell.contains(" of Queue"), shell)
    assert(!shell.contains(" of RRArbiter"), shell)
    assert(!shell.contains("bootstrapSeen"), shell)
    assert(!shell.contains("resolveTransport"), shell)
    assert(!shell.contains("traceTransport"), shell)
  }
}
