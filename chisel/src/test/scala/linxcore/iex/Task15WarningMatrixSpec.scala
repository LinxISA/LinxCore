package linxcore.ooo

import circt.stage.ChiselStage
import linxcore.iex.IEX
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

/** Bounded elaboration-only warning matrix for the reviewed Task-15 owners. */
class Task15WarningMatrixSpec extends AnyFunSuite {
  test("W2 W4 W6 W8 IEX and ROB warning matrix") {
    Seq(2, 4, 6, 8).foreach { width =>
      println(s"TASK15_WARNING_MATRIX_BEGIN W$width")
      val p = SimulationParamProfiles.forWidth(width)
      ChiselStage.emitCHIRRTL(new IEX(p))
      ChiselStage.emitCHIRRTL(new ROB(p))
      ChiselStage.emitCHIRRTL(new PRename(p))
      ChiselStage.emitCHIRRTL(new BROB(p))
      println(s"TASK15_WARNING_MATRIX_END W$width")
    }
  }
}
