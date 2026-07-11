package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

class ScalarLSUSpec extends AnyFunSuite {
  test("scalar LSU sizing separates ROB identity from store capacity") {
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
    val core = CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu)

    assert(core.robEntries == 32)
    assert(core.scalarLsu.stqEntries == 8)
    assert(core.scalarLsu.commitQueueEntries == 4)
    assert(core.scalarLsu.mdbRecoveryQueueEntries == 8)
  }

  test("Chisel ScalarLSU owns the parameterized STQ-to-SCB store path") {
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
      new ScalarLSU(CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu))
    )

    assert(sv.contains("module ScalarLSU"))
    assert(sv.contains("module STQSCBCommitPath"))
    assert(sv.contains("module STQEntryBank"))
    assert(sv.contains("module STQCommitDrain"))
    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("module ScalarLSULoadPath"))
    assert(sv.contains("module LoadInflightQueue"))
    assert(sv.contains("module LoadResolveQueue"))
    assert(sv.contains("module ScalarLSUMDBPath"))
    assert(sv.contains("module MDBConflictDetect"))
    assert(sv.contains("module MDBQueueFanout"))
    assert(sv.contains("module MDBSSIT"))
    assert("""(?s)module ScalarLSU\(.*?input\s+\[4:0\]\s+io_store_flush_req_bid_value""".r.findFirstIn(sv).nonEmpty)
    assert(sv.contains("io_store_stqRows_7_valid"))
    assert(sv.contains("io_load_liqRows_3_valid"))
    assert(sv.contains("io_load_resolveEntries_7_valid"))
    assert(sv.contains("io_load_mdbConflictFlush_req_valid"))
    assert(sv.contains("module ScalarLSURecoverySource"))
    assert(sv.contains("module RecoveryEligibilityControl"))
    assert(sv.contains("module RingFullBidRecoveryBridge"))
    assert(!sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("io_recovery_fullBidLookupRequest_rid_value"))
    assert(sv.contains("io_recovery_sourceBlockedByLookupMiss"))
    assert(sv.contains("io_recovery_sourceEligible"))
    assert(sv.contains("io_recovery_source_blockBid"))
    assert(sv.contains("io_recovery_sourceReady"))
    assert(sv.contains("io_load_mdbSsitValidMask"))
  }

  test("ScalarLSURecoverySource elaborates exact promotion without local cleanup arbitration") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSURecoverySource(entries = 8, bidWidth = 16))

    assert(sv.contains("module ScalarLSURecoverySource"))
    assert(sv.contains("module RecoveryEligibilityControl"))
    assert(sv.contains("module RingFullBidRecoveryBridge"))
    assert(!sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("io_fullBidLookupRequest_rid_value"))
    assert(sv.contains("io_source_blockBid"))
    assert(sv.contains("io_sourceReady"))
    assert(sv.contains("io_blockedByStaleLookup"))
  }
}
