package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexIssueP1FabricIO(val p: OooParams = OooParams()) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val releases = Flipped(Vec(p.iexReleaseWidth,
    Decoupled(new OooIexIssueRelease(p))))
  def release = releases(0)
  val dispatchReleases = Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p)))
  def dispatchRelease = dispatchReleases(0)
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryFire = Input(Bool())

  val pickBankEnables = Input(Vec(p.iexIssueDomainCount,
    Vec(p.iqClassCount, UInt(p.iqBankCount.W))))
  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))

  val readAttempts = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexI1ReadAttempt(p))))
  val readCapabilities = Output(Vec(p.iexIssueDomainCount,
    UInt(OooIexDomainCapability.Count.W)))
  val readDecisionValid = Input(Vec(p.iexIssueDomainCount, Bool()))
  val readGrant = Input(Vec(p.iexIssueDomainCount, Bool()))
  val sourceDataValid = Input(Vec(p.iexIssueDomainCount,
    UInt(p.maxSourceOperands.W)))
  val sourceData = Input(Vec(p.iexIssueDomainCount,
    Vec(p.maxSourceOperands, UInt(p.pcWidth.W))))
  val pcDataValid = Input(Vec(p.iexIssueDomainCount, Bool()))
  val pcData = Input(Vec(p.iexIssueDomainCount, UInt(p.pcWidth.W)))
  val bypass = Input(Vec(p.iexBypassPorts,
    Valid(new OooIexBypassCandidate(p))))
  val i2 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexI2Transaction(p)))

  val retryFeedback = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadRepick(p))))
  val joinRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickJoinReject(p))))
  val p1Rejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexP1Reject(p))))
  val readRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadReject(p))))
  val recoveryCanceled = Output(Vec(p.iexIssueDomainCount,
    Vec(2, Valid(new OooIexReadRepick(p)))))
  val loadCanceled = Output(Vec(p.iexIssueDomainCount,
    Vec(3, Valid(new OooIexReadRepick(p)))))
  val stageCanceled = Output(Vec(p.iexIssueDomainCount,
    Vec(2, Valid(new OooIexStageCancel(p)))))
  val stageCancelRejected = Output(Vec(p.iexIssueDomainCount,
    Vec(2, Valid(new OooIexStageCancelReject(p)))))

  val pickMalformed = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickReject(p))))
  val pickRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickClaimReject(p))))
  val pickRetryRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickRetryReject(p))))
  val pickRecoveryCanceled = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickToken(p))))
  val pickRecoveryBlocked = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickToken(p))))
  val pickPolicyBlocked = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexIssuePolicyBlockEvent(p))))
  val queryPolicyReasons = Output(Vec(p.iexIssueDomainCount,
    UInt(OooIexIssueBlockReason.Count.W)))
  val policyBlockedCount = Output(Vec(p.iexIssueDomainCount,
    UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
      p.iqEntriesPerBank).W)))
  val s1Rejected = Output(Valid(new OooIexS1Reject(p)))
  val releaseRejecteds = Output(Vec(p.iexReleaseWidth,
    Valid(new OooIexReleaseReject(p))))
  def releaseRejected = releaseRejecteds(0)
  val recoveryRejected = Output(Valid(new OooIexRecoveryReject(p)))

  val s1Occupied = Output(Vec(p.stidCount, Bool()))
  val i1Occupied = Output(Vec(p.iexIssueDomainCount, Bool()))
  val i2Occupied = Output(Vec(p.iexIssueDomainCount, Bool()))
  val lanesEmpty = Output(Bool())
  val empty = Output(Bool())
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
}

/** Parameterized multi-domain IQ → P1 → I1 → I2 composition.
  *
  * One canonical IQ owns every schedule row and payload sidecar. Each physical
  * picker function adds only a retained token, exact sidecar join, and
  * private P1/I1/I2 residency. The caller supplies class/bank/capability
  * projections. The IQ permits overlapping banks only for capability-disjoint
  * picker functions and asserts that topology before parallel claims.
  */
class OooIexIssueP1Fabric(
    val p: OooParams = OooParams(),
    val domainCapabilities: Seq[BigInt] = Seq.empty) extends Module {
  val io = IO(new OooIexIssueP1FabricIO(p))

  val issue = Module(new OooIexIssue(p, domainCapabilities))
  val bridges = Seq.fill(p.iexIssueDomainCount)(
    Module(new OooIexPickP1Bridge(p)))
  val lanes = Seq.fill(p.iexIssueDomainCount)(
    Module(new OooIexP1I2Lane(p)))

  issue.io.s1 <> io.s1
  issue.io.wakeup := io.wakeup
  issue.io.loadCancel := io.loadCancel
  issue.io.releases <> io.releases
  io.dispatchReleases <> issue.io.dispatchReleases
  issue.io.ptagRecycle <> io.ptagRecycle
  issue.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := issue.io.recoveryPrepareReady
  io.recoveryPrepared := issue.io.recoveryPrepared
  issue.io.recoveryFire := io.recoveryFire
  issue.io.pickBankEnables := io.pickBankEnables
  issue.io.issuePolicy := io.issuePolicy

  for (domain <- 0 until p.iexIssueDomainCount) {
    val bridge = bridges(domain)
    val lane = lanes(domain)

    bridge.io.pick <> issue.io.picks(domain)
    issue.io.queries(domain) := bridge.io.query
    bridge.io.queryState := issue.io.queryStates(domain)
    bridge.io.queryRow := issue.io.queryRows(domain)
    lane.io.p1 <> bridge.io.p1

    val retryConflict = bridge.io.repick.valid && lane.io.repick.valid
    assert(!retryConflict,
      "one IEX domain must never return two exact retries in one cycle")
    issue.io.pickRetries(domain).valid :=
      bridge.io.repick.valid || lane.io.repick.valid
    issue.io.pickRetries(domain).bits := Mux(
      bridge.io.repick.valid, bridge.io.repick.bits, lane.io.repick.bits)
    lane.io.repick.ready := true.B
    io.retryFeedback(domain) := issue.io.pickRetries(domain)

    io.readAttempts(domain) := lane.io.readAttempt
    io.readCapabilities(domain) := lane.io.readCapability
    lane.io.readDecisionValid := io.readDecisionValid(domain)
    lane.io.readGrant := io.readGrant(domain)
    lane.io.sourceDataValid := io.sourceDataValid(domain)
    lane.io.sourceData := io.sourceData(domain)
    lane.io.pcDataValid := io.pcDataValid(domain)
    lane.io.pcData := io.pcData(domain)
    lane.io.bypass := io.bypass
    lane.io.loadCancel := io.loadCancel
    lane.io.stageCancel <> io.stageCancels(domain)
    io.i2(domain) <> lane.io.i2
    lane.io.recoveryApply := issue.io.recoveryApplied

    io.joinRejected(domain) := bridge.io.rejected
    io.p1Rejected(domain) := lane.io.p1Rejected
    io.readRejected(domain) := lane.io.readRejected
    io.recoveryCanceled(domain) := lane.io.recoveryCanceled
    io.loadCanceled(domain) := lane.io.loadCanceled
    io.stageCanceled(domain) := lane.io.stageCanceled
    io.stageCancelRejected(domain) := lane.io.stageCancelRejected
    io.pickMalformed(domain) := issue.io.pickMalformedByDomain(domain)
    io.pickRejected(domain) := issue.io.pickRejectedByDomain(domain)
    io.pickRetryRejected(domain) :=
      issue.io.pickRetryRejectedByDomain(domain)
    io.pickRecoveryCanceled(domain) :=
      issue.io.pickRecoveryCanceledByDomain(domain)
    io.pickRecoveryBlocked(domain) :=
      issue.io.pickRecoveryBlockedByDomain(domain)
    io.pickPolicyBlocked(domain) :=
      issue.io.pickPolicyBlockedByDomain(domain)
    io.queryPolicyReasons(domain) := issue.io.queryPolicyReasons(domain)
    io.policyBlockedCount(domain) := issue.io.policyBlockedCount(domain)
    io.i1Occupied(domain) := lane.io.i1Occupied
    io.i2Occupied(domain) := lane.io.i2Occupied
  }

  io.s1Rejected := issue.io.s1Rejected
  io.releaseRejecteds := issue.io.releaseRejecteds
  io.recoveryRejected := issue.io.recoveryRejected
  io.s1Occupied := issue.io.s1Occupied
  io.boundEntries := issue.io.boundEntries
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries

  io.lanesEmpty := lanes.map(_.io.empty).reduce(_ && _)
  val noBoundRows = issue.io.boundEntries.flatten.map(_ === 0.U).reduce(_ && _)
  val noResidentRows =
    issue.io.residentEntries.flatten.map(_ === 0.U).reduce(_ && _)
  io.empty := io.lanesEmpty && !issue.io.s1Occupied.asUInt.orR &&
    noBoundRows && noResidentRows && !issue.io.recoveryBusy
}
