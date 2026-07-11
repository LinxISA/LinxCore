package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreTopSpec extends AnyFunSuite {
  test("trace parameters follow the top-level ROB and commit configuration") {
    val params = CoreParams(robEntries = 8, commitWidth = 2)
    val trace = LinxCoreTop.traceParamsFor(params)

    assert(trace.commitWidth == 2)
    assert(trace.robValueWidth == 3)
  }

  test("Chisel LinxCoreTop elaborates with commit and scalar LSU owners") {
    val lsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      liqEntries = 4,
      resolveQueueEntries = 8,
      mapQDepth = 8
    )
    val sv = ChiselStage.emitSystemVerilog(
      new LinxCoreTop(CoreParams(robEntries = 8, commitWidth = 2, scalarLsu = lsu))
    )

    assert(sv.contains("module LinxCoreTop"))
    assert(sv.contains("module ReducedCommitROB"))
    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("module ScalarLSU"))
    assert(sv.contains("module STQSCBCommitPath"))
    assert(sv.contains("module ScalarLSULoadPath"))
    assert(sv.contains("module RecoveryEligibilityControl"))
    assert(sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("io_scalarLsu_store_flush_req_valid"))
    assert(sv.contains("io_scalarLsu_load_preciseFlush_req_valid"))
    assert(sv.contains("io_scalarLsu_recovery_oldestBid_value"))
    assert(sv.contains("io_scalarLsu_recovery_fullBidLookupRequest_rid_value"))
    assert(sv.contains("io_scalarLsu_recovery_fullBidLookup_blockBid"))
    assert(sv.contains("io_scalarLsu_recovery_intent_flush_req_valid"))
    assert(sv.contains("commitContractError"))
    assert(sv.contains("io_idle"))
  }
}
