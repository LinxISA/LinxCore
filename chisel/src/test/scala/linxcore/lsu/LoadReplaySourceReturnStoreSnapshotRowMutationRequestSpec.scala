package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference {
  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      candidateTargetMask: Int,
      candidateTargetCount: Int,
      candidateTargetIndex: Int,
      targetReady: Boolean,
      requestValid: Boolean,
      requestTargetMask: Int,
      requestTargetIndex: Int,
      statusWrite: Boolean,
      setWaitStatus: Boolean,
      keepRepickStatus: Boolean,
      returnStateWrite: Boolean,
      clearReturnState: Boolean,
      lineWrite: Boolean,
      waitStoreWrite: Boolean,
      nextWaitStore: Boolean,
      nextLineData: BigInt,
      nextValidMask: BigInt,
      nextDataComplete: Boolean,
      nextScbReturned: Boolean,
      nextStqReturned: Boolean,
      nextStoreSourceReturned: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoPlan: Boolean,
      blockedByNoTarget: Boolean,
      blockedByLiveDisabled: Boolean,
      invalidMultiTarget: Boolean,
      invalidWriteWithoutPlan: Boolean,
      invalidWaitStoreWithoutWaitStatus: Boolean,
      invalidReturnWithoutSplitSources: Boolean,
      invalidConflictingStatusWrite: Boolean)

  private def firstSet(mask: Int): Int =
    if (mask == 0) 0 else Integer.numberOfTrailingZeros(mask)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      planValid: Boolean,
      targetMask: Int,
      setWaitStatus: Boolean = false,
      keepRepickStatus: Boolean = false,
      clearReturnState: Boolean = false,
      lineWrite: Boolean = false,
      waitStoreWrite: Boolean = false,
      nextWaitStore: Boolean = false,
      nextLineData: BigInt = 0,
      nextValidMask: BigInt = 0,
      nextDataComplete: Boolean = false,
      nextScbReturned: Boolean = false,
      nextStqReturned: Boolean = false,
      nextStoreSourceReturned: Boolean = false): Result = {
    val active = enable && !flush
    val mutationIntent =
      planValid || targetMask != 0 || setWaitStatus || keepRepickStatus || clearReturnState || lineWrite || waitStoreWrite
    val candidateValid = active && planValid
    val targetCount = Integer.bitCount(targetMask)
    val oneTarget = targetCount == 1
    val targetReady = candidateValid && targetMask != 0 && oneTarget
    val requestValid = targetReady && liveEnable

    Result(
      active = active,
      candidateValid = candidateValid,
      candidateTargetMask = if (candidateValid) targetMask else 0,
      candidateTargetCount = if (candidateValid) targetCount else 0,
      candidateTargetIndex = if (targetReady) firstSet(targetMask) else 0,
      targetReady = targetReady,
      requestValid = requestValid,
      requestTargetMask = if (requestValid) targetMask else 0,
      requestTargetIndex = if (requestValid) firstSet(targetMask) else 0,
      statusWrite = requestValid && (setWaitStatus || keepRepickStatus),
      setWaitStatus = requestValid && setWaitStatus,
      keepRepickStatus = requestValid && keepRepickStatus,
      returnStateWrite = requestValid,
      clearReturnState = requestValid && clearReturnState,
      lineWrite = requestValid && lineWrite,
      waitStoreWrite = requestValid && waitStoreWrite,
      nextWaitStore = requestValid && nextWaitStore,
      nextLineData = if (requestValid) nextLineData else 0,
      nextValidMask = if (requestValid) nextValidMask else 0,
      nextDataComplete = requestValid && nextDataComplete,
      nextScbReturned = requestValid && nextScbReturned,
      nextStqReturned = requestValid && nextStqReturned,
      nextStoreSourceReturned = requestValid && nextStoreSourceReturned,
      blockedByDisabled = !enable && mutationIntent,
      blockedByFlush = enable && flush && mutationIntent,
      blockedByNoPlan = active && mutationIntent && !planValid,
      blockedByNoTarget = candidateValid && targetMask == 0,
      blockedByLiveDisabled = targetReady && !liveEnable,
      invalidMultiTarget = candidateValid && targetCount > 1,
      invalidWriteWithoutPlan = active && !planValid && mutationIntent,
      invalidWaitStoreWithoutWaitStatus = candidateValid && nextWaitStore && !setWaitStatus,
      invalidReturnWithoutSplitSources =
        candidateValid && nextStoreSourceReturned && !(nextScbReturned && nextStqReturned),
      invalidConflictingStatusWrite = candidateValid && setWaitStatus && keepRepickStatus)
  }
}

class LoadReplaySourceReturnStoreSnapshotRowMutationRequestSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference._

  test("live one-hot wait-store plan produces a row mutation request") {
    val result = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x4,
      setWaitStatus = true,
      clearReturnState = true,
      lineWrite = true,
      waitStoreWrite = true,
      nextWaitStore = true)

    assert(result.candidateValid)
    assert(result.candidateTargetMask == 0x4)
    assert(result.candidateTargetCount == 1)
    assert(result.candidateTargetIndex == 2)
    assert(result.targetReady)
    assert(result.requestValid)
    assert(result.requestTargetMask == 0x4)
    assert(result.requestTargetIndex == 2)
    assert(result.statusWrite)
    assert(result.setWaitStatus)
    assert(result.returnStateWrite)
    assert(result.clearReturnState)
    assert(result.lineWrite)
    assert(result.waitStoreWrite)
    assert(result.nextWaitStore)
  }

  test("live-disabled one-hot plan remains a candidate without emitting a request") {
    val result = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = false,
      planValid = true,
      targetMask = 0x2,
      keepRepickStatus = true,
      waitStoreWrite = true,
      nextLineData = BigInt("11223344", 16),
      nextValidMask = BigInt("ff", 16),
      nextDataComplete = true,
      nextScbReturned = true,
      nextStqReturned = true,
      nextStoreSourceReturned = true)

    assert(result.candidateValid)
    assert(result.targetReady)
    assert(result.blockedByLiveDisabled)
    assert(!result.requestValid)
    assert(result.requestTargetMask == 0)
    assert(!result.returnStateWrite)
    assert(result.nextLineData == 0)
    assert(!result.nextStoreSourceReturned)
  }

  test("requires exactly one target before the mutation request can fire") {
    val noTarget = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0,
      keepRepickStatus = true)
    val multiTarget = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x5,
      keepRepickStatus = true)

    assert(noTarget.blockedByNoTarget)
    assert(!noTarget.targetReady)
    assert(!noTarget.requestValid)
    assert(multiTarget.invalidMultiTarget)
    assert(multiTarget.candidateTargetCount == 2)
    assert(!multiTarget.targetReady)
    assert(!multiTarget.requestValid)
  }

  test("reports disabled flush and no-plan mutation intents") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = false,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x1)
    val flushed = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = true,
      liveEnable = true,
      planValid = true,
      targetMask = 0x1)
    val noPlan = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = false,
      targetMask = 0x1,
      lineWrite = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noPlan.blockedByNoPlan)
    assert(noPlan.invalidWriteWithoutPlan)
    assert(!disabled.requestValid)
    assert(!flushed.requestValid)
    assert(!noPlan.requestValid)
  }

  test("reports impossible row-plan payload combinations") {
    val waitWithoutStatus = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x1,
      keepRepickStatus = true,
      nextWaitStore = true)
    val returnWithoutSplit = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x1,
      keepRepickStatus = true,
      nextScbReturned = true,
      nextStqReturned = false,
      nextStoreSourceReturned = true)
    val conflictingStatus = LoadReplaySourceReturnStoreSnapshotRowMutationRequestReference(
      enable = true,
      flush = false,
      liveEnable = true,
      planValid = true,
      targetMask = 0x1,
      setWaitStatus = true,
      keepRepickStatus = true)

    assert(waitWithoutStatus.invalidWaitStoreWithoutWaitStatus)
    assert(returnWithoutSplit.invalidReturnWithoutSplitSources)
    assert(conflictingStatus.invalidConflictingStatusWrite)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRowMutationRequest elaborates row mutation outputs") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRowMutationRequest)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRowMutationRequest"))
    assert(sv.contains("io_candidateTargetMask"))
    assert(sv.contains("io_requestTargetIndex"))
    assert(sv.contains("io_nextStqReturnedOut"))
    assert(sv.contains("io_blockedByLiveDisabled"))
    assert(sv.contains("io_invalidMultiTarget"))
  }
}
