package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeReference {
  final case class Result(
      active: Boolean,
      suppressCandidate: Boolean,
      probeEnabledCandidate: Boolean,
      selected: Boolean,
      selectedMask: Int,
      allOrNoneInputMask: Boolean,
      blockedByProbeDisabled: Boolean,
      blockedByNoAtomicCandidate: Boolean,
      blockedByPartialMask: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      probeEnable: Boolean,
      atomicSuppressCandidate: Boolean,
      suppressRobComplete: Boolean,
      suppressRfWriteback: Boolean,
      suppressWakeup: Boolean,
      suppressLifecycleClear: Boolean): Result = {
    val active = enable && !flush
    val mask =
      (if (suppressLifecycleClear) 8 else 0) |
        (if (suppressWakeup) 4 else 0) |
        (if (suppressRfWriteback) 2 else 0) |
        (if (suppressRobComplete) 1 else 0)
    val allOrNone = mask == 0 || mask == 0xf
    val candidate = active && atomicSuppressCandidate
    val enabledCandidate = candidate && probeEnable
    val selected = enabledCandidate && allOrNone && mask == 0xf

    Result(
      active = active,
      suppressCandidate = candidate,
      probeEnabledCandidate = enabledCandidate,
      selected = selected,
      selectedMask = if (selected) mask else 0,
      allOrNoneInputMask = allOrNone,
      blockedByProbeDisabled = candidate && !probeEnable,
      blockedByNoAtomicCandidate = active && probeEnable && !atomicSuppressCandidate,
      blockedByPartialMask = enabledCandidate && !allOrNone)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeReference._

  test("selects the full physical suppression bundle when the probe is enabled") {
    val result = step(
      enable = true,
      flush = false,
      probeEnable = true,
      atomicSuppressCandidate = true,
      suppressRobComplete = true,
      suppressRfWriteback = true,
      suppressWakeup = true,
      suppressLifecycleClear = true)

    assert(result.suppressCandidate)
    assert(result.probeEnabledCandidate)
    assert(result.selected)
    assert(result.selectedMask == 0xf)
    assert(result.allOrNoneInputMask)
  }

  test("reports a disabled probe without selecting the suppress mask") {
    val result = step(
      enable = true,
      flush = false,
      probeEnable = false,
      atomicSuppressCandidate = true,
      suppressRobComplete = true,
      suppressRfWriteback = true,
      suppressWakeup = true,
      suppressLifecycleClear = true)

    assert(result.suppressCandidate)
    assert(!result.probeEnabledCandidate)
    assert(!result.selected)
    assert(result.selectedMask == 0)
    assert(result.blockedByProbeDisabled)
  }

  test("rejects a partial suppression mask") {
    val result = step(
      enable = true,
      flush = false,
      probeEnable = true,
      atomicSuppressCandidate = true,
      suppressRobComplete = true,
      suppressRfWriteback = true,
      suppressWakeup = false,
      suppressLifecycleClear = true)

    assert(result.probeEnabledCandidate)
    assert(!result.selected)
    assert(!result.allOrNoneInputMask)
    assert(result.blockedByPartialMask)
  }

  test("reports missing atomic candidate separately from disabled probe") {
    val result = step(
      enable = true,
      flush = false,
      probeEnable = true,
      atomicSuppressCandidate = false,
      suppressRobComplete = true,
      suppressRfWriteback = true,
      suppressWakeup = true,
      suppressLifecycleClear = true)

    assert(!result.suppressCandidate)
    assert(!result.selected)
    assert(result.blockedByNoAtomicCandidate)
    assert(!result.blockedByProbeDisabled)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe"))
    assert(sv.contains("io_selected"))
    assert(sv.contains("io_selectedMask"))
  }
}
