package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexIssueP1LaneIO(val p: OooParams = OooParams()) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val dispatchRelease = Decoupled(new OooDispatchRelease(p))
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryFire = Input(Bool())

  val pickClass = Input(OooUopClass())
  val pickBankEnable = Input(UInt(p.iqBankCount.W))
  val issuePolicy = Input(new OooIexIssuePolicy(p))

  val readAttempt = Valid(new OooIexI1ReadAttempt(p))
  val readDecisionValid = Input(Bool())
  val readGrant = Input(Bool())
  val sourceDataValid = Input(UInt(p.maxSourceOperands.W))
  val sourceData = Input(Vec(p.maxSourceOperands, UInt(p.pcWidth.W)))
  val pcDataValid = Input(Bool())
  val pcData = Input(UInt(p.pcWidth.W))
  val bypass = Input(Vec(p.iexBypassPorts,
    Valid(new OooIexBypassCandidate(p))))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val i2 = Decoupled(new OooIexI2Transaction(p))

  val retryFeedback = Valid(new OooIexReadRepick(p))
  val joinRejected = Valid(new OooIexPickJoinReject(p))
  val p1Rejected = Valid(new OooIexP1Reject(p))
  val readRejected = Valid(new OooIexReadReject(p))
  val recoveryCanceled = Output(Vec(2,
    Valid(new OooIexReadRepick(p))))
  val loadCanceled = Output(Vec(3,
    Valid(new OooIexReadRepick(p))))

  val pickMalformed = Valid(new OooIexPickReject(p))
  val pickRejected = Valid(new OooIexPickClaimReject(p))
  val pickRetryRejected = Valid(new OooIexPickRetryReject(p))
  val pickRecoveryCanceled = Valid(new OooIexPickToken(p))
  val pickRecoveryBlocked = Valid(new OooIexPickToken(p))
  val pickPolicyBlocked = Valid(new OooIexIssuePolicyBlockEvent(p))
  val queryPolicyReason = Output(
    UInt(OooIexIssueBlockReason.Count.W))
  val policyBlockedCount = Output(
    UInt(p.countWidth(p.iqBankCount * p.iqEntriesPerBank).W))
  val s1Rejected = Valid(new OooIexS1Reject(p))
  val releaseRejected = Valid(new OooIexReleaseReject(p))
  val recoveryRejected = Valid(new OooIexRecoveryReject(p))

  val s1Occupied = Output(Vec(p.stidCount, Bool()))
  val i1Occupied = Output(Bool())
  val i2Occupied = Output(Bool())
  val pipelineEmpty = Output(Bool())
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
}

/** Canonical one-domain IQ → P1 → I1 → I2 composition.
  *
  * This module is the first executable integration seam: the IQ remains the
  * only row/payload owner, the bridge performs one exact sidecar join, and the
  * lane owns only pipeline residency. Every unsuccessful P1/I1 attempt feeds
  * the exact member back to the IQ so it becomes pickable again.
  */
class OooIexIssueP1Lane(val p: OooParams = OooParams()) extends Module {
  require(p.iexIssueDomainCount == 1,
    "OooIexIssueP1Lane is the one-domain compatibility composition")
  val io = IO(new OooIexIssueP1LaneIO(p))

  val issue = Module(new OooIexIssue(p))
  val bridge = Module(new OooIexPickP1Bridge(p))
  val lane = Module(new OooIexP1I2Lane(p))

  issue.io.s1 <> io.s1
  issue.io.wakeup := io.wakeup
  issue.io.loadCancel := io.loadCancel
  issue.io.release <> io.release
  io.dispatchRelease <> issue.io.dispatchRelease
  issue.io.ptagRecycle <> io.ptagRecycle
  issue.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := issue.io.recoveryPrepareReady
  io.recoveryPrepared := issue.io.recoveryPrepared
  issue.io.recoveryFire := io.recoveryFire
  issue.io.pickClass := io.pickClass
  issue.io.pickBankEnable := io.pickBankEnable
  issue.io.issuePolicy := io.issuePolicy

  bridge.io.pick <> issue.io.pick
  issue.io.query := bridge.io.query
  bridge.io.queryState := issue.io.queryState
  bridge.io.queryRow := issue.io.queryRow
  lane.io.p1 <> bridge.io.p1

  val retryConflict = bridge.io.repick.valid && lane.io.repick.valid
  assert(!retryConflict,
    "one IEX lane must never return two distinct exact retries in one cycle")
  issue.io.pickRetry.valid := bridge.io.repick.valid || lane.io.repick.valid
  issue.io.pickRetry.bits := Mux(
    bridge.io.repick.valid, bridge.io.repick.bits, lane.io.repick.bits)
  io.retryFeedback := issue.io.pickRetry

  io.readAttempt := lane.io.readAttempt
  lane.io.readDecisionValid := io.readDecisionValid
  lane.io.readGrant := io.readGrant
  lane.io.sourceDataValid := io.sourceDataValid
  lane.io.sourceData := io.sourceData
  lane.io.pcDataValid := io.pcDataValid
  lane.io.pcData := io.pcData
  lane.io.bypass := io.bypass
  lane.io.loadCancel := io.loadCancel
  io.i2 <> lane.io.i2
  lane.io.recoveryApply := issue.io.recoveryApplied

  io.joinRejected := bridge.io.rejected
  io.p1Rejected := lane.io.p1Rejected
  io.readRejected := lane.io.readRejected
  io.recoveryCanceled := lane.io.recoveryCanceled
  io.loadCanceled := lane.io.loadCanceled
  io.pickMalformed := issue.io.pickMalformed
  io.pickRejected := issue.io.pickRejected
  io.pickRetryRejected := issue.io.pickRetryRejected
  io.pickRecoveryCanceled := issue.io.pickRecoveryCanceled
  io.pickRecoveryBlocked := issue.io.pickRecoveryBlocked
  io.pickPolicyBlocked := issue.io.pickPolicyBlocked
  io.queryPolicyReason := issue.io.queryPolicyReason
  io.policyBlockedCount := issue.io.policyBlockedCount(0)
  io.s1Rejected := issue.io.s1Rejected
  io.releaseRejected := issue.io.releaseRejected
  io.recoveryRejected := issue.io.recoveryRejected
  io.s1Occupied := issue.io.s1Occupied
  io.i1Occupied := lane.io.i1Occupied
  io.i2Occupied := lane.io.i2Occupied
  io.pipelineEmpty := lane.io.empty
  io.boundEntries := issue.io.boundEntries
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries
}
