package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PopCount, PriorityEncoderOH, UIntToOH,
  Valid}

/** One physical resource shared by otherwise independent picker functions. */
final case class OooIexSharedResourceConfig(
    name: String,
    capability: Int,
    pickerFunctions: Seq[Int])

object OooIexSharedResourceConfig {
  def validate(
      p: OooParams,
      resources: Seq[OooIexSharedResourceConfig]): Unit = {
    require(resources.nonEmpty,
      "shared IEX arbitration needs at least one declared resource")
    require(resources.map(_.name).forall(_.nonEmpty) &&
      resources.map(_.name).distinct.length == resources.length,
      "shared IEX resources need nonempty unique names")
    require(resources.map(_.capability).distinct.length == resources.length,
      "one IEX capability may name only one shared resource")
    resources.foreach { resource =>
      require(resource.capability >= 0 &&
        resource.capability < OooIexDomainCapability.Count,
        s"shared IEX resource ${resource.name} names an invalid capability")
      require(resource.pickerFunctions.length >= 2 &&
        resource.pickerFunctions.distinct.length ==
          resource.pickerFunctions.length &&
        resource.pickerFunctions.forall(index =>
          index >= 0 && index < p.iexIssueDomainCount),
        s"shared IEX resource ${resource.name} needs valid unique participants")
    }
  }
}

class OooIexSharedResourceArbiterIO(
    val p: OooParams,
    val resourceCount: Int) extends Bundle {
  val requestValid = Input(UInt(p.iexIssueDomainCount.W))
  val capabilities = Input(Vec(p.iexIssueDomainCount,
    UInt(OooIexDomainCapability.Count.W)))
  val accepted = Input(UInt(p.iexIssueDomainCount.W))

  val eligible = Output(UInt(p.iexIssueDomainCount.W))
  val conflicted = Output(UInt(p.iexIssueDomainCount.W))
  val malformed = Output(UInt(p.iexIssueDomainCount.W))
  val winners = Output(UInt(p.iexIssueDomainCount.W))
  val roundRobin = Output(Vec(resourceCount,
    UInt(p.iexIssueDomainWidth.W)))
}

/** Fair same-cycle arbitration for independently shared execution resources.
  *
  * A loser remains retained in I1; this owner never creates a retry or copies
  * IQ state. Fairness advances only when the selected attempt also receives
  * the downstream atomic RF grant.
  */
class OooIexSharedResourceArbiter(
    val p: OooParams,
    val resources: Seq[OooIexSharedResourceConfig]) extends Module {
  OooIexSharedResourceConfig.validate(p, resources)
  val io = IO(new OooIexSharedResourceArbiterIO(p, resources.length))

  val roundRobin = RegInit(VecInit(Seq.fill(resources.length)(
    0.U(p.iexIssueDomainWidth.W))))
  val resourceRequests = Wire(Vec(resources.length,
    UInt(p.iexIssueDomainCount.W)))
  val resourceWinners = Wire(Vec(resources.length,
    UInt(p.iexIssueDomainCount.W)))

  for ((resource, resourceIndex) <- resources.zipWithIndex) {
    val capability = OooIexDomainCapability.mask(resource.capability)
      .U(OooIexDomainCapability.Count.W)
    val participantMask = resource.pickerFunctions.foldLeft(BigInt(0))(
      (mask, picker) => mask | (BigInt(1) << picker))
    val requests = VecInit((0 until p.iexIssueDomainCount).map { picker =>
      io.requestValid(picker) && participantMask.testBit(picker).B &&
        io.capabilities(picker) === capability
    }).asUInt
    resourceRequests(resourceIndex) := requests

    val rotatedRequests = Wire(Vec(p.iexIssueDomainCount, Bool()))
    val rotatedPickers = Wire(Vec(p.iexIssueDomainCount,
      UInt(p.iexIssueDomainWidth.W)))
    for (offset <- 0 until p.iexIssueDomainCount) {
      val sum = roundRobin(resourceIndex) +& offset.U
      val picker = Mux(sum >= p.iexIssueDomainCount.U,
        sum - p.iexIssueDomainCount.U, sum)(p.iexIssueDomainWidth - 1, 0)
      rotatedPickers(offset) := picker
      rotatedRequests(offset) := requests(picker)
    }
    val selectedOH = PriorityEncoderOH(rotatedRequests.asUInt)
    val selectedValid = selectedOH.orR
    val selectedPicker = Mux(selectedValid,
      Mux1H(selectedOH, rotatedPickers), 0.U)
    val winner = Mux(selectedValid,
      UIntToOH(selectedPicker, p.iexIssueDomainCount),
      0.U(p.iexIssueDomainCount.W))
    resourceWinners(resourceIndex) := winner

    val acceptedWinner = io.accepted & winner
    when(acceptedWinner.orR) {
      val acceptedPicker = chisel3.util.OHToUInt(acceptedWinner)
      roundRobin(resourceIndex) := Mux(
        acceptedPicker === (p.iexIssueDomainCount - 1).U,
        0.U, acceptedPicker + 1.U)
    }
  }

  val capabilityExact = VecInit(io.capabilities.map(capability =>
    capability.orR && PopCount(capability) === 1.U)).asUInt
  val sharedRequests = resourceRequests.reduce(_ | _)
  val winners = resourceWinners.reduce(_ | _)
  io.eligible := io.requestValid & capabilityExact &
    (~sharedRequests | winners)
  io.conflicted := io.requestValid & capabilityExact &
    sharedRequests & ~winners
  io.malformed := io.requestValid & ~capabilityExact
  io.winners := winners
  io.roundRobin := roundRobin

  assert((io.accepted & ~io.eligible) === 0.U,
    "shared IEX resources may accept only an eligible picker attempt")
}

class OooIexIssueReadFabricIO(
    val p: OooParams = OooParams(),
    val requireStoreReservation: Boolean = false) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val storeReserve = if (requireStoreReservation) Some(
    Decoupled(new OooIexIssueRow(p))) else None
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

  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new OooIexPcReadPortRequest(p))))
  val pcReadResponses = Input(Vec(p.pcReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val bypass = Input(Vec(p.iexBypassPorts,
    Valid(new OooIexBypassCandidate(p))))

  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(2, Valid(new OooIexPFileKey(p))))
  val pWrite = Flipped(Vec(p.iexPWritePorts,
    Valid(new OooIexPFileWrite(p))))
  val pWriteReady = Output(Vec(p.iexPWritePorts, Bool()))
  val pWriteFire = Output(Vec(p.iexPWritePorts, Bool()))
  val pReadyMask = Output(UInt(p.pPhysRegs.W))
  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val tWrite = Flipped(Vec(p.iexTWritePorts,
    Valid(new OooIexLocalFileWrite(p))))
  val uWrite = Flipped(Vec(p.iexUWritePorts,
    Valid(new OooIexLocalFileWrite(p))))
  val tWriteReady = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteReady = Output(Vec(p.iexUWritePorts, Bool()))
  val tWriteFire = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Output(Vec(p.iexUWritePorts, Bool()))

  val i2 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexI2Transaction(p)))
  val readAttempts = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexI1ReadAttempt(p))))
  val readCapabilities = Output(Vec(p.iexIssueDomainCount,
    UInt(OooIexDomainCapability.Count.W)))
  val sharedEligibleMask = Output(UInt(p.iexIssueDomainCount.W))
  val sharedConflictMask = Output(UInt(p.iexIssueDomainCount.W))
  val sharedMalformedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readSelectedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readDeniedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readShapeExact = Output(Vec(p.iexIssueDomainCount, Bool()))
  val retryFeedback = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadRepick(p))))
  val loadCanceled = Output(Vec(p.iexIssueDomainCount,
    Vec(3, Valid(new OooIexReadRepick(p)))))
  val stageCanceled = Output(Vec(p.iexIssueDomainCount,
    Vec(2, Valid(new OooIexStageCancel(p)))))
  val stageCancelRejected = Output(Vec(p.iexIssueDomainCount,
    Vec(2, Valid(new OooIexStageCancelReject(p)))))
  val readRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadReject(p))))
  val p1Rejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexP1Reject(p))))
  val joinRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickJoinReject(p))))
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

  val empty = Output(Bool())
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val tAllocatedCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.tPhysRegs).W)))
  val uAllocatedCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.uPhysRegs).W)))
  val tReadyCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.tPhysRegs).W)))
  val uReadyCount = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.uPhysRegs).W)))
  val pProtocolError = Output(Bool())
  val localProtocolError = Output(Bool())
}

/** Canonical issue through physical operand-read composition.
  *
  * One retained IQ owner feeds N private P1/I1/I2 lanes. Their complete I1
  * groups enter one atomic allocator, which drives the canonical P file and
  * exact STID-local T/U files. PC requests remain an explicit readyless port
  * boundary for the canonical OooPcBuffer owner. No manual read decision or
  * operand-data injection remains on this composition.
  */
class OooIexIssueReadFabric(
    val p: OooParams = OooParams(),
    val domainCapabilities: Seq[BigInt] = Seq.empty,
    val sharedResources: Seq[OooIexSharedResourceConfig] = Seq.empty,
    val staticDomains: Seq[OooIexIssueDomainConfig] = Seq.empty,
    val requireStoreReservation: Boolean = false)
    extends Module {
  if (staticDomains.nonEmpty) {
    OooIexIssueDomainConfig.validate(p, staticDomains)
  }
  private val effectiveDomainCapabilities =
    if (staticDomains.nonEmpty) staticDomains.map(_.capabilities)
    else domainCapabilities
  if (effectiveDomainCapabilities.nonEmpty) {
    require(effectiveDomainCapabilities.length == p.iexIssueDomainCount &&
      effectiveDomainCapabilities.forall(mask => mask != 0 &&
        (mask & ~OooIexDomainCapability.ValidMask) == 0),
      "IEX read fabric needs one valid capability mask per picker")
  }
  if (staticDomains.nonEmpty && domainCapabilities.nonEmpty) {
    require(domainCapabilities == effectiveDomainCapabilities,
      "static IEX domain capabilities must match the issue configuration")
  }
  if (sharedResources.nonEmpty) {
    OooIexSharedResourceConfig.validate(p, sharedResources)
    val declaredCapabilities =
      if (effectiveDomainCapabilities.nonEmpty) effectiveDomainCapabilities
      else Seq.fill(p.iexIssueDomainCount)(
        OooIexDomainCapability.ValidMask)
    sharedResources.foreach { resource =>
      val resourceMask = OooIexDomainCapability.mask(resource.capability)
      require(resource.pickerFunctions.forall(picker =>
        (declaredCapabilities(picker) & resourceMask) != 0),
        s"shared IEX resource ${resource.name} names an incapable picker")
    }
  }
  val io = IO(new OooIexIssueReadFabricIO(p, requireStoreReservation))

  val issue = Module(new OooIexIssueP1Fabric(p,
    effectiveDomainCapabilities, requireStoreReservation))
  val arbiter = Module(new OooIexAtomicReadArbiter(p))
  val operands = Module(new OooIexOperandFiles(p))

  issue.io.s1 <> io.s1
  if (requireStoreReservation) {
    io.storeReserve.get <> issue.io.storeReserve.get
  }
  issue.io.wakeup := io.wakeup
  issue.io.loadCancel := io.loadCancel
  issue.io.releases <> io.releases
  io.dispatchReleases <> issue.io.dispatchReleases
  issue.io.ptagRecycle <> io.ptagRecycle
  issue.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := issue.io.recoveryPrepareReady
  io.recoveryPrepared := issue.io.recoveryPrepared
  issue.io.recoveryFire := io.recoveryFire
  if (staticDomains.nonEmpty) {
    for (picker <- 0 until p.iexIssueDomainCount) {
      issue.io.pickBankEnables(picker) := VecInit(
        staticDomains(picker).classBankEnables.map(_.U(p.iqBankCount.W)))
    }
  } else {
    issue.io.pickBankEnables := io.pickBankEnables
  }
  issue.io.issuePolicy := io.issuePolicy
  issue.io.stageCancels <> io.stageCancels
  issue.io.bypass := io.bypass

  val arbitratedAttempts = Wire(Vec(p.iexIssueDomainCount,
    Valid(new OooIexI1ReadAttempt(p))))
  arbitratedAttempts := issue.io.readAttempts
  if (sharedResources.nonEmpty) {
    val shared = Module(new OooIexSharedResourceArbiter(p, sharedResources))
    shared.io.requestValid := VecInit(
      issue.io.readAttempts.map(_.valid)).asUInt
    shared.io.capabilities := issue.io.readCapabilities
    shared.io.accepted := arbiter.io.selectedMask
    for (picker <- 0 until p.iexIssueDomainCount) {
      arbitratedAttempts(picker).valid :=
        issue.io.readAttempts(picker).valid && shared.io.eligible(picker)
    }
    io.sharedEligibleMask := shared.io.eligible
    io.sharedConflictMask := shared.io.conflicted
    io.sharedMalformedMask := shared.io.malformed
  } else {
    io.sharedEligibleMask := VecInit(
      issue.io.readAttempts.map(_.valid)).asUInt
    io.sharedConflictMask := 0.U
    io.sharedMalformedMask := 0.U
  }
  arbiter.io.attempts := arbitratedAttempts
  for (domain <- 0 until p.iexIssueDomainCount) {
    issue.io.readDecisionValid(domain) := arbiter.io.decisionValid(domain)
    issue.io.readGrant(domain) := arbiter.io.grant(domain)
    issue.io.sourceDataValid(domain) := arbiter.io.sourceDataValid(domain)
    issue.io.sourceData(domain) := arbiter.io.sourceData(domain)
    issue.io.pcDataValid(domain) := arbiter.io.pcDataValid(domain)
    issue.io.pcData(domain) := arbiter.io.pcData(domain)
    io.i2(domain) <> issue.io.i2(domain)
  }

  operands.io.pReadRequests := arbiter.io.pReadRequests
  arbiter.io.pReadResponses := operands.io.pReadResponses
  operands.io.tReadRequests := arbiter.io.tReadRequests
  arbiter.io.tReadResponses := operands.io.tReadResponses
  operands.io.uReadRequests := arbiter.io.uReadRequests
  arbiter.io.uReadResponses := operands.io.uReadResponses
  io.pcReadRequests := arbiter.io.pcReadRequests
  arbiter.io.pcReadResponses := io.pcReadResponses

  operands.io.pInit := io.pInit
  operands.io.pClear := io.pClear
  operands.io.pWrite := io.pWrite
  io.pWriteReady := operands.io.pWriteReady
  io.pWriteFire := operands.io.pWriteFire
  io.pReadyMask := operands.io.pReadyMask
  operands.io.tClear := io.tClear
  operands.io.uClear := io.uClear
  operands.io.tWrite := io.tWrite
  operands.io.uWrite := io.uWrite
  io.tWriteReady := operands.io.tWriteReady
  io.uWriteReady := operands.io.uWriteReady
  io.tWriteFire := operands.io.tWriteFire
  io.uWriteFire := operands.io.uWriteFire

  io.readAttempts := issue.io.readAttempts
  io.readCapabilities := issue.io.readCapabilities
  io.readSelectedMask := arbiter.io.selectedMask
  io.readDeniedMask := arbiter.io.deniedMask
  io.readShapeExact := arbiter.io.shapeExact
  io.retryFeedback := issue.io.retryFeedback
  io.loadCanceled := issue.io.loadCanceled
  io.stageCanceled := issue.io.stageCanceled
  io.stageCancelRejected := issue.io.stageCancelRejected
  io.readRejected := issue.io.readRejected
  io.p1Rejected := issue.io.p1Rejected
  io.joinRejected := issue.io.joinRejected
  io.pickPolicyBlocked := issue.io.pickPolicyBlocked
  io.queryPolicyReasons := issue.io.queryPolicyReasons
  io.policyBlockedCount := issue.io.policyBlockedCount
  io.s1Rejected := issue.io.s1Rejected
  io.releaseRejecteds := issue.io.releaseRejecteds
  io.recoveryRejected := issue.io.recoveryRejected
  io.empty := issue.io.empty
  io.boundEntries := issue.io.boundEntries
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries
  io.tAllocatedCount := operands.io.tAllocatedCount
  io.uAllocatedCount := operands.io.uAllocatedCount
  io.tReadyCount := operands.io.tReadyCount
  io.uReadyCount := operands.io.uReadyCount
  io.pProtocolError := operands.io.pProtocolError
  io.localProtocolError := operands.io.localProtocolError
}

/** Production Linx specialization with one authoritative physical profile. */
class OooIexLinxIssueReadFabric(
    val profile: OooIexPhysicalProfile =
      OooIexLinxPhysicalProfile(),
    override val requireStoreReservation: Boolean = false)
    extends OooIexIssueReadFabric(
      profile.params,
      profile.transferConfigs.map(_.capabilities),
      OooIexLinxPhysicalProfile.sharedReadResources(profile),
      profile.transferConfigs,
      requireStoreReservation)
