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
      loadReturnQueueEntries = 2,
      loadReturnPipeCount = 3,
      stidCount = 2,
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
      loadReturnQueueEntries = 2,
      loadReturnPipeCount = 3,
      stidCount = 2,
      mapQDepth = 8
    )
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarLSULoadPath(CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu))
    )

    assert(sv.contains("module ScalarLSULoadPath"))
    assert(sv.contains("module LoadInflightQueue"))
    assert(sv.contains("module LoadResolveQueue"))
    assert(sv.contains("module ScalarLSUMDBPath"))
    assert(sv.contains("module ScalarLSULoadReturnQueueBank"))
    assert(sv.contains("module ScalarLSULoadReturnQueue"))
    assert(sv.contains("module ScalarLSULoadReturnPipeline"))
    assert(sv.contains("module LoadReplayReturnDataExtract"))
    assert(sv.contains("module LoadReplayReturnLretPayload"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_liqFlushPruneMask"))
    assert(sv.contains("io_resolveFlushPruneMask"))
    assert(sv.contains("io_transferPending"))
    assert(sv.contains("io_transferProtocolError"))
    assert(sv.contains("io_launchBlockedByReturnCredit"))
    assert(sv.contains("io_loadReturn_robLookupValid"))
    assert(sv.contains("io_loadReturn_robRowValid"))
    assert(sv.contains("io_loadReturn_resolveReady_2"))
    assert(sv.contains("io_loadReturn_resolveFire_2"))
    assert(sv.contains("io_loadReturn_writebackFire_2"))
    assert(sv.contains("io_loadReturn_wakeupFire_2"))
    assert(sv.contains("io_loadReturn_w1ValidMask"))
    assert(sv.contains("io_loadReturn_w2ValidMask"))
    assert(sv.contains("io_loadReturn_pipelineEmpty"))
    assert(sv.contains("io_loadReturn_laneCounts_5"))
    assert(sv.contains("io_loadReturn_reservedCount"))
    assert(sv.contains("io_loadReturn_publicationAccepted"))
    assert(sv.contains("io_loadReturn_protocolError"))
    assert(sv.contains("io_alloc_returnPipeIndex"))
    assert("""(?s)module ScalarLSULoadPath\(.*?input\s+\[1:0\]\s+io_alloc_returnPipeIndex""".r.findFirstIn(sv).nonEmpty)
    assert("""(?s)module LoadInflightRowMutationPath\(.*?input\s+\[1:0\]\s+io_row_returnPipeIndex""".r.findFirstIn(sv).nonEmpty)
    assert("""(?s)module LoadInflightRowMutationPath\(.*?output\s+\[1:0\]\s+io_nextRow_returnPipeIndex""".r.findFirstIn(sv).nonEmpty)
    assert(sv.contains("io_liqRows_0_stid"))
    assert(sv.contains("io_resolveConflictRows_0_stid"))
    assert(sv.contains("io_mdbConflictFlush_req_valid"))
    assert(sv.contains("recovery_ready"))
    assert(sv.contains("recovery_flush_req_valid"))
    assert(sv.contains("io_mdbLookupWaitMutation"))
    assert(sv.contains("io_mdbTransientEmpty"))
  }

  test("load-return launch credit is reserved independently per STID and pipe") {
    val depth = 2
    val lanes = Array.fill(4)((0, 0)) // resident, reserved

    def canLaunch(lane: Int): Boolean = lanes(lane)._1 + lanes(lane)._2 < depth
    def launch(lane: Int): Unit = {
      assert(canLaunch(lane))
      lanes(lane) = (lanes(lane)._1, lanes(lane)._2 + 1)
    }
    def returnToQueue(lane: Int): Unit = {
      assert(lanes(lane)._2 > 0)
      lanes(lane) = (lanes(lane)._1 + 1, lanes(lane)._2 - 1)
    }

    launch(0)
    launch(0)
    assert(!canLaunch(0))
    assert(canLaunch(1) && canLaunch(2) && canLaunch(3))
    returnToQueue(0)
    assert(!canLaunch(0))
    lanes(0) = (lanes(0)._1 - 1, lanes(0)._2)
    assert(canLaunch(0))
  }
}
