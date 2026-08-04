package linxcore.top

import _root_.circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class TOPWidthProfilesSpec extends AnyFunSuite {
  Seq(2, 4, 6, 8).foreach { width =>
    test(s"W$width TOP elaborates the selected public widths") {
      val p = SimulationParamProfiles.forWidth(width)
      val chirrtl = ChiselStage.emitCHIRRTL(new TOP(p))

      assert(chirrtl.contains("module TOP"))
      assert(p.widths.fetchWidth == width)
      assert(p.widths.decodeWidth == width)
      assert(p.widths.issueWidth == width)
      assert(p.widths.retireWidth == width)
    }
  }
}
