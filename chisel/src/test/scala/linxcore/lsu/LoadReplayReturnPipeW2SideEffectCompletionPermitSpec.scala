package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectCompletionPermitReference {
  final case class Result(
      candidateValid: Boolean,
      requiredMask: Int,
      sinkReadyMask: Int,
      missingReadyMask: Int,
      allRequiredSinksReady: Boolean,
      completionPermitted: Boolean,
      matchesReadyJoin: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByNoRequiredSink: Boolean,
      blockedByResolveSink: Boolean,
      blockedByWritebackSink: Boolean,
      blockedByWakeupSink: Boolean,
      blockedByReadyJoinMismatch: Boolean)

  def mask(resolve: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    (if (wakeup) 4 else 0) |
      (if (writeback) 2 else 0) |
      (if (resolve) 1 else 0)

  def apply(
      enable: Boolean,
      flush: Boolean,
      sideEffectCandidateValid: Boolean,
      resolveRequired: Boolean,
      writebackRequired: Boolean,
      wakeupRequired: Boolean,
      resolveSinkReady: Boolean,
      writebackSinkReady: Boolean,
      wakeupSinkReady: Boolean,
      readyJoinSideEffectsReady: Boolean): Result = {
    val active = enable && !flush
    val candidateValid = active && sideEffectCandidateValid
    val requiredMask = mask(resolveRequired, writebackRequired, wakeupRequired)
    val hasRequiredSink = (requiredMask & 0x7) != 0
    val resolveReady = !resolveRequired || resolveSinkReady
    val writebackReady = !writebackRequired || writebackSinkReady
    val wakeupReady = !wakeupRequired || wakeupSinkReady
    val allRequiredSinksReady = resolveReady && writebackReady && wakeupReady
    val completionPermitted = candidateValid && hasRequiredSink && allRequiredSinksReady
    val matchesReadyJoin = readyJoinSideEffectsReady == completionPermitted
    val missingResolve = candidateValid && resolveRequired && !resolveSinkReady
    val missingWriteback = candidateValid && writebackRequired && !writebackSinkReady
    val missingWakeup = candidateValid && wakeupRequired && !wakeupSinkReady

    Result(
      candidateValid = candidateValid,
      requiredMask = requiredMask,
      sinkReadyMask = mask(resolveSinkReady, writebackSinkReady, wakeupSinkReady),
      missingReadyMask = mask(missingResolve, missingWriteback, missingWakeup),
      allRequiredSinksReady = allRequiredSinksReady,
      completionPermitted = completionPermitted,
      matchesReadyJoin = matchesReadyJoin,
      blockedByDisabled = !enable && sideEffectCandidateValid,
      blockedByFlush = enable && flush && sideEffectCandidateValid,
      blockedByNoCandidate = active && !sideEffectCandidateValid,
      blockedByNoRequiredSink = candidateValid && !hasRequiredSink,
      blockedByResolveSink = missingResolve,
      blockedByWritebackSink = missingWriteback,
      blockedByWakeupSink = missingWakeup,
      blockedByReadyJoinMismatch = !matchesReadyJoin)
  }
}

class LoadReplayReturnPipeW2SideEffectCompletionPermitSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectCompletionPermitReference._

  test("permits W2 completion when every required side-effect sink is ready") {
    val result = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = true)

    assert(result.candidateValid)
    assert(result.completionPermitted)
    assert(result.allRequiredSinksReady)
    assert(result.matchesReadyJoin)
    assert(result.requiredMask == 0x7)
    assert(result.missingReadyMask == 0x0)
  }

  test("permits resolve-only completion without optional sink readiness") {
    val result = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false,
      readyJoinSideEffectsReady = true)

    assert(result.completionPermitted)
    assert(result.requiredMask == 0x1)
    assert(!result.blockedByWritebackSink)
    assert(!result.blockedByWakeupSink)
  }

  test("reports each missing required sink before completion clear") {
    val result = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false,
      readyJoinSideEffectsReady = false)

    assert(result.candidateValid)
    assert(!result.completionPermitted)
    assert(!result.allRequiredSinksReady)
    assert(result.blockedByWritebackSink)
    assert(result.blockedByWakeupSink)
    assert(!result.blockedByResolveSink)
    assert(result.missingReadyMask == 0x6)
    assert(result.matchesReadyJoin)
  }

  test("stays dormant on disabled flush and no-candidate conditions") {
    val disabled = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = false,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = false)
    val flushed = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = true,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = false)
    val empty = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = false,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.completionPermitted)
    assert(flushed.blockedByFlush)
    assert(!flushed.completionPermitted)
    assert(empty.blockedByNoCandidate)
    assert(!empty.completionPermitted)
  }

  test("flags a legal candidate with no required side-effect mask") {
    val result = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = false,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = false)

    assert(result.candidateValid)
    assert(!result.completionPermitted)
    assert(result.blockedByNoRequiredSink)
    assert(result.matchesReadyJoin)
  }

  test("detects divergence from the existing side-effect readiness join") {
    val unexpectedlyReady = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = true,
      readyJoinSideEffectsReady = true)
    val unexpectedlyBlocked = LoadReplayReturnPipeW2SideEffectCompletionPermitReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false,
      readyJoinSideEffectsReady = false)

    assert(!unexpectedlyReady.completionPermitted)
    assert(unexpectedlyReady.blockedByReadyJoinMismatch)
    assert(unexpectedlyBlocked.completionPermitted)
    assert(unexpectedlyBlocked.blockedByReadyJoinMismatch)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectCompletionPermit elaborates pre-clear diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectCompletionPermit)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectCompletionPermit"))
    assert(sv.contains("io_sideEffectCandidateValid"))
    assert(sv.contains("io_requiredMask"))
    assert(sv.contains("io_missingReadyMask"))
    assert(sv.contains("io_completionPermitted"))
    assert(sv.contains("io_matchesReadyJoin"))
  }
}
