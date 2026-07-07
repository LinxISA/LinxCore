package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundaryReference {
  final case class Id(valid: Boolean, wrap: Boolean, value: Int)
  final case class State(
      capturedValid: Boolean = false,
      rid: Id = Id(valid = false, wrap = false, value = 0),
      loadLsId: Id = Id(valid = false, wrap = false, value = 0),
      lifecycleReady: Boolean = false,
      lifecycleIndex: Int = 0)
  final case class Result(
      captureOwnership: Boolean,
      capturedOwnershipValid: Boolean,
      registeredCandidate: Boolean,
      registeredRidValid: Boolean,
      registeredLoadLsIdValid: Boolean,
      registeredOwnershipBundleReady: Boolean,
      eligibleRegisteredMask: Boolean,
      blockedByNoCapturedOwnership: Boolean,
      blockedByMissingRid: Boolean,
      blockedByMissingLoadLsId: Boolean,
      blockedByMissingLifecycleRow: Boolean,
      blockedByNotFullMask: Boolean,
      next: State)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      capture: Boolean,
      captureRid: Id,
      captureLoadLsId: Id,
      captureLifecycleRowReady: Boolean,
      captureLifecycleRowIndex: Int,
      registeredValid: Boolean,
      registeredFullMask: Boolean): Result = {
    val active = enable && !flush
    val captureOwnership = active && capture
    val registeredCandidate = active && registeredValid
    val capturedOwnershipValid = active && state.capturedValid
    val registeredRidValid = capturedOwnershipValid && state.rid.valid
    val registeredLoadLsIdValid = capturedOwnershipValid && state.loadLsId.valid
    val registeredOwnershipBundleReady =
      capturedOwnershipValid && registeredRidValid && registeredLoadLsIdValid && state.lifecycleReady
    val next =
      if (!enable || flush) State()
      else if (captureOwnership) {
        State(
          capturedValid = true,
          rid = captureRid,
          loadLsId = captureLoadLsId,
          lifecycleReady = captureLifecycleRowReady,
          lifecycleIndex = captureLifecycleRowIndex)
      } else {
        state
      }

    Result(
      captureOwnership = captureOwnership,
      capturedOwnershipValid = capturedOwnershipValid,
      registeredCandidate = registeredCandidate,
      registeredRidValid = registeredRidValid,
      registeredLoadLsIdValid = registeredLoadLsIdValid,
      registeredOwnershipBundleReady = registeredOwnershipBundleReady,
      eligibleRegisteredMask = registeredCandidate && registeredOwnershipBundleReady && registeredFullMask,
      blockedByNoCapturedOwnership = registeredCandidate && !capturedOwnershipValid,
      blockedByMissingRid = registeredCandidate && capturedOwnershipValid && !state.rid.valid,
      blockedByMissingLoadLsId =
        registeredCandidate && capturedOwnershipValid && state.rid.valid && !state.loadLsId.valid,
      blockedByMissingLifecycleRow =
        registeredCandidate && capturedOwnershipValid && state.rid.valid && state.loadLsId.valid &&
          !state.lifecycleReady,
      blockedByNotFullMask = registeredCandidate && registeredOwnershipBundleReady && !registeredFullMask,
      next = next)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundarySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundaryReference._

  private val rid = Id(valid = true, wrap = false, value = 3)
  private val lsid = Id(valid = true, wrap = false, value = 5)

  test("carries capture-time ownership to the registered mask candidate") {
    val captured = step(
      State(),
      enable = true,
      flush = false,
      capture = true,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = false,
      registeredFullMask = false)
    val registered = step(
      captured.next,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true)

    assert(captured.captureOwnership)
    assert(registered.registeredCandidate)
    assert(registered.registeredRidValid)
    assert(registered.registeredLoadLsIdValid)
    assert(registered.registeredOwnershipBundleReady)
    assert(registered.eligibleRegisteredMask)
  }

  test("reports missing captured ownership before identity blockers") {
    val result = step(
      State(),
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true)

    assert(result.blockedByNoCapturedOwnership)
    assert(!result.blockedByMissingRid)
    assert(!result.eligibleRegisteredMask)
  }

  test("reports missing rid, load-LSID, and lifecycle row separately") {
    val missingRid = step(
      State(capturedValid = true, rid = rid.copy(valid = false), loadLsId = lsid, lifecycleReady = true),
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true)
    val missingLoadLsId = step(
      State(capturedValid = true, rid = rid, loadLsId = lsid.copy(valid = false), lifecycleReady = true),
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true)
    val missingLifecycle = step(
      State(capturedValid = true, rid = rid, loadLsId = lsid, lifecycleReady = false),
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true)

    assert(missingRid.blockedByMissingRid)
    assert(missingLoadLsId.blockedByMissingLoadLsId)
    assert(missingLifecycle.blockedByMissingLifecycleRow)
  }

  test("requires the registered mask to remain full") {
    val result = step(
      State(capturedValid = true, rid = rid, loadLsId = lsid, lifecycleReady = true),
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = false)

    assert(result.registeredOwnershipBundleReady)
    assert(result.blockedByNotFullMask)
    assert(!result.eligibleRegisteredMask)
  }

  test("elaboration exposes ownership boundary IOs") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary(idEntries = 16, liqEntries = 16),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )

    assert(sv.contains("LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressOwnershipBoundary"))
    assert(sv.contains("io_captureOwnership"))
    assert(sv.contains("io_registeredOwnershipBundleReady"))
    assert(sv.contains("io_eligibleRegisteredMask"))
  }
}
