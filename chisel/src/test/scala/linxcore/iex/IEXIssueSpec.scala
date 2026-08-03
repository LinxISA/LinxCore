package linxcore.iex

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class IEXIssueSpec extends AnyFunSuite {
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
}
