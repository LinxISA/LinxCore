package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessReference {
  final case class Result(
      active: Boolean,
      recordCandidate: Boolean,
      preArmModelOrderReady: Boolean,
      anyPhysicalDuplicate: Boolean,
      defaultPromotionReady: Boolean,
      blockedByNoRecord: Boolean,
      blockedByMissingFallbackCandidate: Boolean,
      blockedByMissingLifecycleEvidence: Boolean,
      blockedByPhysicalDuplicate: Boolean,
      blockedByProbeActive: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      recordValid: Boolean,
      fallbackCandidatesReady: Boolean,
      lifecycleEvidenceProviderValid: Boolean,
      robDuplicatePhysicalComplete: Boolean,
      rfDuplicatePhysicalWriteback: Boolean,
      wakeupDuplicatePhysicalWakeup: Boolean,
      lifecycleClearDuplicatePhysicalClear: Boolean,
      probeActive: Boolean): Result = {
    val active = enable && !flush
    val recordCandidate = active && recordValid
    val preArmReady = fallbackCandidatesReady && lifecycleEvidenceProviderValid
    val duplicate =
      preArmReady &&
        (robDuplicatePhysicalComplete ||
          rfDuplicatePhysicalWriteback ||
          wakeupDuplicatePhysicalWakeup ||
          lifecycleClearDuplicatePhysicalClear)
    val withoutDuplicate = preArmReady && !duplicate

    Result(
      active = active,
      recordCandidate = recordCandidate,
      preArmModelOrderReady = preArmReady,
      anyPhysicalDuplicate = duplicate,
      defaultPromotionReady = withoutDuplicate && !probeActive,
      blockedByNoRecord = active && !recordValid,
      blockedByMissingFallbackCandidate = recordCandidate && !fallbackCandidatesReady,
      blockedByMissingLifecycleEvidence =
        fallbackCandidatesReady && !lifecycleEvidenceProviderValid,
      blockedByPhysicalDuplicate = duplicate,
      blockedByProbeActive = withoutDuplicate && probeActive)
  }
}

class LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessReference._

  test("allows default promotion only with pre-arm order evidence and no duplicate or probe") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      fallbackCandidatesReady = true,
      lifecycleEvidenceProviderValid = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)

    assert(result.recordCandidate)
    assert(result.preArmModelOrderReady)
    assert(!result.anyPhysicalDuplicate)
    assert(result.defaultPromotionReady)
  }

  test("blocks default promotion when a real physical duplicate owns the side effects") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      fallbackCandidatesReady = true,
      lifecycleEvidenceProviderValid = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)

    assert(result.preArmModelOrderReady)
    assert(result.anyPhysicalDuplicate)
    assert(result.blockedByPhysicalDuplicate)
    assert(!result.defaultPromotionReady)
  }

  test("blocks probe-only evidence from satisfying the default-promotion gate") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      fallbackCandidatesReady = true,
      lifecycleEvidenceProviderValid = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = true)

    assert(result.preArmModelOrderReady)
    assert(result.blockedByProbeActive)
    assert(!result.defaultPromotionReady)
  }

  test("reports missing record, fallback candidates, and lifecycle evidence separately") {
    val noRecord = step(
      enable = true,
      flush = false,
      recordValid = false,
      fallbackCandidatesReady = false,
      lifecycleEvidenceProviderValid = false,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)
    val missingCandidates = step(
      enable = true,
      flush = false,
      recordValid = true,
      fallbackCandidatesReady = false,
      lifecycleEvidenceProviderValid = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)
    val missingLifecycle = step(
      enable = true,
      flush = false,
      recordValid = true,
      fallbackCandidatesReady = true,
      lifecycleEvidenceProviderValid = false,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)

    assert(noRecord.blockedByNoRecord)
    assert(missingCandidates.blockedByMissingFallbackCandidate)
    assert(missingLifecycle.blockedByMissingLifecycleEvidence)
  }

  test("flush suppresses the default-promotion candidate") {
    val result = step(
      enable = true,
      flush = true,
      recordValid = true,
      fallbackCandidatesReady = true,
      lifecycleEvidenceProviderValid = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false,
      probeActive = false)

    assert(!result.active)
    assert(!result.recordCandidate)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness"))
    assert(sv.contains("io_defaultPromotionReady"))
    assert(sv.contains("io_blockedByProbeActive"))
  }
}
