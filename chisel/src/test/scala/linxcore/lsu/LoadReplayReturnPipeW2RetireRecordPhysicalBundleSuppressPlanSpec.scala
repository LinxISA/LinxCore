package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanReference {
  final case class Result(
      active: Boolean,
      transferCandidateSeen: Boolean,
      suppressionRequired: Boolean,
      physicalBundleComplete: Boolean,
      atomicSuppressCandidate: Boolean,
      suppressRobComplete: Boolean,
      suppressRfWriteback: Boolean,
      suppressWakeup: Boolean,
      suppressLifecycleClear: Boolean,
      suppressMask: Int,
      allOrNoneSuppress: Boolean,
      blockedByNoTransferCandidate: Boolean,
      blockedByNoSuppressionRequirement: Boolean,
      blockedByIncompletePhysicalBundle: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      transferCandidate: Boolean,
      requiresPhysicalBundleSuppression: Boolean,
      robDuplicatePhysicalComplete: Boolean,
      rfDuplicatePhysicalWriteback: Boolean,
      wakeupDuplicatePhysicalWakeup: Boolean,
      lifecycleClearDuplicatePhysicalClear: Boolean): Result = {
    val active = enable && !flush
    val transferSeen = active && transferCandidate
    val suppressionRequired = transferSeen && requiresPhysicalBundleSuppression
    val bundleComplete =
      suppressionRequired &&
        robDuplicatePhysicalComplete &&
        rfDuplicatePhysicalWriteback &&
        wakeupDuplicatePhysicalWakeup &&
        lifecycleClearDuplicatePhysicalClear
    val atomicCandidate = suppressionRequired && bundleComplete
    val mask = if (atomicCandidate) 0xf else 0

    Result(
      active = active,
      transferCandidateSeen = transferSeen,
      suppressionRequired = suppressionRequired,
      physicalBundleComplete = bundleComplete,
      atomicSuppressCandidate = atomicCandidate,
      suppressRobComplete = atomicCandidate,
      suppressRfWriteback = atomicCandidate,
      suppressWakeup = atomicCandidate,
      suppressLifecycleClear = atomicCandidate,
      suppressMask = mask,
      allOrNoneSuppress = mask == 0 || mask == 0xf,
      blockedByNoTransferCandidate = active && !transferCandidate,
      blockedByNoSuppressionRequirement = transferSeen && !requiresPhysicalBundleSuppression,
      blockedByIncompletePhysicalBundle = suppressionRequired && !bundleComplete)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlanReference._

  test("emits all suppression intents for a complete physical bundle") {
    val result = step(
      enable = true,
      flush = false,
      transferCandidate = true,
      requiresPhysicalBundleSuppression = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(result.transferCandidateSeen)
    assert(result.suppressionRequired)
    assert(result.physicalBundleComplete)
    assert(result.atomicSuppressCandidate)
    assert(result.suppressRobComplete)
    assert(result.suppressRfWriteback)
    assert(result.suppressWakeup)
    assert(result.suppressLifecycleClear)
    assert(result.suppressMask == 0xf)
    assert(result.allOrNoneSuppress)
  }

  test("emits no partial suppression when one physical duplicate is missing") {
    val result = step(
      enable = true,
      flush = false,
      transferCandidate = true,
      requiresPhysicalBundleSuppression = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(!result.physicalBundleComplete)
    assert(!result.atomicSuppressCandidate)
    assert(result.suppressMask == 0)
    assert(result.allOrNoneSuppress)
    assert(result.blockedByIncompletePhysicalBundle)
  }

  test("reports transfer and suppression-requirement blockers separately") {
    val noTransfer = step(
      enable = true,
      flush = false,
      transferCandidate = false,
      requiresPhysicalBundleSuppression = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)
    val noRequirement = step(
      enable = true,
      flush = false,
      transferCandidate = true,
      requiresPhysicalBundleSuppression = false,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(noTransfer.blockedByNoTransferCandidate)
    assert(!noTransfer.atomicSuppressCandidate)
    assert(noRequirement.blockedByNoSuppressionRequirement)
    assert(!noRequirement.atomicSuppressCandidate)
  }

  test("flush suppresses the physical bundle plan") {
    val result = step(
      enable = true,
      flush = true,
      transferCandidate = true,
      requiresPhysicalBundleSuppression = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(!result.active)
    assert(!result.transferCandidateSeen)
    assert(!result.suppressionRequired)
    assert(!result.atomicSuppressCandidate)
    assert(result.suppressMask == 0)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressPlan"))
    assert(sv.contains("io_atomicSuppressCandidate"))
    assert(sv.contains("io_suppressMask"))
  }
}
