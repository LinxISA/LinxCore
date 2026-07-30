package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.common.CoreParams

/** Production static composition from OOO S1 through typed execution owners.
  *
  * The wrapper keeps IQ/read/E1 ownership in `OooIexPipeline`, feeds execution
  * results back into its canonical P/T/U files and wakeup/bypass domains, and
  * exposes only execution families whose later owners are still external.
  */
class OooIexExecutionPipelineIO(
    val p: OooParams,
    val requireStoreReservation: Boolean,
    val coreParams: CoreParams) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val storeReserve = if (requireStoreReservation) Some(
    Decoupled(new OooIexIssueRow(p))) else None
  val dispatchReleases = Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p)))
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryRejected = Output(Valid(new OooIexRecoveryReject(p)))
  val recoveryFire = Input(Bool())
  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))

  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new OooIexPcReadPortRequest(p))))
  val pcReadResponses = Input(Vec(p.pcReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(2, Valid(new OooIexPFileKey(p))))
  val fastWriteback = Flipped(Decoupled(new OooFastResolveWriteback(p)))
  val fastWakeup = Flipped(Decoupled(new OooIexWakeup(p)))
  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))

  val storeAddress = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val storeData = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val multiCycleAlu = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val system = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val pointerAuth = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val floatingVector = Decoupled(new OooIexExecuteTransaction(p))
  val engineCommand = Decoupled(new OooIexExecuteTransaction(p))
  val load = new OooIexCanonicalLoadPortIO(p, coreParams)
  val loadCancel = Output(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))
  val completion = Vec(p.iexTerminalWidth,
    Decoupled(new OooRobMemberCompletion(p)))

  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
  val pWriteFire = Output(Vec(p.iexPWritePorts, Bool()))
  val tWriteFire = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Output(Vec(p.iexUWritePorts, Bool()))
  val routeRejected = Output(Vec(
    OooIexLinxPhysicalProfile.ExecutionLaneCount,
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
    val profile: OooIexPhysicalProfile = OooIexLinxPhysicalProfile(),
    val requireStoreReservation: Boolean = false,
    val coreParamsOverride: Option[CoreParams] = None)
    extends Module {
  val p = profile.params
  val coreParams = coreParamsOverride.getOrElse(
    OooIexCanonicalLoadOwnership.defaultCoreParams(p))
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands
  private val fastPWritePort = terminalPorts
  private val fastWakeupPort = p.iexWakeupPorts - 1
  private val committedAndLoadWakeupPorts = terminalPorts + 3
  require(p.iexPWritePorts > terminalPorts,
    "production execution needs one dedicated fast-result P write port")
  require(p.iexWakeupPorts > committedAndLoadWakeupPorts,
    "production execution needs one dedicated fast-result wakeup port")
  val io = IO(new OooIexExecutionPipelineIO(
    p, requireStoreReservation, coreParams))

  val issue = Module(new OooIexPipeline(profile, requireStoreReservation))
  val execute = Module(new OooIexExecutionCluster(profile, Some(coreParams)))

  issue.io.s1 <> io.s1
  if (requireStoreReservation) {
    io.storeReserve.get <> issue.io.storeReserve.get
  }
  io.dispatchReleases <> issue.io.dispatchReleases
  issue.io.ptagRecycle <> io.ptagRecycle
  issue.io.recoveryPrepare := io.recoveryPrepare
  execute.io.recoveryPrepare := io.recoveryPrepare
  val recoveryReady = issue.io.recoveryPrepareReady &&
    execute.io.recoveryPrepareReady
  io.recoveryPrepareReady := io.recoveryPrepare.valid && recoveryReady
  io.recoveryPrepared := issue.io.recoveryPrepared
  io.recoveryPrepared.valid := issue.io.recoveryPrepared.valid &&
    execute.io.recoveryPrepareReady
  io.recoveryRejected := issue.io.recoveryRejected
  when(!issue.io.recoveryRejected.valid && io.recoveryPrepare.valid &&
      execute.io.recoveryRejected) {
    io.recoveryRejected.valid := true.B
    io.recoveryRejected.bits.requested := io.recoveryPrepare.bits
    io.recoveryRejected.bits.stidInRange :=
      io.recoveryPrepare.bits.oldHead.stid < p.stidCount.U
    io.recoveryRejected.bits.residentRowsExact := false.B
    io.recoveryRejected.bits.s1RowsExact := true.B
  }
  val commonRecoveryFire = io.recoveryFire && io.recoveryPrepareReady
  issue.io.recoveryFire := commonRecoveryFire
  execute.io.recoveryFire := commonRecoveryFire
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

  for (index <- 0 until 2) {
    io.storeAddress(index).valid := execute.io.storeAddress(index).valid
    io.storeAddress(index).bits := execute.io.storeAddress(index).bits
    execute.io.storeAddress(index).ready := io.storeAddress(index).ready
    io.storeData(index).valid := execute.io.storeData(index).valid
    io.storeData(index).bits := execute.io.storeData(index).bits
    execute.io.storeData(index).ready := io.storeData(index).ready
    io.multiCycleAlu(index).valid := execute.io.multiCycleAlu(index).valid
    io.multiCycleAlu(index).bits := execute.io.multiCycleAlu(index).bits
    execute.io.multiCycleAlu(index).ready := io.multiCycleAlu(index).ready
    io.system(index).valid := execute.io.system(index).valid
    io.system(index).bits := execute.io.system(index).bits
    execute.io.system(index).ready := io.system(index).ready
    io.pointerAuth(index).valid := execute.io.pointerAuth(index).valid
    io.pointerAuth(index).bits := execute.io.pointerAuth(index).bits
    execute.io.pointerAuth(index).ready := io.pointerAuth(index).ready
  }
  io.floatingVector.valid := execute.io.floatingVector.valid
  io.floatingVector.bits := execute.io.floatingVector.bits
  execute.io.floatingVector.ready := io.floatingVector.ready
  io.engineCommand.valid := execute.io.engineCommand.valid
  io.engineCommand.bits := execute.io.engineCommand.bits
  execute.io.engineCommand.ready := io.engineCommand.ready

  io.load <> execute.io.load
  for (lane <- 0 until p.iexTerminalWidth) {
    io.bctrl(lane).valid := execute.io.bctrl(lane).valid
    io.bctrl(lane).bits := execute.io.bctrl(lane).bits
    execute.io.bctrl(lane).ready := io.bctrl(lane).ready
    io.trace(lane).valid := execute.io.trace(lane).valid
    io.trace(lane).bits := execute.io.trace(lane).bits
    execute.io.trace(lane).ready := io.trace(lane).ready
    io.completion(lane).valid := execute.io.completion(lane).valid
    io.completion(lane).bits := execute.io.completion(lane).bits
    execute.io.completion(lane).ready := io.completion(lane).ready
  }

  io.terminalFireMask := execute.io.terminalFireMask
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
  io.empty := issue.io.empty && execute.io.empty
  io.pProtocolError := issue.io.pProtocolError
  io.localProtocolError := issue.io.localProtocolError

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && issue.io.recoveryPrepareReady &&
      issue.io.recoveryPrepared.valid,
      "execution-pipeline recovery needs one held prepared issue plan")
  }
}
