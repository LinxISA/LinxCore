package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicyReference {
  final case class Result(
      active: Boolean,
      recordCandidate: Boolean,
      allFallbackCandidatesReady: Boolean,
      anyPhysicalDuplicate: Boolean,
      retainedSoleOwnerEligible: Boolean,
      sideEffectOwnerEnable: Boolean,
      blockedByNoRecord: Boolean,
      blockedByMissingFallbackCandidate: Boolean,
      blockedByPhysicalDuplicate: Boolean,
      blockedByGlobalFallbackDisabled: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      globalFallbackEnable: Boolean,
      recordValid: Boolean,
      robCandidate: Boolean,
      rfCandidate: Boolean,
      wakeupCandidate: Boolean,
      lifecycleClearCandidate: Boolean,
      robDuplicatePhysicalComplete: Boolean,
      rfDuplicatePhysicalWriteback: Boolean,
      wakeupDuplicatePhysicalWakeup: Boolean,
      lifecycleClearDuplicatePhysicalClear: Boolean): Result = {
    val active = enable && !flush
    val recordCandidate = active && recordValid
    val allReady =
      recordCandidate && robCandidate && rfCandidate && wakeupCandidate && lifecycleClearCandidate
    val anyDuplicate =
      allReady &&
        (robDuplicatePhysicalComplete ||
          rfDuplicatePhysicalWriteback ||
          wakeupDuplicatePhysicalWakeup ||
          lifecycleClearDuplicatePhysicalClear)
    val eligible = allReady && !anyDuplicate

    Result(
      active = active,
      recordCandidate = recordCandidate,
      allFallbackCandidatesReady = allReady,
      anyPhysicalDuplicate = anyDuplicate,
      retainedSoleOwnerEligible = eligible,
      sideEffectOwnerEnable = globalFallbackEnable && eligible,
      blockedByNoRecord = active && !recordValid,
      blockedByMissingFallbackCandidate =
        recordCandidate && !(robCandidate && rfCandidate && wakeupCandidate && lifecycleClearCandidate),
      blockedByPhysicalDuplicate = anyDuplicate,
      blockedByGlobalFallbackDisabled = eligible && !globalFallbackEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicyReference._

  test("enables retained side-effect ownership only when all fallback candidates are ready and unique") {
    val result = step(
      enable = true,
      flush = false,
      globalFallbackEnable = true,
      recordValid = true,
      robCandidate = true,
      rfCandidate = true,
      wakeupCandidate = true,
      lifecycleClearCandidate = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false)

    assert(result.allFallbackCandidatesReady)
    assert(result.retainedSoleOwnerEligible)
    assert(result.sideEffectOwnerEnable)
    assert(!result.blockedByPhysicalDuplicate)
  }

  test("blocks retained side-effect ownership when any physical duplicate is present") {
    val result = step(
      enable = true,
      flush = false,
      globalFallbackEnable = true,
      recordValid = true,
      robCandidate = true,
      rfCandidate = true,
      wakeupCandidate = true,
      lifecycleClearCandidate = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(result.allFallbackCandidatesReady)
    assert(result.anyPhysicalDuplicate)
    assert(result.blockedByPhysicalDuplicate)
    assert(!result.retainedSoleOwnerEligible)
    assert(!result.sideEffectOwnerEnable)
  }

  test("reports missing candidate and global disabled blockers separately") {
    val missingCandidate = step(
      enable = true,
      flush = false,
      globalFallbackEnable = true,
      recordValid = true,
      robCandidate = true,
      rfCandidate = false,
      wakeupCandidate = true,
      lifecycleClearCandidate = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false)
    val disabled = step(
      enable = true,
      flush = false,
      globalFallbackEnable = false,
      recordValid = true,
      robCandidate = true,
      rfCandidate = true,
      wakeupCandidate = true,
      lifecycleClearCandidate = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false)

    assert(missingCandidate.blockedByMissingFallbackCandidate)
    assert(!missingCandidate.allFallbackCandidatesReady)
    assert(disabled.retainedSoleOwnerEligible)
    assert(disabled.blockedByGlobalFallbackDisabled)
    assert(!disabled.sideEffectOwnerEnable)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy"))
    assert(sv.contains("io_sideEffectOwnerEnable"))
    assert(sv.contains("io_blockedByPhysicalDuplicate"))
  }
}
