package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnFinalMetadataCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      isLoadReturnMarked: Boolean,
      loadBranchResolveCalled: Boolean,
      loadBranchResolveSideEffectValid: Boolean,
      pipeCycleSidebandValid: Boolean,
      readyForPipeInsert: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoTloadCompletion: Boolean)

  def apply(enable: Boolean, flush: Boolean, tloadCompletionValid: Boolean): Result = {
    val candidateValid = enable && !flush && tloadCompletionValid
    Result(
      candidateValid = candidateValid,
      isLoadReturnMarked = candidateValid,
      loadBranchResolveCalled = candidateValid,
      loadBranchResolveSideEffectValid = false,
      pipeCycleSidebandValid = candidateValid,
      readyForPipeInsert = candidateValid,
      blockedByDisabled = !enable && tloadCompletionValid,
      blockedByFlush = enable && flush && tloadCompletionValid,
      blockedByNoTloadCompletion = enable && !flush && !tloadCompletionValid)
  }
}

class LoadReplayReturnFinalMetadataCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnFinalMetadataCandidateReference._

  test("marks a post-TLOAD-complete candidate as load-return metadata") {
    val result = LoadReplayReturnFinalMetadataCandidateReference(
      enable = true,
      flush = false,
      tloadCompletionValid = true)

    assert(result.candidateValid)
    assert(result.isLoadReturnMarked)
    assert(result.loadBranchResolveCalled)
    assert(!result.loadBranchResolveSideEffectValid)
    assert(result.pipeCycleSidebandValid)
    assert(result.readyForPipeInsert)
  }

  test("reports disabled flush and missing TLOAD-completion blockers") {
    val disabled = LoadReplayReturnFinalMetadataCandidateReference(
      enable = false,
      flush = false,
      tloadCompletionValid = true)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnFinalMetadataCandidateReference(
      enable = true,
      flush = true,
      tloadCompletionValid = true)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val missingTloadCompletion = LoadReplayReturnFinalMetadataCandidateReference(
      enable = true,
      flush = false,
      tloadCompletionValid = false)
    assert(missingTloadCompletion.blockedByNoTloadCompletion)
    assert(!missingTloadCompletion.candidateValid)
  }

  test("Chisel LoadReplayReturnFinalMetadataCandidate elaborates final metadata diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnFinalMetadataCandidate)

    assert(sv.contains("module LoadReplayReturnFinalMetadataCandidate"))
    assert(sv.contains("io_isLoadReturnMarked"))
    assert(sv.contains("io_loadBranchResolveSideEffectValid"))
    assert(sv.contains("io_pipeCycleSidebandValid"))
  }
}
