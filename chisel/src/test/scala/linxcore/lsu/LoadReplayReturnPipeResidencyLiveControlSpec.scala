package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeResidencyLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      residencyEvidenceValid: Boolean,
      liveEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoInsertCandidate: Boolean,
      blockedByNoInsertValid: Boolean,
      blockedByPipeOccupied: Boolean,
      blockedByNoEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      insertCandidateValid: Boolean,
      insertValid: Boolean,
      selectedPipeOccupied: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && requestEnable
    val rawEvidence = insertCandidateValid && insertValid && !selectedPipeOccupied

    Result(
      active = active,
      requestActive = requestActive,
      residencyEvidenceValid = active && rawEvidence,
      liveEnable = requestActive && rawEvidence,
      blockedByDisabled = !enable && (requestEnable || rawEvidence),
      blockedByFlush = enable && flush && (requestEnable || rawEvidence),
      blockedByRequestDisabled = active && !requestEnable && rawEvidence,
      blockedByNoInsertCandidate = requestActive && !insertCandidateValid,
      blockedByNoInsertValid = requestActive && insertCandidateValid && !insertValid,
      blockedByPipeOccupied = requestActive && insertCandidateValid && insertValid && selectedPipeOccupied,
      blockedByNoEvidence = requestActive && !rawEvidence)
  }
}

class LoadReplayReturnPipeResidencyLiveControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencyLiveControlReference._

  test("keeps residency writes disabled when the top request is false") {
    val result = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      insertCandidateValid = true,
      insertValid = true,
      selectedPipeOccupied = false)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.residencyEvidenceValid)
    assert(!result.liveEnable)
    assert(result.blockedByRequestDisabled)
  }

  test("enables residency writes only with an active request and valid insert evidence") {
    val result = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      insertCandidateValid = true,
      insertValid = true,
      selectedPipeOccupied = false)

    assert(result.requestActive)
    assert(result.residencyEvidenceValid)
    assert(result.liveEnable)
    assert(!result.blockedByNoEvidence)
  }

  test("reports disabled and flush blockers without enabling live writes") {
    val disabled = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      insertCandidateValid = true,
      insertValid = true,
      selectedPipeOccupied = false)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.liveEnable)

    val flushed = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      insertCandidateValid = true,
      insertValid = true,
      selectedPipeOccupied = false)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.liveEnable)
  }

  test("reports missing insert and occupied-pipe blockers for active requests") {
    val noCandidate = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      insertCandidateValid = false,
      insertValid = false,
      selectedPipeOccupied = false)
    assert(noCandidate.blockedByNoInsertCandidate)
    assert(noCandidate.blockedByNoEvidence)
    assert(!noCandidate.liveEnable)

    val noInsert = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      insertCandidateValid = true,
      insertValid = false,
      selectedPipeOccupied = false)
    assert(noInsert.blockedByNoInsertValid)
    assert(noInsert.blockedByNoEvidence)
    assert(!noInsert.liveEnable)

    val occupied = LoadReplayReturnPipeResidencyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      insertCandidateValid = true,
      insertValid = true,
      selectedPipeOccupied = true)
    assert(occupied.blockedByPipeOccupied)
    assert(occupied.blockedByNoEvidence)
    assert(!occupied.liveEnable)
  }

  test("Chisel LoadReplayReturnPipeResidencyLiveControl elaborates live-control diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeResidencyLiveControl)

    assert(sv.contains("module LoadReplayReturnPipeResidencyLiveControl"))
    assert(sv.contains("io_residencyEvidenceValid"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_blockedByRequestDisabled"))
    assert(sv.contains("io_blockedByPipeOccupied"))
  }
}
