package linxcore.iex

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OOOIEXIntegrationSpec extends AnyFunSuite {
  test("OOO and IEX elaborate one canonical classed dispatch graph") {
    Seq(2, 4, 6, 8).foreach { width =>
      val rtl = ChiselStage.emitCHIRRTL(
        new OOOIEXLSUActivationProbe(
          SimulationParamProfiles.forWidth(width)))
      assert(rtl.contains("module OOO"))
      assert(rtl.contains("module IEX"))
      assert(rtl.contains("module OooIexExecutionPipeline"))
      assert(!rtl.contains("module ReducedScalarIssueQueue"))
    }
  }
}
