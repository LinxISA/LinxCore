package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class LSUStoreSpec extends AnyFunSuite {
  test("public LSU retains one canonical STQ SCB and L1D owner graph") {
    val chirrtl = ChiselStage.emitCHIRRTL(new LSU(SimulationParamProfiles.W4))
    assert(chirrtl.contains("circuit LSU"))
    assert(chirrtl.contains("module STQEntryBank"))
    assert(chirrtl.contains("module SCBRowBank"))
    assert(chirrtl.contains("module ScalarL1D"))
    assert(!chirrtl.contains("module ReducedStoreMemoryOverlay"))
  }
}
