package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ScalarIssueFabricSpec extends AnyFunSuite {
  test("fabric preserves total capacity while exposing bank arbitration") {
    val io = new ScalarIssueFabricIO(InterfaceParams(), depth = 8, bankCount = 2)

    assert(io.count.getWidth == 4)
    assert(io.selectedIndex.getWidth == 3)
    assert(io.bankOccupancy.length == 2)
    assert(io.bankOccupancy.head.getWidth == 3)
    assert(io.bankPickMask.getWidth == 2)
    assert(io.bankReadAttemptMask.getWidth == 2)
    assert(io.bankReadGrantMask.getWidth == 2)
    assert(io.bankIssueValidMask.getWidth == 2)
    assert(io.bankIssueGrantMask.getWidth == 2)
    assert(io.bankControlBlockedMask.getWidth == 2)
    assert(io.bankStoreOrderBlockedMask.getWidth == 2)
  }

  test("fabric elaborates resident banks plus I1 and I2 arbiters") {
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarIssueFabric(InterfaceParams(), depth = 8, bankCount = 2))

    assert(sv.contains("module ScalarIssueFabric"))
    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ScalarIssueCandidateArbiter"))
    assert(sv.contains("io_bankOccupancy_0"))
    assert(sv.contains("io_readContention"))
    assert(sv.contains("io_readArbitrationLoss"))
    assert(sv.contains("io_issueContention"))
    assert(sv.contains("io_controlFenceActive"))
    assert(sv.contains("io_controlFenceBlocked"))
    assert(sv.contains("io_storeOrderBlocked"))
  }
}
