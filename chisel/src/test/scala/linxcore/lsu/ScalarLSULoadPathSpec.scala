package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

class ScalarLSULoadPathSpec extends AnyFunSuite {
  test("load and resolve capacities are independent from ROB and STQ sizing") {
    val lsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      liqEntries = 4,
      resolveQueueEntries = 8,
      mapQDepth = 8
    )
    val core = CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu)

    assert(core.robEntries == 32)
    assert(core.scalarLsu.stqEntries == 8)
    assert(core.scalarLsu.liqEntries == 4)
    assert(core.scalarLsu.resolveQueueEntries == 8)
    assert(core.scalarLsu.mdbRecoveryQueueEntries == 8)
  }

  test("ScalarLSULoadPath elaborates LIQ-to-ResolveQ lifecycle ownership") {
    val lsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      liqEntries = 4,
      resolveQueueEntries = 8,
      mapQDepth = 8
    )
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarLSULoadPath(CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu))
    )

    assert(sv.contains("module ScalarLSULoadPath"))
    assert(sv.contains("module LoadInflightQueue"))
    assert(sv.contains("module LoadResolveQueue"))
    assert(sv.contains("module ScalarLSUMDBPath"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_liqFlushPruneMask"))
    assert(sv.contains("io_resolveFlushPruneMask"))
    assert(sv.contains("io_transferPending"))
    assert(sv.contains("io_transferProtocolError"))
    assert(sv.contains("io_liqRows_0_stid"))
    assert(sv.contains("io_resolveConflictRows_0_stid"))
    assert(sv.contains("io_mdbConflictFlush_req_valid"))
    assert(sv.contains("recovery_ready"))
    assert(sv.contains("recovery_flush_req_valid"))
    assert(sv.contains("io_mdbLookupWaitMutation"))
    assert(sv.contains("io_mdbTransientEmpty"))
  }
}
