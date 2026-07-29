package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexIssueReadFabricIO(val p: OooParams = OooParams()) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val dispatchRelease = Decoupled(new OooDispatchRelease(p))
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryFire = Input(Bool())

  val pickClasses = Input(Vec(p.iexIssueDomainCount, OooUopClass()))
  val pickBankEnables = Input(Vec(p.iexIssueDomainCount,
    UInt(p.iqBankCount.W)))

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
  val readSelectedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readDeniedMask = Output(UInt(p.iexIssueDomainCount.W))
  val readShapeExact = Output(Vec(p.iexIssueDomainCount, Bool()))
  val retryFeedback = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadRepick(p))))
  val readRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadReject(p))))
  val p1Rejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexP1Reject(p))))
  val joinRejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickJoinReject(p))))
  val s1Rejected = Output(Valid(new OooIexS1Reject(p)))
  val releaseRejected = Output(Valid(new OooIexReleaseReject(p)))
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
class OooIexIssueReadFabric(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexIssueReadFabricIO(p))

  val issue = Module(new OooIexIssueP1Fabric(p))
  val arbiter = Module(new OooIexAtomicReadArbiter(p))
  val operands = Module(new OooIexOperandFiles(p))

  issue.io.s1 <> io.s1
  issue.io.wakeup := io.wakeup
  issue.io.release <> io.release
  io.dispatchRelease <> issue.io.dispatchRelease
  issue.io.ptagRecycle <> io.ptagRecycle
  issue.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := issue.io.recoveryPrepareReady
  io.recoveryPrepared := issue.io.recoveryPrepared
  issue.io.recoveryFire := io.recoveryFire
  issue.io.pickClasses := io.pickClasses
  issue.io.pickBankEnables := io.pickBankEnables
  issue.io.bypass := io.bypass

  arbiter.io.attempts := issue.io.readAttempts
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
  io.readSelectedMask := arbiter.io.selectedMask
  io.readDeniedMask := arbiter.io.deniedMask
  io.readShapeExact := arbiter.io.shapeExact
  io.retryFeedback := issue.io.retryFeedback
  io.readRejected := issue.io.readRejected
  io.p1Rejected := issue.io.p1Rejected
  io.joinRejected := issue.io.joinRejected
  io.s1Rejected := issue.io.s1Rejected
  io.releaseRejected := issue.io.releaseRejected
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
