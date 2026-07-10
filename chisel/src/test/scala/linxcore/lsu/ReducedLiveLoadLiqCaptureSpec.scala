package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLiveLoadLiqCaptureReference {
  final case class Step(
      candidateValid: Boolean,
      accepted: Boolean,
      blockedByAlloc: Boolean)

  def step(enable: Boolean, flush: Boolean, loadValid: Boolean, allocReady: Boolean): Step = {
    val candidateValid = enable && !flush && loadValid
    Step(
      candidateValid = candidateValid,
      accepted = candidateValid && allocReady,
      blockedByAlloc = candidateValid && !allocReady)
  }
}

class ReducedLiveLoadLiqCaptureSpec extends AnyFunSuite {
  import ReducedLiveLoadLiqCaptureReference._

  test("admits an enabled valid E1 load only when the LIQ adapter is ready") {
    val accepted = step(enable = true, flush = false, loadValid = true, allocReady = true)
    assert(accepted.candidateValid)
    assert(accepted.accepted)
    assert(!accepted.blockedByAlloc)

    val blocked = step(enable = true, flush = false, loadValid = true, allocReady = false)
    assert(blocked.candidateValid)
    assert(!blocked.accepted)
    assert(blocked.blockedByAlloc)
  }

  test("flush and disabled admission suppress an otherwise valid load") {
    val flushed = step(enable = true, flush = true, loadValid = true, allocReady = true)
    val disabled = step(enable = false, flush = false, loadValid = true, allocReady = true)

    assert(!flushed.candidateValid && !flushed.accepted && !flushed.blockedByAlloc)
    assert(!disabled.candidateValid && !disabled.accepted && !disabled.blockedByAlloc)
  }

  test("ReducedLiveLoadLiqCapture elaborates the E1 metadata and allocation handshake") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedLiveLoadLiqCapture(idEntries = 8, archRegWidth = 5, physRegWidth = 6))

    assert(sv.contains("module ReducedLiveLoadLiqCapture"))
    assert(sv.contains("io_captureEnable"))
    assert(sv.contains("io_loadValid"))
    assert(sv.contains("io_loadDst_oldPhysTag"))
    assert(sv.contains("io_loadSource0_data"))
    assert(sv.contains("io_loadBid_value"))
    assert(sv.contains("io_loadLsId_value"))
    assert(sv.contains("io_youngestStoreLsId_value"))
    assert(sv.contains("io_candidateValid"))
    assert(sv.contains("io_candidate_dst_physTag"))
    assert(sv.contains("io_captureAccepted"))
    assert(sv.contains("io_blockedByAlloc"))
  }
}
