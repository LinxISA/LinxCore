package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.common.{CoreParams => LoadCoreParams}
import linxcore.params.CoreParams
import linxcore.top.interface.{CmdIssueTxn, PcBufferReadAddress, RecoveryEvent,
  RecoveryPhase, RecoveryPlan, RecoveryPlanContract, RecoveryTargetIO,
  RobNoflushReadyTxn, RobNoflushTxn, RobResolveTxn, SystemIssueTxn}

/** Private stage composition from canonical issue through typed execution owners.
  *
  * The wrapper keeps IQ/read/E1 ownership in `OooIexPipeline`, feeds execution
  * results back into its canonical P/T/U files and wakeup/bypass domains, and
  * exposes only execution families whose later owners are still external.
  */
class OooIexExecutionPipelineIO(
    val core: CoreParams,
    val profile: OooIexPhysicalProfile,
    val requireStoreReservation: Boolean,
    val loadParams: LoadCoreParams) extends Bundle {
  val p = profile.params
  private def capabilityCount(capability: Int): Int =
    profile.pickerFunctions.count(_.hasCapability(capability))
  val dispatch = Flipped(new OOODispatchChannels(core))
  val storeReserve = if (requireStoreReservation) Some(
    Decoupled(new OooIexIssueRow(p))) else None
  val recovery = Flipped(new RecoveryTargetIO(core))
  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))

  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new PcBufferReadAddress(core))))
  val pcReadResponses = Input(Vec(p.pcReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(p.pTagAllocationWidth,
    Valid(new OooIexPFileKey(p))))
  val fastWriteback = Flipped(Decoupled(new OooFastResolveWriteback(p)))
  val fastWakeup = Flipped(Decoupled(new OooIexWakeup(p)))
  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))

  val storeAddress = Vec(capabilityCount(
    OooIexDomainCapability.StoreAddress),
    Decoupled(new OooIexExecuteTransaction(p)))
  val storeData = Vec(capabilityCount(OooIexDomainCapability.StoreData),
    Decoupled(new OooIexExecuteTransaction(p)))
  val multiCycleAlu = Vec(capabilityCount(
    OooIexDomainCapability.MultiCycleAlu),
    Decoupled(new OooIexExecuteTransaction(p)))
  val pointerAuth = Vec(capabilityCount(
    OooIexDomainCapability.PointerAuth),
    Decoupled(new OooIexExecuteTransaction(p)))
  val floatingVector = Decoupled(new OooIexExecuteTransaction(p))
  val robNoflushReady = Decoupled(new RobNoflushReadyTxn(core))
  val robNoflush = Flipped(Decoupled(new RobNoflushTxn(core)))
  val systemIssue = Vec(capabilityCount(OooIexDomainCapability.System),
    Decoupled(new SystemIssueTxn(core)))
  val cmdIssue = Decoupled(new CmdIssueTxn(core))
  val systemCmdResolve = Decoupled(new RobResolveTxn(core))
  val systemCmdTrace = Decoupled(new OooIexTerminalTrace(p))
  val load = new OooIexCanonicalLoadPortIO(p, loadParams)
  val loadCancel = Output(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))
  val robResolve = Vec(p.iexTerminalWidth,
    Decoupled(new RobResolveTxn(core)))
  val recoveryEvent = Vec(p.iexTerminalWidth,
    Decoupled(new RecoveryEvent(core)))

  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val systemCmdTerminalFire = Output(Bool())
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
  val pWriteFire = Output(Vec(p.iexPWritePorts, Bool()))
  val tWriteFire = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Output(Vec(p.iexUWritePorts, Bool()))
  val routeRejected = Output(Vec(profile.pickerFunctions.length,
    Valid(new OooIexExecutionRouteReject(p))))
  val terminalRejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val issueEmpty = Output(Bool())
  val executionEmpty = Output(Bool())
  val empty = Output(Bool())
  val pProtocolError = Output(Bool())
  val localProtocolError = Output(Bool())
}

class OooIexExecutionPipeline(
    val core: CoreParams,
    val requireStoreReservation: Boolean = false,
    val loadParamsOverride: Option[LoadCoreParams] = None)
    extends Module {
  val profile = OooIexPhysicalProfile.fromCoreParams(core)
  val p = profile.params
  val loadParams = loadParamsOverride.getOrElse(
    OooIexCanonicalLoadOwnership.defaultCoreParams(p))
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands
  private val fastPWritePort = terminalPorts
  private val fastWakeupPort = p.iexWakeupPorts - 1
  private val loadCount = profile.pickerFunctions.count(
    _.hasCapability(OooIexDomainCapability.LoadAddress))
  private val committedAndLoadWakeupPorts = terminalPorts + loadCount
  require(p.iexPWritePorts > terminalPorts,
    "IEX terminal composition needs one dedicated fast-result P write port")
  require(p.iexWakeupPorts > committedAndLoadWakeupPorts,
    "IEX terminal composition needs one dedicated fast-result wakeup port")
  val io = IO(new OooIexExecutionPipelineIO(
    core, profile, requireStoreReservation, loadParams))

  val issue = Module(new OooIexPipeline(core, requireStoreReservation))
  val execute = Module(new OooIexExecutionCluster(core, Some(loadParams)))

  issue.io.dispatch <> io.dispatch
  if (requireStoreReservation) {
    io.storeReserve.get <> issue.io.storeReserve.get
  }
  val recoveryPending = RegInit(false.B)
  val childrenPreparedAccepted = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val prepareReady = !recoveryPending && issue.io.recovery.prepare.ready &&
    execute.io.recovery.prepare.ready
  io.recovery.prepare.ready := prepareReady
  issue.io.recovery.prepare.valid := io.recovery.prepare.valid && prepareReady
  issue.io.recovery.prepare.bits := io.recovery.prepare.bits
  execute.io.recovery.prepare.valid := io.recovery.prepare.valid && prepareReady
  execute.io.recovery.prepare.bits := io.recovery.prepare.bits
  when(io.recovery.prepare.fire) {
    retainedRecovery := io.recovery.prepare.bits
    recoveryPending := true.B
    childrenPreparedAccepted := false.B
  }

  val bothPrepared = issue.io.recovery.prepared.valid &&
    execute.io.recovery.prepared.valid
  io.recovery.prepared.valid := recoveryPending && bothPrepared
  io.recovery.prepared.bits := retainedRecovery
  io.recovery.prepared.bits.phase := RecoveryPhase.Prepare
  issue.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    execute.io.recovery.prepared.valid
  execute.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    issue.io.recovery.prepared.valid
  when(io.recovery.prepared.fire) {
    assert(RecoveryPlanContract.sameTransactionIgnoringPhase(
      issue.io.recovery.prepared.bits,
      execute.io.recovery.prepared.bits),
      "issue and execution must prepare one exact recovery transaction")
    assert(RecoveryPlanContract.sameTransactionIgnoringPhase(
      issue.io.recovery.prepared.bits, retainedRecovery),
      "issue must echo the retained recovery transaction")
    childrenPreparedAccepted := true.B
  }
  val applyExact = io.recovery.apply.valid && recoveryPending &&
    childrenPreparedAccepted &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val abortExact = io.recovery.abort.valid && recoveryPending &&
    childrenPreparedAccepted &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)
  issue.io.recovery.apply.valid := applyExact
  issue.io.recovery.apply.bits := io.recovery.apply.bits
  execute.io.recovery.apply.valid := applyExact
  execute.io.recovery.apply.bits := io.recovery.apply.bits
  issue.io.recovery.abort.valid := abortExact
  issue.io.recovery.abort.bits := io.recovery.abort.bits
  execute.io.recovery.abort.valid := abortExact
  execute.io.recovery.abort.bits := io.recovery.abort.bits
  when(applyExact || abortExact) {
    recoveryPending := false.B
    childrenPreparedAccepted := false.B
  }
  when(io.recovery.apply.valid) {
    assert(applyExact,
      "IEX recovery apply requires the exact prepared transaction")
  }
  when(io.recovery.abort.valid) {
    assert(abortExact,
      "IEX recovery abort requires the exact prepared transaction")
  }
  issue.io.issuePolicy := io.issuePolicy
  issue.io.stageCancels <> io.stageCancels
  io.pcReadRequests := issue.io.pcReadRequests
  issue.io.pcReadResponses := io.pcReadResponses
  issue.io.pInit := io.pInit
  issue.io.pClear := io.pClear
  issue.io.tClear := io.tClear
  issue.io.uClear := io.uClear

  for (domain <- 0 until p.iexIssueDomainCount) {
    execute.io.e1(domain) <> issue.io.e1(domain)
  }
  issue.io.wakeup := execute.io.wakeup
  issue.io.bypass := execute.io.bypass
  issue.io.loadCancel := execute.io.loadCancel
  io.loadCancel := execute.io.loadCancel

  val fastResult = Module(new OooIexFastResultPort(p))
  fastResult.io.writeback <> io.fastWriteback
  fastResult.io.wakeup <> io.fastWakeup
  fastResult.io.pWriteReady := issue.io.pWriteReady(fastPWritePort)
  issue.io.wakeup(fastWakeupPort) := fastResult.io.issueWakeup

  for (port <- 0 until p.iexPWritePorts) {
    if (port < terminalPorts) {
      issue.io.pWrite(port).valid := execute.io.pWrite(port).valid
      issue.io.pWrite(port).bits := execute.io.pWrite(port).bits
      execute.io.pWrite(port).ready := issue.io.pWriteReady(port)
    } else if (port == fastPWritePort) {
      issue.io.pWrite(port) := fastResult.io.pWrite
    } else {
      issue.io.pWrite(port) := 0.U.asTypeOf(issue.io.pWrite(port))
    }
  }

  when(fastResult.io.accepted) {
    assert(issue.io.pWriteFire(fastPWritePort),
      "accepted fast result must mutate its exact PTag generation")
  }
  for (port <- 0 until p.iexTWritePorts) {
    if (port < terminalPorts) {
      issue.io.tWrite(port).valid := execute.io.tWrite(port).valid
      issue.io.tWrite(port).bits := execute.io.tWrite(port).bits
      execute.io.tWrite(port).ready := issue.io.tWriteReady(port)
    } else {
      issue.io.tWrite(port) := 0.U.asTypeOf(issue.io.tWrite(port))
    }
  }
  for (port <- 0 until p.iexUWritePorts) {
    if (port < terminalPorts) {
      issue.io.uWrite(port).valid := execute.io.uWrite(port).valid
      issue.io.uWrite(port).bits := execute.io.uWrite(port).bits
      execute.io.uWrite(port).ready := issue.io.uWriteReady(port)
    } else {
      issue.io.uWrite(port) := 0.U.asTypeOf(issue.io.uWrite(port))
    }
  }

  for (index <- io.storeAddress.indices) {
    io.storeAddress(index).valid := execute.io.storeAddress(index).valid
    io.storeAddress(index).bits := execute.io.storeAddress(index).bits
    execute.io.storeAddress(index).ready := io.storeAddress(index).ready
  }
  for (index <- io.storeData.indices) {
    io.storeData(index).valid := execute.io.storeData(index).valid
    io.storeData(index).bits := execute.io.storeData(index).bits
    execute.io.storeData(index).ready := io.storeData(index).ready
  }
  for (index <- io.multiCycleAlu.indices) {
    io.multiCycleAlu(index).valid := execute.io.multiCycleAlu(index).valid
    io.multiCycleAlu(index).bits := execute.io.multiCycleAlu(index).bits
    execute.io.multiCycleAlu(index).ready := io.multiCycleAlu(index).ready
  }
  for (index <- io.pointerAuth.indices) {
    io.pointerAuth(index).valid := execute.io.pointerAuth(index).valid
    io.pointerAuth(index).bits := execute.io.pointerAuth(index).bits
    execute.io.pointerAuth(index).ready := io.pointerAuth(index).ready
  }
  io.floatingVector.valid := execute.io.floatingVector.valid
  io.floatingVector.bits := execute.io.floatingVector.bits
  execute.io.floatingVector.ready := io.floatingVector.ready
  io.robNoflushReady.valid := execute.io.robNoflushReady.valid
  io.robNoflushReady.bits := execute.io.robNoflushReady.bits
  execute.io.robNoflushReady.ready := io.robNoflushReady.ready
  execute.io.robNoflush.valid := io.robNoflush.valid
  execute.io.robNoflush.bits := io.robNoflush.bits
  io.robNoflush.ready := execute.io.robNoflush.ready
  for (index <- io.systemIssue.indices) {
    io.systemIssue(index).valid := execute.io.systemIssue(index).valid
    io.systemIssue(index).bits := execute.io.systemIssue(index).bits
    execute.io.systemIssue(index).ready := io.systemIssue(index).ready
  }
  io.cmdIssue.valid := execute.io.cmdIssue.valid
  io.cmdIssue.bits := execute.io.cmdIssue.bits
  execute.io.cmdIssue.ready := io.cmdIssue.ready
  io.systemCmdResolve.valid := execute.io.systemCmdResolve.valid
  io.systemCmdResolve.bits := execute.io.systemCmdResolve.bits
  execute.io.systemCmdResolve.ready := io.systemCmdResolve.ready
  io.systemCmdTrace.valid := execute.io.systemCmdTrace.valid
  io.systemCmdTrace.bits := execute.io.systemCmdTrace.bits
  execute.io.systemCmdTrace.ready := io.systemCmdTrace.ready

  io.load.liqAlloc <> execute.io.load.liqAlloc
  execute.io.load.liqAllocLoadId := io.load.liqAllocLoadId
  execute.io.load.rebind <> io.load.rebind
  io.load.liqRebind <> execute.io.load.liqRebind
  execute.io.load.attemptLaunch := io.load.attemptLaunch
  io.load.attemptLaunchAccepted :=
    execute.io.load.attemptLaunchAccepted
  execute.io.load.completion <> io.load.completion
  for (lane <- 0 until p.iexTerminalWidth) {
    io.bctrl(lane).valid := execute.io.bctrl(lane).valid
    io.bctrl(lane).bits := execute.io.bctrl(lane).bits
    execute.io.bctrl(lane).ready := io.bctrl(lane).ready
    io.trace(lane).valid := execute.io.trace(lane).valid
    io.trace(lane).bits := execute.io.trace(lane).bits
    execute.io.trace(lane).ready := io.trace(lane).ready
    io.robResolve(lane) <> execute.io.robResolve(lane)
    io.recoveryEvent(lane) <> execute.io.recoveryEvent(lane)
  }

  io.terminalFireMask := execute.io.terminalFireMask
  io.systemCmdTerminalFire := execute.io.systemCmdTerminalFire
  io.transferFireMask := issue.io.transferFireMask
  io.pWriteFire := issue.io.pWriteFire
  io.tWriteFire := issue.io.tWriteFire
  io.uWriteFire := issue.io.uWriteFire
  io.routeRejected := execute.io.routeRejected
  io.terminalRejected := execute.io.terminalRejected
  io.boundEntries := issue.io.boundEntries
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries
  io.issueEmpty := issue.io.empty
  io.executionEmpty := execute.io.empty
  io.empty := !recoveryPending && issue.io.empty && execute.io.empty
  io.pProtocolError := issue.io.pProtocolError
  io.localProtocolError := issue.io.localProtocolError

}
