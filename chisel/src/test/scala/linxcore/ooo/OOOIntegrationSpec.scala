package linxcore.ooo

import circt.stage.ChiselStage
import linxcore.params.{ParamProfiles, CoreParams}
import org.scalatest.funsuite.AnyFunSuite

class OOOIntegrationSpec extends AnyFunSuite {
  test("elaborates the canonical owner graph at W2 W4 W6 and W8") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p: CoreParams = ParamProfiles.forWidth(width)
      val chirrtl = ChiselStage.emitCHIRRTL(new OOO(p))
      assert(chirrtl.contains("circuit OOO"))
      assert(chirrtl.contains("module OooD3ReservationAllocator"))
    }
  }
}
