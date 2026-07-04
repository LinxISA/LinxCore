package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnScbLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      scbEvidenceValid: Boolean,
      externalScbPending: Boolean,
      externalScbReturned: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoPending: Boolean,
      blockedByScbReturn: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      scbPendingEvidence: Boolean,
      scbReturnedEvidence: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && requestEnable
    val rawEvidence = scbPendingEvidence || scbReturnedEvidence
    val externalScbPending = requestActive && scbPendingEvidence
    val externalScbReturned = externalScbPending && scbReturnedEvidence

    Result(
      active = active,
      requestActive = requestActive,
      scbEvidenceValid = active && rawEvidence,
      externalScbPending = externalScbPending,
      externalScbReturned = externalScbReturned,
      blockedByDisabled = !enable && (requestEnable || rawEvidence),
      blockedByFlush = enable && flush && (requestEnable || rawEvidence),
      blockedByRequestDisabled = active && !requestEnable && rawEvidence,
      blockedByNoPending = requestActive && scbReturnedEvidence && !scbPendingEvidence,
      blockedByScbReturn = requestActive && scbPendingEvidence && !scbReturnedEvidence)
  }
}

class LoadReplaySourceReturnScbLiveControlSpec extends AnyFunSuite {
  import LoadReplaySourceReturnScbLiveControlReference._

  test("current reduced top shape drives no external SCB dependency") {
    val result = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      scbPendingEvidence = false,
      scbReturnedEvidence = false)

    assert(result.active)
    assert(!result.requestActive)
    assert(!result.scbEvidenceValid)
    assert(!result.externalScbPending)
    assert(!result.externalScbReturned)
    assert(!result.blockedByRequestDisabled)
  }

  test("live pending evidence waits for the external SCB return") {
    val result = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      scbPendingEvidence = true,
      scbReturnedEvidence = false)

    assert(result.requestActive)
    assert(result.scbEvidenceValid)
    assert(result.externalScbPending)
    assert(!result.externalScbReturned)
    assert(result.blockedByScbReturn)
  }

  test("live pending plus returned evidence releases the SCB source") {
    val result = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      scbPendingEvidence = true,
      scbReturnedEvidence = true)

    assert(result.externalScbPending)
    assert(result.externalScbReturned)
    assert(!result.blockedByScbReturn)
    assert(!result.blockedByNoPending)
  }

  test("returned evidence without a pending dependency is reported but not armed") {
    val result = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      scbPendingEvidence = false,
      scbReturnedEvidence = true)

    assert(result.scbEvidenceValid)
    assert(!result.externalScbPending)
    assert(!result.externalScbReturned)
    assert(result.blockedByNoPending)
  }

  test("disabled, flushed, and request-disabled states suppress live outputs") {
    val disabled = LoadReplaySourceReturnScbLiveControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      scbPendingEvidence = true,
      scbReturnedEvidence = true)
    val flushed = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      scbPendingEvidence = true,
      scbReturnedEvidence = true)
    val requestDisabled = LoadReplaySourceReturnScbLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      scbPendingEvidence = true,
      scbReturnedEvidence = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(requestDisabled.blockedByRequestDisabled)
    assert(!disabled.externalScbPending && !disabled.externalScbReturned)
    assert(!flushed.externalScbPending && !flushed.externalScbReturned)
    assert(!requestDisabled.externalScbPending && !requestDisabled.externalScbReturned)
  }

  test("Chisel LoadReplaySourceReturnScbLiveControl elaborates request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnScbLiveControl)

    assert(sv.contains("module LoadReplaySourceReturnScbLiveControl"))
    assert(sv.contains("io_scbPendingEvidence"))
    assert(sv.contains("io_externalScbPending"))
    assert(sv.contains("io_blockedByScbReturn"))
  }
}
