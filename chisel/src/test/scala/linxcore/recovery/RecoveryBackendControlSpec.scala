package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object RecoveryBackendControlReference {
  def robFlushValid(intentValid: Boolean, robPruneValid: Boolean, intentReady: Boolean): Boolean =
    intentValid && robPruneValid && intentReady
}

class RecoveryBackendControlSpec extends AnyFunSuite {
  import RecoveryBackendControlReference._

  test("ROB mutation is qualified by the shared cleanup handshake") {
    assert(!robFlushValid(intentValid = true, robPruneValid = true, intentReady = false))
    assert(!robFlushValid(intentValid = true, robPruneValid = false, intentReady = true))
    assert(!robFlushValid(intentValid = false, robPruneValid = true, intentReady = true))
    assert(robFlushValid(intentValid = true, robPruneValid = true, intentReady = true))
  }

  test("backend owner elaborates parameterized non-LSU and LSU source composition") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryBackendControl(
      nonLsuSourceCount = 2,
      stidCount = 2,
      peCount = 2,
      entries = 8,
      bidWidth = 16
    ))

    assert(sv.contains("module RecoveryBackendControl"))
    assert(sv.contains("module RecoveryFabric"))
    assert(sv.contains("module RecoverySourceArbiter"))
    assert(sv.contains("module RecoveryClassMerge"))
    assert(sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("io_nonLsuSources_0_blockBid"))
    assert(sv.contains("io_nonLsuSources_1_blockBid"))
    assert(sv.contains("io_lsuSource_blockBid"))
    assert(sv.contains("io_robFullBidLookupRequest_rid_value"))
    assert(sv.contains("io_robFlush_req_valid"))
  }

  test("backend owner supports a scalar top with no non-LSU source ports") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryBackendControl(
      nonLsuSourceCount = 0,
      stidCount = 1,
      peCount = 1,
      entries = 8,
      bidWidth = 64
    ))

    assert(sv.contains("module RecoveryBackendControl"))
    assert(!sv.contains("io_nonLsuSources_0"))
    assert(sv.contains("io_lsuSource_blockBid"))
  }
}
