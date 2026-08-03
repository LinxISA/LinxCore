package linxcore.ooo

import circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OOOPcBufferIntegrationSpec extends AnyFunSuite {
  test("canonical W2 W4 W6 and W8 graphs contain exactly one PC buffer owner") {
    Seq(2, 4, 6, 8).foreach { width =>
      val chirrtl = ChiselStage.emitCHIRRTL(
        new OOOD3S1Graph(ParamProfiles.forWidth(width)))
      val instance = "inst pcBuffer of OooPcBuffer"
      assert(chirrtl.sliding(instance.length).count(_ == instance) == 1)
    }
  }
}
