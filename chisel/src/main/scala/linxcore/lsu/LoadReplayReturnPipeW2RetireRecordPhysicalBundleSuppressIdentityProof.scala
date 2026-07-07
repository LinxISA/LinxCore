package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofIO(
    val idEntries: Int = 16,
    val liqEntries: Int = 16)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val capture = Input(Bool())
  val captureRid = Input(new ROBID(idEntries))
  val captureLoadLsId = Input(new ROBID(idEntries))
  val captureLifecycleRowReady = Input(Bool())
  val captureLifecycleRowIndex = Input(UInt(liqPtrWidth.W))
  val registeredValid = Input(Bool())
  val registeredFullMask = Input(Bool())
  val recordValid = Input(Bool())
  val recordRid = Input(new ROBID(idEntries))
  val recordLoadLsId = Input(new ROBID(idEntries))
  val lifecycleEvidenceProviderValid = Input(Bool())
  val lifecycleEvidenceRowClearIndex = Input(UInt(liqPtrWidth.W))

  val captureIdentity = Output(Bool())
  val capturedIdentityValid = Output(Bool())
  val registeredCandidate = Output(Bool())
  val retainedRecordAligned = Output(Bool())
  val lifecycleRowAligned = Output(Bool())
  val identityLifetimeAligned = Output(Bool())
  val eligibleRegisteredMask = Output(Bool())
  val blockedByNoCapturedIdentity = Output(Bool())
  val blockedByMissingRecord = Output(Bool())
  val blockedByRidMismatch = Output(Bool())
  val blockedByLoadLsIdMismatch = Output(Bool())
  val blockedByMissingLifecycleEvidence = Output(Bool())
  val blockedByLifecycleRowMismatch = Output(Bool())
  val blockedByNotFullMask = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProof(
    val idEntries: Int = 16,
    val liqEntries: Int = 16)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(liqEntries > 1, "liqEntries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "liqEntries must be a power of two")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressIdentityProofIO(
    idEntries,
    liqEntries
  ))

  val capturedValidReg = RegInit(false.B)
  val capturedRidReg = RegInit(ROBID.disabled(idEntries))
  val capturedLoadLsIdReg = RegInit(ROBID.disabled(idEntries))
  val capturedLifecycleReadyReg = RegInit(false.B)
  val capturedLifecycleIndexReg = RegInit(0.U(liqPtrWidth.W))

  val active = io.enable && !io.flush
  val captureIdentity = active && io.capture

  when(!io.enable || io.flush) {
    capturedValidReg := false.B
    capturedRidReg := ROBID.disabled(idEntries)
    capturedLoadLsIdReg := ROBID.disabled(idEntries)
    capturedLifecycleReadyReg := false.B
    capturedLifecycleIndexReg := 0.U
  }.elsewhen(captureIdentity) {
    capturedValidReg := true.B
    capturedRidReg := io.captureRid
    capturedLoadLsIdReg := io.captureLoadLsId
    capturedLifecycleReadyReg := io.captureLifecycleRowReady
    capturedLifecycleIndexReg := io.captureLifecycleRowIndex
  }

  val registeredCandidate = active && io.registeredValid
  val capturedIdentityValid = active && capturedValidReg
  val ridMatch =
    capturedRidReg.valid &&
      io.recordRid.valid &&
      ROBID.equal(capturedRidReg, io.recordRid)
  val loadLsIdMatch =
    capturedLoadLsIdReg.valid &&
      io.recordLoadLsId.valid &&
      ROBID.equal(capturedLoadLsIdReg, io.recordLoadLsId)
  val retainedRecordAligned =
    registeredCandidate &&
      capturedIdentityValid &&
      io.recordValid &&
      ridMatch &&
      loadLsIdMatch
  val lifecycleEvidencePresent =
    retainedRecordAligned &&
      capturedLifecycleReadyReg &&
      io.lifecycleEvidenceProviderValid
  val lifecycleRowAligned =
    lifecycleEvidencePresent &&
      capturedLifecycleIndexReg === io.lifecycleEvidenceRowClearIndex
  val identityLifetimeAligned =
    retainedRecordAligned &&
      lifecycleRowAligned
  val eligibleRegisteredMask =
    identityLifetimeAligned &&
      io.registeredFullMask

  io.captureIdentity := captureIdentity
  io.capturedIdentityValid := capturedIdentityValid
  io.registeredCandidate := registeredCandidate
  io.retainedRecordAligned := retainedRecordAligned
  io.lifecycleRowAligned := lifecycleRowAligned
  io.identityLifetimeAligned := identityLifetimeAligned
  io.eligibleRegisteredMask := eligibleRegisteredMask
  io.blockedByNoCapturedIdentity := registeredCandidate && !capturedIdentityValid
  io.blockedByMissingRecord := registeredCandidate && capturedIdentityValid && !io.recordValid
  io.blockedByRidMismatch :=
    registeredCandidate &&
      capturedIdentityValid &&
      io.recordValid &&
      !ridMatch
  io.blockedByLoadLsIdMismatch :=
    registeredCandidate &&
      capturedIdentityValid &&
      io.recordValid &&
      ridMatch &&
      !loadLsIdMatch
  io.blockedByMissingLifecycleEvidence :=
    registeredCandidate &&
      capturedIdentityValid &&
      io.recordValid &&
      ridMatch &&
      loadLsIdMatch &&
      !(capturedLifecycleReadyReg && io.lifecycleEvidenceProviderValid)
  io.blockedByLifecycleRowMismatch :=
    lifecycleEvidencePresent &&
      capturedLifecycleIndexReg =/= io.lifecycleEvidenceRowClearIndex
  io.blockedByNotFullMask :=
    registeredCandidate &&
      capturedIdentityValid &&
      !io.registeredFullMask
}
