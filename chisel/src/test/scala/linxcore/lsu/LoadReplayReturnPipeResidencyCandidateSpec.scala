package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeResidencyCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      residencyArmed: Boolean,
      residencyWriteValid: Boolean,
      liveEnabled: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      targetPipeIndex: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoInsertCandidate: Boolean,
      blockedByNoInsertValid: Boolean,
      blockedByLiveDisabled: Boolean,
      blockedByPipeOccupied: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      insertCandidateValid: Boolean,
      insertValid: Boolean,
      liveEnable: Boolean,
      isVectorMachine: Boolean,
      selectedPipeIndex: Int,
      selectedPipeOccupied: Boolean): Result = {
    val candidateValid = enable && !flush && insertCandidateValid
    val residencyArmed = candidateValid && insertValid
    val pipeWritable = residencyArmed && !selectedPipeOccupied
    Result(
      candidateValid = candidateValid,
      residencyArmed = residencyArmed,
      residencyWriteValid = pipeWritable && liveEnable,
      liveEnabled = liveEnable,
      targetIsAgu = candidateValid && isVectorMachine,
      targetIsLda = candidateValid && !isVectorMachine,
      targetPipeIndex = if (candidateValid) selectedPipeIndex else 0,
      blockedByDisabled = !enable && insertCandidateValid,
      blockedByFlush = enable && flush && insertCandidateValid,
      blockedByNoInsertCandidate = enable && !flush && !insertCandidateValid,
      blockedByNoInsertValid = candidateValid && !insertValid,
      blockedByLiveDisabled = pipeWritable && !liveEnable,
      blockedByPipeOccupied = candidateValid && selectedPipeOccupied)
  }
}

class LoadReplayReturnPipeResidencyCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencyCandidateReference._

  test("arms scalar LDA residency after an accepted insert while live writes are disabled") {
    val result = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = false,
      insertCandidateValid = true,
      insertValid = true,
      liveEnable = false,
      isVectorMachine = false,
      selectedPipeIndex = 1,
      selectedPipeOccupied = false)

    assert(result.candidateValid)
    assert(result.residencyArmed)
    assert(!result.residencyWriteValid)
    assert(!result.liveEnabled)
    assert(!result.targetIsAgu)
    assert(result.targetIsLda)
    assert(result.targetPipeIndex == 1)
    assert(result.blockedByLiveDisabled)
  }

  test("routes vector IEX returns to AGU residency when live writes are enabled") {
    val result = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = false,
      insertCandidateValid = true,
      insertValid = true,
      liveEnable = true,
      isVectorMachine = true,
      selectedPipeIndex = 0,
      selectedPipeOccupied = false)

    assert(result.candidateValid)
    assert(result.residencyArmed)
    assert(result.residencyWriteValid)
    assert(result.liveEnabled)
    assert(result.targetIsAgu)
    assert(!result.targetIsLda)
  }

  test("reports disabled flush and missing insert-candidate blockers") {
    val disabled = LoadReplayReturnPipeResidencyCandidateReference(
      enable = false,
      flush = false,
      insertCandidateValid = true,
      insertValid = true,
      liveEnable = true,
      isVectorMachine = false,
      selectedPipeIndex = 0,
      selectedPipeOccupied = false)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = true,
      insertCandidateValid = true,
      insertValid = true,
      liveEnable = true,
      isVectorMachine = false,
      selectedPipeIndex = 0,
      selectedPipeOccupied = false)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val missingCandidate = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = false,
      insertCandidateValid = false,
      insertValid = false,
      liveEnable = true,
      isVectorMachine = false,
      selectedPipeIndex = 1,
      selectedPipeOccupied = false)
    assert(missingCandidate.blockedByNoInsertCandidate)
    assert(!missingCandidate.candidateValid)
    assert(missingCandidate.targetPipeIndex == 0)
  }

  test("reports unaccepted insert and occupied-pipe blockers") {
    val noInsert = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = false,
      insertCandidateValid = true,
      insertValid = false,
      liveEnable = true,
      isVectorMachine = false,
      selectedPipeIndex = 0,
      selectedPipeOccupied = false)
    assert(noInsert.candidateValid)
    assert(!noInsert.residencyArmed)
    assert(noInsert.blockedByNoInsertValid)
    assert(!noInsert.residencyWriteValid)

    val occupied = LoadReplayReturnPipeResidencyCandidateReference(
      enable = true,
      flush = false,
      insertCandidateValid = true,
      insertValid = true,
      liveEnable = true,
      isVectorMachine = true,
      selectedPipeIndex = 1,
      selectedPipeOccupied = true)
    assert(occupied.candidateValid)
    assert(occupied.residencyArmed)
    assert(occupied.blockedByPipeOccupied)
    assert(!occupied.residencyWriteValid)
  }

  test("Chisel LoadReplayReturnPipeResidencyCandidate elaborates residency diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeResidencyCandidate(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeResidencyCandidate"))
    assert(sv.contains("io_residencyWriteValid"))
    assert(sv.contains("io_targetIsAgu"))
    assert(sv.contains("io_blockedByPipeOccupied"))
  }
}
