package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.params.CoreParams
import linxcore.top.interface.{PcBufferReadAddress, RecoveryTargetIO}

/** Public boundary of the canonical Linx P1/I1/I2/E1 issue pipeline.
  *
  * I2 ownership and both release handshakes are deliberately private. A
  * caller can submit S1 work and consume typed E1 lanes, but cannot reconnect
  * IQ release independently from E1 capture.
  */
class OooIexPipelineIO(
    val core: CoreParams,
    val p: OooParams,
    val requireStoreReservation: Boolean = false) extends Bundle {
  val dispatch = Flipped(new OOODispatchChannels(core))
  val storeReserve = if (requireStoreReservation) Some(
    Decoupled(new OooIexIssueRow(p))) else None
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val recovery = Flipped(new RecoveryTargetIO(core))

  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))

  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new PcBufferReadAddress(core))))
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

  val e1 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexExecuteTransaction(p)))

  val staticBankEnables = Output(Vec(p.iexIssueDomainCount,
    Vec(p.iqClassCount, UInt(p.iqBankCount.W))))
  val releaseDomains = Vec(p.iexReleaseWidth,
    Valid(UInt(p.iexIssueDomainWidth.W)))
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
  val sharedEligibleMask = Output(UInt(p.iexIssueDomainCount.W))
  val sharedConflictMask = Output(UInt(p.iexIssueDomainCount.W))
  val sharedMalformedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readSelectedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readDeniedMask = Output(UInt(p.iexIssueDomainCount.W))
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
  val releaseRejecteds = Output(Vec(p.iexReleaseWidth,
    Valid(new OooIexReleaseReject(p))))
  val transferRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexE1TransferReject(p))))
  val transferKilled = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexExecuteTransaction(p))))

  val issueEmpty = Output(Bool())
  val transferEmpty = Output(Bool())
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

/** Canonical stage composition from retained IQ issue through typed E1 lanes.
  *
  * One physical profile constructs both children. The I2 handoff, exact IQ
  * release and E1 capture therefore share one topology
  * and one ownership transaction by construction.
  */
class OooIexPipeline(
    val core: CoreParams,
    val requireStoreReservation: Boolean = false)
    extends Module {
  val profile = OooIexPhysicalProfile.fromCoreParams(core)
  val p = profile.params
  val io = IO(new OooIexPipelineIO(core, p, requireStoreReservation))

  val issue = Module(new OooIexIssueReadFabric(
    core, requireStoreReservation))
  val transfer = Module(new OooIexLinxE1TransferFabric(profile))

  issue.io.dispatch <> io.dispatch
  if (requireStoreReservation) {
    io.storeReserve.get <> issue.io.storeReserve.get
  }
  issue.io.wakeup := io.wakeup
  issue.io.loadCancel := io.loadCancel
  issue.io.recovery <> io.recovery
  issue.io.issuePolicy := io.issuePolicy
  issue.io.stageCancels <> io.stageCancels
  io.pcReadRequests := issue.io.pcReadRequests
  issue.io.pcReadResponses := io.pcReadResponses
  issue.io.bypass := io.bypass

  issue.io.pInit := io.pInit
  issue.io.pClear := io.pClear
  issue.io.pWrite := io.pWrite
  io.pWriteReady := issue.io.pWriteReady
  io.pWriteFire := issue.io.pWriteFire
  io.pReadyMask := issue.io.pReadyMask
  issue.io.tClear := io.tClear
  issue.io.uClear := io.uClear
  issue.io.tWrite := io.tWrite
  issue.io.uWrite := io.uWrite
  io.tWriteReady := issue.io.tWriteReady
  io.uWriteReady := issue.io.uWriteReady
  io.tWriteFire := issue.io.tWriteFire
  io.uWriteFire := issue.io.uWriteFire

  for (domain <- 0 until p.iexIssueDomainCount) {
    transfer.io.i2(domain) <> issue.io.i2(domain)
    io.e1(domain) <> transfer.io.e1(domain)
  }
  issue.io.releases <> transfer.io.issueReleases
  transfer.io.loadCancel := io.loadCancel
  transfer.io.recoveryApply := issue.io.acceptedRecoveryApply

  io.staticBankEnables := transfer.io.pickBankEnables
  io.releaseDomains := transfer.io.releaseDomains
  io.transferFireMask := VecInit(
    transfer.io.issueReleases.map(_.fire)).asUInt
  io.sharedEligibleMask := issue.io.sharedEligibleMask
  io.sharedConflictMask := issue.io.sharedConflictMask
  io.sharedMalformedMask := issue.io.sharedMalformedMask
  io.readSelectedMask := issue.io.readSelectedMask
  io.readDeniedMask := issue.io.readDeniedMask
  io.retryFeedback := issue.io.retryFeedback
  io.loadCanceled := issue.io.loadCanceled
  io.stageCanceled := issue.io.stageCanceled
  io.stageCancelRejected := issue.io.stageCancelRejected
  io.readRejected := issue.io.readRejected
  io.p1Rejected := issue.io.p1Rejected
  io.joinRejected := issue.io.joinRejected
  io.pickPolicyBlocked := issue.io.pickPolicyBlocked
  io.releaseRejecteds := issue.io.releaseRejecteds
  io.transferRejected := transfer.io.rejected
  io.transferKilled := transfer.io.killed

  io.issueEmpty := issue.io.empty
  io.transferEmpty := transfer.io.empty
  io.empty := issue.io.empty && transfer.io.empty
  io.boundEntries := issue.io.boundEntries
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries
  io.tAllocatedCount := issue.io.tAllocatedCount
  io.uAllocatedCount := issue.io.uAllocatedCount
  io.tReadyCount := issue.io.tReadyCount
  io.uReadyCount := issue.io.uReadyCount
  io.pProtocolError := issue.io.pProtocolError
  io.localProtocolError := issue.io.localProtocolError
}
