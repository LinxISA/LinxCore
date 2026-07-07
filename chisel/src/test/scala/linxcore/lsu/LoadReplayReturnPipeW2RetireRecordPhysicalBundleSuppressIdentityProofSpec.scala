package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofReference {
  final case class Id(valid: Boolean, wrap: Boolean, value: Int)
  final case class State(
      capturedValid: Boolean = false,
      rid: Id = Id(valid = false, wrap = false, value = 0),
      loadLsId: Id = Id(valid = false, wrap = false, value = 0),
      lifecycleReady: Boolean = false,
      lifecycleIndex: Int = 0)
  final case class Result(
      captureIdentity: Boolean,
      capturedIdentityValid: Boolean,
      registeredCandidate: Boolean,
      retainedRecordAligned: Boolean,
      lifecycleRowAligned: Boolean,
      identityLifetimeAligned: Boolean,
      eligibleRegisteredMask: Boolean,
      blockedByNoCapturedIdentity: Boolean,
      blockedByMissingRecord: Boolean,
      blockedByRidMismatch: Boolean,
      blockedByLoadLsIdMismatch: Boolean,
      blockedByMissingLifecycleEvidence: Boolean,
      blockedByLifecycleRowMismatch: Boolean,
      blockedByNotFullMask: Boolean,
      next: State)

  private def same(lhs: Id, rhs: Id): Boolean =
    lhs.valid && rhs.valid && lhs.wrap == rhs.wrap && lhs.value == rhs.value

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
      registeredFullMask: Boolean,
      recordValid: Boolean,
      recordRid: Id,
      recordLoadLsId: Id,
      lifecycleEvidenceProviderValid: Boolean,
      lifecycleEvidenceRowClearIndex: Int): Result = {
    val active = enable && !flush
    val captureIdentity = active && capture
    val registeredCandidate = active && registeredValid
    val capturedIdentityValid = active && state.capturedValid
    val ridMatch = same(state.rid, recordRid)
    val loadLsIdMatch = same(state.loadLsId, recordLoadLsId)
    val retainedRecordAligned =
      registeredCandidate && capturedIdentityValid && recordValid && ridMatch && loadLsIdMatch
    val lifecycleEvidencePresent =
      retainedRecordAligned && state.lifecycleReady && lifecycleEvidenceProviderValid
    val lifecycleRowAligned =
      lifecycleEvidencePresent && state.lifecycleIndex == lifecycleEvidenceRowClearIndex
    val identityLifetimeAligned = retainedRecordAligned && lifecycleRowAligned
    val next =
      if (!enable || flush) State()
      else if (captureIdentity) {
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
      captureIdentity = captureIdentity,
      capturedIdentityValid = capturedIdentityValid,
      registeredCandidate = registeredCandidate,
      retainedRecordAligned = retainedRecordAligned,
      lifecycleRowAligned = lifecycleRowAligned,
      identityLifetimeAligned = identityLifetimeAligned,
      eligibleRegisteredMask = identityLifetimeAligned && registeredFullMask,
      blockedByNoCapturedIdentity = registeredCandidate && !capturedIdentityValid,
      blockedByMissingRecord = registeredCandidate && capturedIdentityValid && !recordValid,
      blockedByRidMismatch = registeredCandidate && capturedIdentityValid && recordValid && !ridMatch,
      blockedByLoadLsIdMismatch =
        registeredCandidate && capturedIdentityValid && recordValid && ridMatch && !loadLsIdMatch,
      blockedByMissingLifecycleEvidence =
        registeredCandidate && capturedIdentityValid && recordValid && ridMatch && loadLsIdMatch &&
          !(state.lifecycleReady && lifecycleEvidenceProviderValid),
      blockedByLifecycleRowMismatch =
        lifecycleEvidencePresent && state.lifecycleIndex != lifecycleEvidenceRowClearIndex,
      blockedByNotFullMask = registeredCandidate && capturedIdentityValid && !registeredFullMask,
      next = next)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofReference._

  private val rid = Id(valid = true, wrap = false, value = 3)
  private val lsid = Id(valid = true, wrap = false, value = 5)

  test("proves registered mask identity against retained record and lifecycle row") {
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
      registeredFullMask = false,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 6)
    val proven = step(
      captured.next,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 6)

    assert(captured.captureIdentity)
    assert(proven.registeredCandidate)
    assert(proven.retainedRecordAligned)
    assert(proven.lifecycleRowAligned)
    assert(proven.identityLifetimeAligned)
    assert(proven.eligibleRegisteredMask)
  }

  test("reports retained record identity mismatches separately") {
    val state = State(
      capturedValid = true,
      rid = rid,
      loadLsId = lsid,
      lifecycleReady = true,
      lifecycleIndex = 6)
    val ridMismatch = step(
      state,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true,
      recordValid = true,
      recordRid = Id(valid = true, wrap = false, value = 4),
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 6)
    val lsidMismatch = step(
      state,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = Id(valid = true, wrap = false, value = 7),
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 6)

    assert(ridMismatch.blockedByRidMismatch)
    assert(!ridMismatch.retainedRecordAligned)
    assert(lsidMismatch.blockedByLoadLsIdMismatch)
    assert(!lsidMismatch.retainedRecordAligned)
  }

  test("reports missing lifecycle evidence and row mismatches separately") {
    val state = State(
      capturedValid = true,
      rid = rid,
      loadLsId = lsid,
      lifecycleReady = true,
      lifecycleIndex = 6)
    val missing = step(
      state,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = false,
      lifecycleEvidenceRowClearIndex = 6)
    val mismatch = step(
      state,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = true,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 2)

    assert(missing.blockedByMissingLifecycleEvidence)
    assert(!missing.lifecycleRowAligned)
    assert(mismatch.blockedByLifecycleRowMismatch)
    assert(!mismatch.lifecycleRowAligned)
  }

  test("requires a full registered mask") {
    val state = State(
      capturedValid = true,
      rid = rid,
      loadLsId = lsid,
      lifecycleReady = true,
      lifecycleIndex = 6)
    val result = step(
      state,
      enable = true,
      flush = false,
      capture = false,
      captureRid = rid,
      captureLoadLsId = lsid,
      captureLifecycleRowReady = true,
      captureLifecycleRowIndex = 6,
      registeredValid = true,
      registeredFullMask = false,
      recordValid = true,
      recordRid = rid,
      recordLoadLsId = lsid,
      lifecycleEvidenceProviderValid = true,
      lifecycleEvidenceRowClearIndex = 6)

    assert(result.identityLifetimeAligned)
    assert(result.blockedByNotFullMask)
    assert(!result.eligibleRegisteredMask)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof"))
    assert(sv.contains("io_identityLifetimeAligned"))
    assert(sv.contains("io_eligibleRegisteredMask"))
  }
}
